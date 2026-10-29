import Foundation
import Security

struct RemoteItem: Codable, Hashable, Identifiable {
    var path: String
    var name: String
    var directory: Bool
    var size: Int64
    var id: String { path }
    init(path: String, name: String, directory: Bool, size: Int64) {
        self.path = path
        self.name = name.isEmpty ? MediaPaths.name(path) : name
        self.directory = directory
        self.size = size
    }
}

struct Server: Codable, Identifiable, Hashable {
    var id = UUID().uuidString
    var name = ""
    var protocolName = "SMB"      // "SMB" | "WEBDAV"
    var host = ""
    var port = 445
    var share = ""
    var rootPath = ""
    var url = ""
    var username = ""
    var domain = ""
    var allowHttp = false
    var encryptSmb = false
    var password = ""             // not persisted in JSON; stored in Keychain

    enum CodingKeys: String, CodingKey {
        case id, name, protocolName, host, port, share, rootPath, url, username, domain, allowHttp, encryptSmb
    }
    var isSMB: Bool { protocolName == "SMB" }

    mutating func validate() throws {
        name = name.trimmingCharacters(in: .whitespaces)
        if name.isEmpty { throw FlowError("请填写服务器名称") }
        if isSMB {
            host = host.trimmingCharacters(in: .whitespaces)
            share = share.trimmingCharacters(in: .whitespaces)
            rootPath = try MediaPaths.normalize(rootPath.trimmingCharacters(in: .whitespaces))
            if host.isEmpty || host.contains("/") || host.contains("\\") || host.contains("@") || host.contains(" ") {
                throw FlowError("SMB 主机填写 IP / 主机名，不包含 smb:// 或共享名")
            }
            if share.isEmpty || share.contains("/") || share.contains("\\") || share == ".." {
                throw FlowError("路径填写 /共享名，例如 /share 或 /share/电影")
            }
            if port < 1 || port > 65535 { throw FlowError("端口应在 1–65535 之间") }
        } else {
            url = try DavUrls(url, allowHttp: allowHttp).root.absoluteString
            if username.contains(":") { throw FlowError("Basic 认证用户名不能含冒号") }
        }
    }
    var summary: String { isSMB ? "SMB · \(host)\(location)" : "WebDAV · \(url)" }
    /// SMB path as typed in the form: "/share" or "/share/sub/dir". First component is the share name.
    var location: String {
        share.isEmpty ? "" : "/" + share + (rootPath.isEmpty ? "" : "/" + rootPath)
    }
    mutating func setLocation(_ text: String) {
        let parts = text.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        share = parts.first ?? ""
        rootPath = parts.dropFirst().joined(separator: "/")
    }
    /// Forward-slash path inside the share, for libsmb2.
    func smbPath(_ relative: String) -> String {
        let p = (try? MediaPaths.normalize(relative)) ?? ""
        return rootPath.isEmpty ? p : (p.isEmpty ? rootPath : rootPath + "/" + p)
    }
}

final class Session {
    let id = UUID().uuidString
    var serverId: String
    var serverName: String
    var folder: String
    var queue: [RemoteItem]
    var index: Int
    var position: Int64 = 0
    var duration: Int64 = 0
    var speed: Float = 1
    var current: RemoteItem { queue[index] }
    init(server: Server, folder: String, queue: [RemoteItem], index: Int) {
        serverId = server.id; serverName = server.name; self.folder = folder; self.queue = queue; self.index = index
    }
}

struct MarkedItem: Codable, Identifiable {
    var serverId: String
    var item: RemoteItem
    var time: Double
    var id: String { serverId + "\u{0}" + item.path }
}

enum Keychain {
    private static let service = "dev.flowstream.player.server"
    private static func fallbackKey(_ id: String) -> String { "pw." + id }
    /// Keychain first. Sideloaded / re-signed builds can lack keychain entitlements (errSecMissingEntitlement);
    /// in that case the password falls back to app-private UserDefaults so the server still works.
    static func set(_ value: String, for id: String) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: id]
        SecItemDelete(base as CFDictionary)
        UserDefaults.standard.removeObject(forKey: fallbackKey(id))
        if value.isEmpty { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        if SecItemAdd(add as CFDictionary, nil) != errSecSuccess {
            UserDefaults.standard.set(value, forKey: fallbackKey(id))
        }
    }
    static func get(_ id: String) -> String {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: id, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data { return String(decoding: d, as: UTF8.self) }
        return UserDefaults.standard.string(forKey: fallbackKey(id)) ?? ""
    }
    static func remove(_ id: String) { set("", for: id) }
}

extension Notification.Name {
    static let storeChanged = Notification.Name("flowstream.storeChanged")
}

/// UserDefaults-backed settings, servers, marks. Main-thread use is expected, but it is thread-safe enough for simple values.
final class Store {
    static let shared = Store()
    private let d = UserDefaults.standard
    private let lock = NSLock()
    var randomOrders: [String: RandomOrder] = [:]

    // MARK: servers
    func servers() -> [Server] {
        guard let data = d.data(forKey: "servers"), var list = try? JSONDecoder().decode([Server].self, from: data) else { return [] }
        for i in list.indices { list[i].password = Keychain.get(list[i].id) }
        return list
    }
    func server(_ id: String) -> Server? { servers().first { $0.id == id } }
    func saveServer(_ s: Server) throws {
        var s = s
        try s.validate()
        var list = servers()
        if let i = list.firstIndex(where: { $0.id == s.id }) { list[i] = s } else { list.append(s) }
        Keychain.set(s.password, for: s.id)
        d.set(try JSONEncoder().encode(list), forKey: "servers")
        if activeServer.isEmpty { activeServer = s.id }
        NotificationCenter.default.post(name: .storeChanged, object: nil)
    }
    func deleteServer(_ id: String) {
        var list = servers(); list.removeAll { $0.id == id }
        d.set(try? JSONEncoder().encode(list), forKey: "servers")
        Keychain.remove(id)
        var marks = markedItems(); marks.removeAll { $0.serverId == id }; saveMarks(marks)
        d.removeObject(forKey: "folder." + id)
        if activeServer == id { activeServer = "" }
        NotificationCenter.default.post(name: .storeChanged, object: nil)
    }
    var activeServer: String {
        get { d.string(forKey: "active_server") ?? "" }
        set { d.set(newValue, forKey: "active_server") }
    }
    func lastFolder(_ id: String) -> String { d.string(forKey: "folder." + id) ?? "" }
    func setLastFolder(_ id: String, _ f: String) { d.set(f, forKey: "folder." + id) }

    // MARK: marks
    func markedItems() -> [MarkedItem] {
        lock.lock(); defer { lock.unlock() }
        guard let data = d.data(forKey: "marked_media") else { return [] }
        return (try? JSONDecoder().decode([MarkedItem].self, from: data)) ?? []
    }
    private func saveMarks(_ m: [MarkedItem]) { d.set(try? JSONEncoder().encode(m), forKey: "marked_media") }
    func isMarked(_ serverId: String, _ path: String) -> Bool {
        markedItems().contains { $0.serverId == serverId && $0.item.path == path }
    }
    @discardableResult func toggleMarked(_ serverId: String, _ item: RemoteItem) -> Bool {
        var marks = markedItems()
        let before = marks.count
        marks.removeAll { $0.serverId == serverId && $0.item.path == item.path }
        let removed = marks.count != before
        if !removed { marks.insert(MarkedItem(serverId: serverId, item: item, time: Date().timeIntervalSince1970), at: 0) }
        lock.lock(); saveMarks(marks); lock.unlock()
        return !removed
    }
    func removeMarked(_ serverId: String, _ path: String) {
        var marks = markedItems(); marks.removeAll { $0.serverId == serverId && $0.item.path == path }
        lock.lock(); saveMarks(marks); lock.unlock()
    }

    // MARK: settings
    var thumbnailMode: Bool { get { d.bool(forKey: "thumbnail_mode") } set { d.set(newValue, forKey: "thumbnail_mode") } }
    var randomMode: Bool { get { d.bool(forKey: "random_mode") } set { d.set(newValue, forKey: "random_mode") } }
    var preloadMb: Int {
        get { max(50, min(3072, d.object(forKey: "preload_mb") as? Int ?? 50)) }
        set { d.set(max(50, min(3072, newValue)), forKey: "preload_mb") }
    }
    var memoryPreload: Bool { get { d.bool(forKey: "memory_preload") } set { d.set(newValue, forKey: "memory_preload") } }
    var hudTimeoutMs: Int {
        get { max(1000, min(10000, d.object(forKey: "hud_timeout_ms") as? Int ?? 3500)) }
        set { d.set(max(1000, min(10000, newValue)), forKey: "hud_timeout_ms") }
    }
    var globalSpeed: Float {
        get { SpeedScale.normalize(d.object(forKey: "global_speed") as? Float ?? 1) }
        set { d.set(SpeedScale.normalize(newValue), forKey: "global_speed") }
    }
    func sourcePreset(_ id: String) -> Int { max(0, min(2, d.object(forKey: "preset." + id) as? Int ?? 0)) }
    func setSourcePreset(_ id: String, _ p: Int) { d.set(p, forKey: "preset." + id) }
    var theme: String { get { d.string(forKey: "theme") ?? "system" } set { d.set(newValue, forKey: "theme") } }
    func gestureAction(_ zone: Int, _ kind: String) -> String {
        GestureBindings.normalize(zone, kind, d.string(forKey: "gesture9.\(zone / 3).\(zone % 3).\(kind)") ?? GestureBindings.defaultAction(zone, kind))
    }
    func setGestureAction(_ zone: Int, _ kind: String, _ action: String) {
        d.set(GestureBindings.normalize(zone, kind, action), forKey: "gesture9.\(zone / 3).\(zone % 3).\(kind)")
    }
}

enum Fmt {
    static func time(_ ms: Int64) -> String {
        if ms < 0 { return "--:--" }
        let s = ms / 1000
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%02d:%02d", s / 60, s % 60)
    }
    static func size(_ bytes: Int64) -> String {
        if bytes <= 0 { return "流式媒体" }
        return bytes >= 1_073_741_824 ? String(format: "%.2f GB", Double(bytes) / 1_073_741_824) : String(format: "%.1f MB", Double(bytes) / 1_048_576)
    }
    static func bytes(_ n: Int64) -> String {
        n >= 1_048_576 ? String(format: "%.1f MB", Double(n) / 1_048_576) : n >= 1024 ? String(format: "%.0f KB", Double(n) / 1024) : "\(max(0, n)) B"
    }
    static func rate(_ bytesPerSec: Double) -> String {
        bytesPerSec >= 1_048_576 ? String(format: "%.1f MB/s", bytesPerSec / 1_048_576) : String(format: "%.1f KB/s", bytesPerSec / 1024)
    }
}
