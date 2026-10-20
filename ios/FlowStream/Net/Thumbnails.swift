import Foundation
import AVFoundation
import CryptoKit
import UIKit

/// Per-server thumbnails for the file list; JPEG disk cache capped at 128 MB.
final class Thumbnails {
    static let shared = Thumbnails()
    private let dir: URL
    private let mem = NSCache<NSString, UIImage>()
    private let gate = AsyncGate(limit: 2)
    private var sources = [String: (MediaLoader, RemoteSource)]()
    private let lock = NSLock()

    init() {
        dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("thumbnails", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        mem.countLimit = 300
    }
    private func file(_ server: Server, _ item: RemoteItem) -> URL {
        let h = SHA256.hash(data: Data("\(server.id)|\(item.path)|\(item.size)".utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
        return dir.appendingPathComponent(h + ".jpg")
    }
    private func loader(for server: Server) throws -> MediaLoader {
        lock.lock(); defer { lock.unlock() }
        if let l = sources[server.id] { return l.0 }
        let src = try Sources.make(server)
        let l = MediaLoader(reader: MediaReader(source: src, serverId: server.id))
        sources[server.id] = (l, src)
        return l
    }
    func image(server: Server, item: RemoteItem) async -> UIImage? {
        let f = file(server, item)
        if let m = mem.object(forKey: f.path as NSString) { return m }
        if let d = try? Data(contentsOf: f), let i = UIImage(data: d) { mem.setObject(i, forKey: f.path as NSString); return i }
        await gate.enter()
        defer { Task { await gate.leave() } }
        if Task.isCancelled { return nil }
        guard let loader = try? loader(for: server) else { return nil }
        let asset = loader.asset(for: item)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 400, height: 400)
        gen.requestedTimeToleranceBefore = .positiveInfinity
        gen.requestedTimeToleranceAfter = .positiveInfinity
        let duration = (try? await asset.load(.duration).seconds) ?? 0
        let t = CMTime(seconds: duration.isFinite && duration > 0 ? min(10, duration * 0.1) : 1, preferredTimescale: 600)
        guard let (cg, _) = try? await gen.image(at: t) else { return nil }
        let img = UIImage(cgImage: cg)
        if let jpg = img.jpegData(compressionQuality: 0.8) { try? jpg.write(to: f, options: .atomic); prune() }
        mem.setObject(img, forKey: f.path as NSString)
        return img
    }
    private func prune() {
        DispatchQueue.global(qos: .utility).async { [dir] in
            let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
            guard let urls = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys) else { return }
            var items = urls.compactMap { u -> (URL, Date, Int64)? in
                guard let v = try? u.resourceValues(forKeys: Set(keys)) else { return nil }
                return (u, v.contentModificationDate ?? .distantPast, Int64(v.fileSize ?? 0))
            }
            var total = items.reduce(0) { $0 + $1.2 }
            items.sort { $0.1 < $1.1 }
            for i in items where total > 128 << 20 { try? FileManager.default.removeItem(at: i.0); total -= i.2 }
        }
    }
    func clear() {
        mem.removeAllObjects()
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    func closeSources() {
        lock.lock(); let s = sources; sources.removeAll(); lock.unlock()
        s.values.forEach { $0.0.cancelAll(); $0.1.close() }
    }
}

actor AsyncGate {
    private var available: Int
    private var waiters = [CheckedContinuation<Void, Never>]()
    init(limit: Int) { available = limit }
    func enter() async {
        if available > 0 { available -= 1; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func leave() {
        if waiters.isEmpty { available += 1 } else { waiters.removeFirst().resume() }
    }
}

/// Scrub-bar preview frames, on their own connection so playback is never starved.
final class FramePreview {
    private var loader: MediaLoader?
    private var source: RemoteSource?
    private var generator: AVAssetImageGenerator?
    private var epoch = 0
    func prepare(server: Server, item: RemoteItem) {
        reset()
        guard let src = try? Sources.make(server) else { return }
        source = src
        let l = MediaLoader(reader: MediaReader(source: src, serverId: server.id))
        loader = l
        let g = AVAssetImageGenerator(asset: l.asset(for: item))
        g.appliesPreferredTrackTransform = true
        g.maximumSize = CGSize(width: 480, height: 270)
        g.requestedTimeToleranceBefore = CMTime(seconds: 2, preferredTimescale: 600)
        g.requestedTimeToleranceAfter = CMTime(seconds: 2, preferredTimescale: 600)
        generator = g
    }
    func request(_ ms: Int64, completion: @escaping (Int, UIImage?) -> Void) {
        guard let g = generator else { completion(epoch, nil); return }
        let e = epoch
        g.cancelAllCGImageGeneration()
        g.generateCGImagesAsynchronously(forTimes: [NSValue(time: CMTime(value: ms, timescale: 1000))]) { _, cg, _, result, _ in
            DispatchQueue.main.async { completion(e, result == .succeeded ? cg.map { UIImage(cgImage: $0) } : nil) }
        }
    }
    func cancel() { generator?.cancelAllCGImageGeneration() }
    var currentEpoch: Int { epoch }
    func reset() {
        epoch += 1
        generator?.cancelAllCGImageGeneration(); generator = nil
        loader?.cancelAll(); loader = nil
        source?.close(); source = nil
    }
}
