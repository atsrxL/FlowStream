import Foundation
import AVFoundation
import CryptoKit
import UniformTypeIdentifiers

/// Chunk cache shared by playback read-ahead and next-video preload. Memory or disk, chosen in Settings.
final class VideoCache {
    static let shared = VideoCache()
    static let chunk: Int64 = 1 << 20
    private let lock = NSLock()
    private var inflight = [String: Task<Data, Error>]()
    private let mem = NSCache<NSString, NSData>()
    private var networkBytes: Int64 = 0
    private var lastPrune = Date.distantPast
    private let dir: URL
    private var preloadTask: Task<Void, Never>?

    init() {
        dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("video-cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    var totalNetworkBytes: Int64 { locked { networkBytes } }
    private func locked<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }

    static func effectiveRamBudget(requestedMb: Int) -> Int64 {
        min(Int64(requestedMb) << 20, Int64(ProcessInfo.processInfo.physicalMemory / 6))
    }
    private var useMemory: Bool { Store.shared.memoryPreload }
    private var diskCap: Int64 { (Int64(Store.shared.preloadMb) * 2 + 256) << 20 }

    private func file(_ key: String) -> URL {
        let h = SHA256.hash(data: Data(key.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
        return dir.appendingPathComponent(h)
    }
    private func lookup(_ key: String) -> Data? {
        if useMemory { return mem.object(forKey: key as NSString) as Data? }
        let f = file(key)
        guard let d = try? Data(contentsOf: f, options: .mappedIfSafe) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: f.path)
        return d
    }
    private func store(_ key: String, _ data: Data) {
        if useMemory {
            mem.totalCostLimit = Int(Self.effectiveRamBudget(requestedMb: Store.shared.preloadMb))
            mem.setObject(data as NSData, forKey: key as NSString, cost: data.count)
            return
        }
        try? data.write(to: file(key), options: .atomic)
        pruneIfNeeded()
    }
    private func pruneIfNeeded() {
        let go: Bool = locked {
            if Date().timeIntervalSince(lastPrune) < 5 { return false }
            lastPrune = Date(); return true
        }
        if !go { return }
        DispatchQueue.global(qos: .utility).async { [self] in
            let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
            guard let urls = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys) else { return }
            var items = urls.compactMap { u -> (URL, Date, Int64)? in
                guard let v = try? u.resourceValues(forKeys: Set(keys)) else { return nil }
                return (u, v.contentModificationDate ?? .distantPast, Int64(v.fileSize ?? 0))
            }
            var total = items.reduce(0) { $0 + $1.2 }
            items.sort { $0.1 < $1.1 }
            for i in items where total > diskCap { try? FileManager.default.removeItem(at: i.0); total -= i.2 }
        }
    }

    /// Returns one aligned chunk, fetching it at most once even when several readers ask for it.
    func chunk(key: String, fetch: @escaping () async throws -> Data) async throws -> Data {
        if let d = lookup(key) { return d }
        let task = claim(key: key, fetch: fetch)
        do { return try await task.value } catch {
            locked { inflight[key] = nil }
            throw error
        }
    }
    private func claim(key: String, fetch: @escaping () async throws -> Data) -> Task<Data, Error> {
        locked {
            if let t = inflight[key] { return t }
            let t = Task { [self] () -> Data in
                let d = try await fetch()
                if !d.isEmpty { store(key, d) }
                locked { networkBytes += Int64(d.count); inflight[key] = nil }
                return d
            }
            inflight[key] = t
            return t
        }
    }
    func has(_ key: String) -> Bool { lookup(key) != nil }

    func clear() async {
        preloadTask?.cancel()
        mem.removeAllObjects()
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    func diskUsage() -> Int64 {
        let urls = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return urls.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    /// Warms the first N MB (and the tail, where non-faststart MP4 keep their index) of the next video.
    func preload(reader: MediaReader?, path: String, megabytes: Int) {
        preloadTask?.cancel()
        guard let reader = reader, megabytes > 0 else { return }
        preloadTask = Task.detached(priority: .utility) {
            do {
                let size = try await reader.size(of: path)
                let head = min(size, Int64(megabytes) << 20)
                var off: Int64 = 0
                while off < head { try Task.checkCancellation(); _ = try await reader.read(path, offset: off, length: min(Self.chunk, head - off)); off += Self.chunk }
                var tail = max(head, size - (4 << 20))
                tail -= tail % Self.chunk
                while tail < size { try Task.checkCancellation(); _ = try await reader.read(path, offset: tail, length: min(Self.chunk, size - tail)); tail += Self.chunk }
            } catch {}
        }
    }
}

/// Aligned, cached reads on top of a RemoteSource. Every read goes through VideoCache.
final class MediaReader {
    let source: RemoteSource
    let serverId: String
    private var sizes = [String: Int64]()
    private let lock = NSLock()
    init(source: RemoteSource, serverId: String) { self.source = source; self.serverId = serverId }
    func remember(size: Int64, for path: String) { if size > 0 { lock.lock(); sizes[path] = size; lock.unlock() } }
    private func known(_ path: String) -> Int64? { lock.lock(); defer { lock.unlock() }; return sizes[path] }
    func size(of path: String) async throws -> Int64 {
        if let s = known(path) { return s }
        let n = try await source.size(path)
        remember(size: n, for: path)
        return n
    }
    /// Reads up to `length` bytes at `offset`, never crossing one chunk boundary.
    func read(_ path: String, offset: Int64, length: Int64) async throws -> Data {
        let size = try await size(of: path)
        guard offset < size else { return Data() }
        let c = VideoCache.chunk
        let index = offset / c
        let start = index * c, end = min(size, start + c)
        let key = "\(serverId)|\(path)|\(size)|\(index)"
        let data = try await VideoCache.shared.chunk(key: key) { [source] in try await source.read(path, range: start..<end) }
        // Read ahead one chunk.
        if end < size {
            let nk = "\(serverId)|\(path)|\(size)|\(index + 1)"
            if !VideoCache.shared.has(nk) {
                let ns = end, ne = min(size, end + c)
                Task.detached(priority: .utility) { [source] in _ = try? await VideoCache.shared.chunk(key: nk) { try await source.read(path, range: ns..<ne) } }
            }
        }
        let rel = Int(offset - start)
        guard rel < data.count else { return Data() }
        let take = min(Int(length), data.count - rel)
        return data.subdata(in: (data.startIndex + rel)..<(data.startIndex + rel + take))
    }
}

/// Serves AVPlayer / AVAssetImageGenerator through a custom URL scheme backed by `MediaReader`.
final class MediaLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "flowx"
    let reader: MediaReader
    let queue = DispatchQueue(label: "flowstream.mediaLoader")
    private var tasks = [ObjectIdentifier: Task<Void, Never>]()
    private let lock = NSLock()

    init(reader: MediaReader) { self.reader = reader }

    func asset(for item: RemoteItem) -> AVURLAsset {
        reader.remember(size: item.size, for: item.path)
        var c = URLComponents()
        c.scheme = Self.scheme
        c.host = "media"
        c.path = "/" + item.path
        let asset = AVURLAsset(url: c.url ?? URL(string: "\(Self.scheme)://media/video")!)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }
    func cancelAll() {
        lock.lock(); let t = tasks; tasks.removeAll(); lock.unlock()
        t.values.forEach { $0.cancel() }
    }

    private static func contentType(_ path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        switch ext {
        case "mp4": return "public.mpeg-4"
        case "m4v": return "com.apple.m4v-video"
        case "mov": return "com.apple.quicktime-movie"
        case "3gp": return "public.3gpp"
        default: return UTType(filenameExtension: ext)?.identifier ?? "public.mpeg-4"
        }
    }

    func resourceLoader(_ l: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource req: AVAssetResourceLoadingRequest) -> Bool {
        guard let url = req.request.url, url.scheme == Self.scheme else { return false }
        var path = url.path
        if path.hasPrefix("/") { path.removeFirst() }
        let key = ObjectIdentifier(req)
        let task = Task { [weak self] in
            guard let self = self else { return }
            do {
                try await self.serve(req, path: path)
                if !req.isCancelled && !req.isFinished { req.finishLoading() }
            } catch {
                if !req.isFinished { req.finishLoading(with: error) }
            }
            self.forget(key)
        }
        track(key, task)
        return true
    }
    func resourceLoader(_ l: AVAssetResourceLoader, didCancel req: AVAssetResourceLoadingRequest) {
        forget(ObjectIdentifier(req))?.cancel()
    }
    private func track(_ key: ObjectIdentifier, _ t: Task<Void, Never>) { lock.lock(); tasks[key] = t; lock.unlock() }
    @discardableResult private func forget(_ key: ObjectIdentifier) -> Task<Void, Never>? {
        lock.lock(); defer { lock.unlock() }
        let t = tasks[key]; tasks[key] = nil; return t
    }

    private func serve(_ req: AVAssetResourceLoadingRequest, path: String) async throws {
        let size = try await reader.size(of: path)
        if let info = req.contentInformationRequest {
            info.contentType = Self.contentType(path)
            info.contentLength = size
            info.isByteRangeAccessSupported = true
        }
        guard let dr = req.dataRequest else { return }
        var offset = dr.requestedOffset
        let end = dr.requestsAllDataToEndOfResource ? size : min(size, dr.requestedOffset + Int64(dr.requestedLength))
        while offset < end {
            try Task.checkCancellation()
            if req.isCancelled { return }
            let data = try await reader.read(path, offset: offset, length: end - offset)
            if data.isEmpty { break }
            dr.respond(with: data)
            offset += Int64(data.count)
        }
    }
}

