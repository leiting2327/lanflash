import Foundation
import Network

/// Thin async wrapper around NWConnection with an exact-read buffer.
final class TCPConn {
    private let conn: NWConnection
    private let queue: DispatchQueue
    private var buffer = Data()

    init(host: String, port: UInt16, queue: DispatchQueue) {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        tcpOptions.connectionTimeout = 8
        let params = NWParameters(tls: nil, tcp: tcpOptions)
        params.allowLocalEndpointReuse = true
        self.queue = queue
        self.conn = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? .any,
            using: params
        )
    }

    init(connection: NWConnection, queue: DispatchQueue) {
        self.conn = connection
        self.queue = queue
    }

    func start(timeout: TimeInterval = 6) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let lock = NSLock()
            var resumed = false

            func finish(_ result: Result<Void, Error>) {
                lock.lock()
                defer { lock.unlock() }
                guard !resumed else { return }
                resumed = true
                cont.resume(with: result)
            }

            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    finish(.success(()))
                case .failed(let error):
                    finish(.failure(error))
                case .waiting(let error):
                    finish(.failure(error))
                case .cancelled:
                    finish(.failure(URLError(.cancelled)))
                default:
                    break
                }
            }
            conn.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) {
                finish(.failure(URLError(.timedOut)))
            }
        }
    }

    func startAccepted() {
        conn.start(queue: queue)
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { error in
                if let error {
                    cont.resume(throwing: error)
                } else {
                    cont.resume()
                }
            })
        }
    }

    func readFrame() async throws -> (UInt8, Data) {
        let head = try await readExact(5)
        let type = head[head.startIndex]
        let length = Int(head[1]) << 24 | Int(head[2]) << 16 | Int(head[3]) << 8 | Int(head[4])
        if length < 0 || length > Proto.maxFrame {
            throw URLError(.cannotParseResponse)
        }
        let payload = try await readExact(length)
        return (type, payload)
    }

    func readExact(_ count: Int) async throws -> Data {
        while buffer.count < count {
            let chunk = try await receiveChunk()
            buffer.append(chunk)
        }
        let out = Data(buffer.prefix(count))
        buffer.removeFirst(count)
        return out
    }

    private func receiveChunk() async throws -> Data {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { data, _, isComplete, error in
                if let error {
                    cont.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    cont.resume(returning: data)
                } else if isComplete {
                    cont.resume(throwing: URLError(.networkConnectionLost))
                } else {
                    cont.resume(throwing: URLError(.resourceUnavailable))
                }
            }
        }
    }

    func cancel() {
        conn.cancel()
    }
}
