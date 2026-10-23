import Combine
import Foundation
import Network
import UIKit

struct RecvFile: Identifiable {
    let id: Int
    let name: String
    let size: Int64
    let path: URL
}

struct RecvState {
    var active = false
    var waiting = false
    var finished = false
    var files: [RecvFile] = []
    var received: Int64 = 0
    var total: Int64 = 0
    var speed: Int64 = 0
    var port: UInt16 = 0
    var ips: [String] = []
    var pairText: String = ""
}

/// Multi-stream TCP receiver. Each connection writes chunks at absolute file
/// offsets, so parallel streams never serialize on a single write head.
final class Receiver: ObservableObject {
    @Published private(set) var state = RecvState()

    private final class Session {
        let token: String
        let dir: URL
        var files: [RecvFile] = []
        var chunkSize = Proto.chunkSize
        var chunkBase: [Int] = []
        var bitmap: [Bool] = []
        var received: Int64 = 0
        var total: Int64 = 0
        var finished = false
        let lock = NSLock()

        init(token: String, dir: URL) {
            self.token = token
            self.dir = dir
        }
    }

    private let queue = DispatchQueue(label: "lanflash.receiver", qos: .userInitiated)
    private var listener: NWListener?
    private var session: Session?
    private var ticker: Task<Void, Never>?
    private var connections: [TCPConn] = []
    private let connLock = NSLock()

    func start() {
        stop()

        let token = Proto.randomToken()
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LanFlash", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let s = Session(token: token, dir: dir)
        session = s

        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let l = try NWListener(using: params, on: .any)
            listener = l

            l.newConnectionHandler = { [weak self] conn in
                guard let self else {
                    conn.cancel()
                    return
                }
                conn.start(queue: self.queue)
                let tcp = TCPConn(connection: conn, queue: self.queue)
                self.connLock.lock()
                self.connections.append(tcp)
                self.connLock.unlock()
                Task { await self.handle(tcp, session: s) }
            }

            l.stateUpdateHandler = { [weak self] st in
                guard let self else { return }
                if case .ready = st {
                    let port = l.port?.rawValue ?? 0
                    let ips = Proto.localIPv4()
                    let info = PairInfo(ips: ips, port: port, token: token, name: UIDevice.current.name)
                    DispatchQueue.main.async {
                        self.state = RecvState(
                            active: true,
                            waiting: true,
                            port: port,
                            ips: ips,
                            pairText: info.encode()
                        )
                    }
                    self.startTicker(s)
                }
            }

            l.start(queue: queue)
        } catch {
            DispatchQueue.main.async { self.state = RecvState() }
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        ticker?.cancel()
        ticker = nil
        connLock.lock()
        connections.forEach { $0.cancel() }
        connections.removeAll()
        connLock.unlock()
        session = nil
        if !state.finished {
            DispatchQueue.main.async { self.state = RecvState() }
        }
    }

    private func startTicker(_ s: Session) {
        ticker?.cancel()
        ticker = Task { [weak self] in
            var lastBytes: Int64 = 0
            var lastAt = Date().timeIntervalSince1970
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 300_000_000)
                guard let self, !s.finished else { return }
                s.lock.lock()
                let bytes = s.received
                let total = s.total
                let files = s.files
                let waiting = files.isEmpty
                s.lock.unlock()

                let now = Date().timeIntervalSince1970
                let dt = max(0.001, now - lastAt)
                let speed = Int64(Double(max(0, bytes - lastBytes)) / dt)
                lastBytes = bytes
                lastAt = now

                await MainActor.run {
                    self.state.received = bytes
                    self.state.total = total
                    self.state.speed = speed
                    self.state.files = files
                    self.state.waiting = waiting
                }
            }
        }
    }

    private func handle(_ tcp: TCPConn, session s: Session) async {
        var handles: [Int: FileHandle] = [:]
        defer { handles.values.forEach { try? $0.close() } }

        do {
            let (type, payload) = try await tcp.readFrame()
            guard type == Proto.hello,
                  let obj = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any],
                  (obj["token"] as? String) == s.token else {
                try? await tcp.send(Proto.frame(Proto.error, Data("bad token".utf8)))
                return
            }

            let streamIndex = (obj["stream"] as? NSNumber)?.intValue ?? 0
            if streamIndex == 0 {
                let (mtype, mpayload) = try await tcp.readFrame()
                guard mtype == Proto.manifest else { return }
                try prepareManifest(s, mpayload)
            } else {
                var waited = 0
                while waited < 300 {
                    s.lock.lock()
                    let ready = !s.files.isEmpty
                    s.lock.unlock()
                    if ready { break }
                    try await Task.sleep(nanoseconds: 100_000_000)
                    waited += 1
                }
                s.lock.lock()
                let ready = !s.files.isEmpty
                s.lock.unlock()
                if !ready { return }
            }

            while !s.finished {
                let (ft, fp) = try await tcp.readFrame()
                if ft == Proto.data {
                    writeChunk(s, fp, handles: &handles)
                } else if ft == Proto.done {
                    let ok = isComplete(s)
                    var response: [String: Any] = ["ok": ok]
                    if !ok { response["missing"] = missingChunks(s) }
                    try await tcp.send(Proto.frame(Proto.complete, Proto.json(response)))
                    if ok {
                        finish(s)
                        return
                    }
                } else if ft == Proto.error {
                    return
                }
            }
        } catch {
            // dropped connection: the sender retransmits missing chunks
        }
    }

    private func prepareManifest(_ s: Session, _ payload: Data) throws {
        guard let obj = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any],
              let arr = obj["files"] as? [[String: Any]] else {
            throw URLError(.cannotParseResponse)
        }
        let chunkSize = max(64 * 1024, min((obj["chunkSize"] as? NSNumber)?.intValue ?? Proto.chunkSize, 16 * 1024 * 1024))

        var files: [RecvFile] = []
        var bases: [Int] = []
        var chunks = 0
        var total: Int64 = 0

        for (i, f) in arr.enumerated() {
            let rawName = (f["name"] as? String) ?? "file_\(i)"
            let size = max(0, (f["size"] as? NSNumber)?.int64Value ?? 0)
            let name = uniqueName(in: s.dir, name: sanitize(rawName))
            let url = s.dir.appendingPathComponent(name)
            FileManager.default.createFile(atPath: url.path, contents: nil)
            let handle = try FileHandle(forWritingTo: url)
            try handle.truncate(atOffset: UInt64(size))
            try? handle.close()

            files.append(RecvFile(id: i, name: name, size: size, path: url))
            bases.append(chunks)
            chunks += Int((size + Int64(chunkSize) - 1) / Int64(chunkSize))
            total += size
        }

        s.lock.lock()
        s.chunkSize = chunkSize
        s.files = files
        s.chunkBase = bases
        s.bitmap = [Bool](repeating: false, count: chunks)
        s.total = total
        s.lock.unlock()

        DispatchQueue.main.async {
            self.state.waiting = false
            self.state.files = files
            self.state.total = total
            self.state.received = 0
        }
    }

    private func writeChunk(_ s: Session, _ payload: Data, handles: inout [Int: FileHandle]) {
        guard payload.count >= 20 else { return }
        let fileId = Int(payload.u32(at: 0))
        let offset = payload.u64(at: 4)
        let expected = payload.u32(at: 12)
        let len = Int(payload.u32(at: 16))
        guard len >= 0, payload.count >= 20 + len else { return }

        s.lock.lock()
        let validFile = fileId >= 0 && fileId < s.files.count
        let path = validFile ? s.files[fileId].path : nil
        let chunkSize = s.chunkSize
        let base = validFile ? s.chunkBase[fileId] : 0
        s.lock.unlock()
        guard let path else { return }

        let data = payload.subdata(in: 20..<(20 + len))
        guard CRC32.checksum(data) == expected else { return }

        do {
            let handle: FileHandle
            if let existing = handles[fileId] {
                handle = existing
            } else {
                handle = try FileHandle(forWritingTo: path)
                handles[fileId] = handle
            }
            try handle.seek(toOffset: offset)
            try handle.write(contentsOf: data)
        } catch {
            return
        }

        let index = base + Int(offset / UInt64(chunkSize))
        s.lock.lock()
        if index >= 0 && index < s.bitmap.count && !s.bitmap[index] {
            s.bitmap[index] = true
            s.received += Int64(len)
        }
        s.lock.unlock()
    }

    private func isComplete(_ s: Session) -> Bool {
        s.lock.lock()
        defer { s.lock.unlock() }
        return !s.bitmap.isEmpty && s.bitmap.allSatisfy { $0 }
    }

    private func missingChunks(_ s: Session) -> [Int] {
        s.lock.lock()
        defer { s.lock.unlock() }
        var out: [Int] = []
        for (i, done) in s.bitmap.enumerated() where !done {
            out.append(i)
            if out.count >= 128 { break }
        }
        return out
    }

    private func finish(_ s: Session) {
        s.lock.lock()
        if s.finished {
            s.lock.unlock()
            return
        }
        s.finished = true
        let files = s.files
        let total = s.total
        s.lock.unlock()

        listener?.cancel()
        listener = nil
        ticker?.cancel()
        ticker = nil

        DispatchQueue.main.async {
            self.state = RecvState(
                active: false,
                finished: true,
                files: files,
                received: total,
                total: total
            )
        }
    }

    private func sanitize(_ name: String) -> String {
        let base = name.split(separator: "/").last.map(String.init) ?? name
        let cleaned = base.components(separatedBy: CharacterSet.controlCharacters).joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty || cleaned == "." || cleaned == ".." { return "file" }
        return cleaned
    }

    private func uniqueName(in dir: URL, name: String) -> String {
        let fm = FileManager.default
        var candidate = name
        var i = 1
        let ext = (name as NSString).pathExtension
        let stem = (name as NSString).deletingPathExtension
        while fm.fileExists(atPath: dir.appendingPathComponent(candidate).path) {
            candidate = ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)"
            i += 1
        }
        return candidate
    }
}
