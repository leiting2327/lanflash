import Combine
import Foundation
import UIKit

struct SendProgress {
    var sentBytes: Int64 = 0
    var totalBytes: Int64 = 0
    var speed: Int64 = 0
    var fileCount: Int = 0
}

struct SendError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private final class Counter {
    private let lock = NSLock()
    private var value: Int64 = 0

    func add(_ n: Int) {
        lock.lock()
        value += Int64(n)
        lock.unlock()
    }

    func reset() {
        lock.lock()
        value = 0
        lock.unlock()
    }

    var current: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// Splits the payload into fixed 4 MB chunks and distributes chunk k over
/// connection k % N.
final class Sender: ObservableObject {
    @Published private(set) var progress = SendProgress()
    @Published private(set) var sending = false
    @Published private(set) var done = false
    @Published private(set) var error: String?

    private struct SFile {
        let id: Int
        let url: URL
        let name: String
        let size: Int64
    }

    private struct Chunk {
        let fileId: Int
        let offset: Int64
        let length: Int
    }

    private let queue = DispatchQueue(label: "lanflash.sender", qos: .userInitiated)
    private let counter = Counter()

    func reset() {
        done = false
        error = nil
        progress = SendProgress()
    }

    func send(pair: PairInfo, urls: [URL]) {
        guard !sending else { return }
        guard !urls.isEmpty else {
            error = "请选择要发送的文件"
            return
        }
        sending = true
        done = false
        error = nil
        progress = SendProgress()
        counter.reset()

        Task { [weak self] in
            guard let self else { return }
            do {
                let count = try await self.run(pair: pair, urls: urls)
                await MainActor.run {
                    self.sending = false
                    self.done = true
                    self.progress.sentBytes = self.progress.totalBytes
                    self.progress.speed = 0
                    self.progress.fileCount = count
                }
            } catch {
                await MainActor.run {
                    self.sending = false
                    self.error = error.localizedDescription
                }
            }
        }
    }

    private func run(pair: PairInfo, urls: [URL]) async throws -> Int {
        var scoped: [URL] = []
        defer { scoped.forEach { $0.stopAccessingSecurityScopedResource() } }

        var files: [SFile] = []
        for (i, url) in urls.enumerated() {
            if url.startAccessingSecurityScopedResource() {
                scoped.append(url)
            }
            var size: Int64 = 0
            if let values = try? url.resourceValues(forKeys: [.fileSizeKey]), let s = values.fileSize {
                size = Int64(s)
            } else if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                      let n = attrs[.size] as? NSNumber {
                size = n.int64Value
            }
            files.append(SFile(id: i, url: url, name: url.lastPathComponent, size: size))
        }

        var plan: [Chunk] = []
        for f in files {
            var offset: Int64 = 0
            while offset < f.size {
                let len = Int(min(Int64(Proto.chunkSize), f.size - offset))
                plan.append(Chunk(fileId: f.id, offset: offset, length: len))
                offset += Int64(len)
            }
        }

        let total = files.reduce(Int64(0)) { $0 + $1.size }
        let streamCount = min(Proto.defaultStreams, max(1, plan.count))
        let conns = try await connect(pair: pair, count: streamCount)
        defer { conns.forEach { $0.cancel() } }

        let manifestFiles: [[String: Any]] = files.map {
            ["id": $0.id, "name": $0.name, "size": $0.size]
        }
        let manifest = Proto.json(["chunkSize": Proto.chunkSize, "files": manifestFiles])

        for (i, conn) in conns.enumerated() {
            let hello = Proto.json([
                "v": Proto.version,
                "token": pair.token,
                "stream": i,
                "streams": conns.count,
                "name": UIDevice.current.name,
            ])
            try await conn.send(Proto.frame(Proto.hello, hello))
        }
        try await conns[0].send(Proto.frame(Proto.manifest, manifest))

        let ticker = startTicker(total: total, fileCount: files.count)
        defer { ticker.cancel() }

        try await withThrowingTaskGroup(of: Void.self) { group in
            for i in 0..<conns.count {
                let conn = conns[i]
                group.addTask {
                    var handles: [Int: FileHandle] = [:]
                    defer { handles.values.forEach { try? $0.close() } }
                    var index = i
                    while index < plan.count {
                        let chunk = plan[index]
                        try await self.sendChunk(conn, meta: files[chunk.fileId], chunk: chunk, handles: &handles)
                        self.counter.add(chunk.length)
                        index += conns.count
                    }
                }
            }
            try await group.waitForAll()
        }

        var missing: [Int] = []
        var retryHandles: [Int: FileHandle] = [:]
        defer { retryHandles.values.forEach { try? $0.close() } }

        for _ in 0..<3 {
            try await conns[0].send(Proto.frame(Proto.done, Proto.json(["total": plan.count])))
            let (type, payload) = try await conns[0].readFrame()
            guard type == Proto.complete else { throw SendError(message: "接收端没有响应校验请求") }
            let obj = ((try? JSONSerialization.jsonObject(with: payload)) as? [String: Any]) ?? [:]
            let ok = (obj["ok"] as? NSNumber)?.boolValue ?? false
            if ok {
                missing = []
                break
            }
            let list = ((obj["missing"] as? [NSNumber]) ?? []).map { $0.intValue }
            missing = list
            if list.isEmpty { break }
            for ci in list where ci >= 0 && ci < plan.count {
                let chunk = plan[ci]
                try await sendChunk(conns[0], meta: files[chunk.fileId], chunk: chunk, handles: &retryHandles)
            }
        }

        if !missing.isEmpty {
            throw SendError(message: "传输校验失败，仍有 \(missing.count) 个分块缺失，请重试")
        }

        await MainActor.run {
            self.progress = SendProgress(sentBytes: total, totalBytes: total, speed: 0, fileCount: files.count)
        }
        return files.count
    }

    private func connect(pair: PairInfo, count: Int) async throws -> [TCPConn] {
        var opened: [TCPConn] = []
        var workingIp: String?

        for ip in pair.ips {
            let conn = TCPConn(host: ip, port: pair.port, queue: queue)
            do {
                try await conn.start(timeout: 5)
                opened.append(conn)
                workingIp = ip
                break
            } catch {
                conn.cancel()
            }
        }

        guard let ip = workingIp else {
            throw SendError(
                message: "无法连接接收端（\(pair.ips.joined(separator: ", "))）。请确认两台设备在同一 Wi-Fi，且接收页面保持打开。"
            )
        }

        do {
            while opened.count < count {
                let conn = TCPConn(host: ip, port: pair.port, queue: queue)
                try await conn.start(timeout: 5)
                opened.append(conn)
            }
        } catch {
            opened.forEach { $0.cancel() }
            throw SendError(message: "建立并发传输通道失败：\(error.localizedDescription)")
        }
        return opened
    }

    private func startTicker(total: Int64, fileCount: Int) -> Task<Void, Never> {
        let box = counter
        return Task { [weak self] in
            var last: Int64 = 0
            var lastAt = Date().timeIntervalSince1970
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 300_000_000)
                guard let self else { return }
                let sent = box.current
                let now = Date().timeIntervalSince1970
                let dt = max(0.001, now - lastAt)
                let speed = Int64(Double(max(0, sent - last)) / dt)
                last = sent
                lastAt = now
                await MainActor.run {
                    self.progress = SendProgress(
                        sentBytes: sent,
                        totalBytes: total,
                        speed: speed,
                        fileCount: fileCount
                    )
                }
            }
        }
    }

    private func sendChunk(
        _ conn: TCPConn,
        meta: SFile,
        chunk: Chunk,
        handles: inout [Int: FileHandle]
    ) async throws {
        let handle: FileHandle
        if let existing = handles[meta.id] {
            handle = existing
        } else {
            handle = try FileHandle(forReadingFrom: meta.url)
            handles[meta.id] = handle
        }
        try handle.seek(toOffset: UInt64(chunk.offset))
        var data = try handle.read(upToCount: chunk.length) ?? Data()
        if data.count < chunk.length {
            data.append(Data(repeating: 0, count: chunk.length - data.count))
        }

        var payload = Data(capacity: 20 + data.count)
        appendU32(UInt32(meta.id), to: &payload)
        appendU64(UInt64(chunk.offset), to: &payload)
        appendU32(CRC32.checksum(data), to: &payload)
        appendU32(UInt32(data.count), to: &payload)
        payload.append(data)

        try await conn.send(Proto.frame(Proto.data, payload))
    }

    private func appendU32(_ value: UInt32, to data: inout Data) {
        data.append(UInt8((value >> 24) & 0xFF))
        data.append(UInt8((value >> 16) & 0xFF))
        data.append(UInt8((value >> 8) & 0xFF))
        data.append(UInt8(value & 0xFF))
    }

    private func appendU64(_ value: UInt64, to data: inout Data) {
        for shift in stride(from: 56, through: 0, by: -8) {
            data.append(UInt8((value >> UInt64(shift)) & 0xFF))
        }
    }
}
