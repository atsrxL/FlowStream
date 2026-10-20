import Foundation
import AMSMB2

protocol RemoteSource: AnyObject {
    func list(_ folder: String) async throws -> [RemoteItem]
    func size(_ path: String) async throws -> Int64
    func read(_ path: String, range: Range<Int64>) async throws -> Data
    func delete(_ path: String) async throws
    func close()
}

enum Sources {
    static func make(_ server: Server) throws -> RemoteSource {
        server.isSMB ? try SMBSource(server) : try WebDAVSource(server)
    }
    /// Directory listing, sorted: folders first, then localized name order; non-video files hidden.
    static func browse(_ server: Server, folder: String) async throws -> [RemoteItem] {
        let source = try make(server)
        defer { source.close() }
        var found = try await source.list(try MediaPaths.normalize(folder))
        found.removeAll { !$0.directory && !MediaPaths.isVideo($0.path) }
        found.sort {
            if $0.directory != $1.directory { return $0.directory }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
        return found
    }
}

// MARK: - SMB

final class SMBSource: RemoteSource {
    private let server: Server
    private let manager: SMB2Manager
    init(_ server: Server) throws {
        self.server = server
        guard let url = URL(string: "smb://\(server.host):\(server.port)") else { throw FlowError("SMB 地址无效") }
        let credential = server.username.isEmpty ? nil : URLCredential(user: server.username, password: server.password, persistence: .forSession)
        guard let m = SMB2Manager(url: url, domain: server.domain, credential: credential) else { throw FlowError("SMB 地址无效") }
        m.timeout = 20
        manager = m
    }
    private func connect() async throws {
        try await manager.connectShare(name: server.share, encrypted: server.encryptSmb)
    }
    private func retrying<T>(_ op: () async throws -> T) async throws -> T {
        do { try await connect(); return try await op() }
        catch {
            if Task.isCancelled { throw error }
            try? await manager.disconnectShare()
            try await connect()
            return try await op()
        }
    }
    func list(_ folder: String) async throws -> [RemoteItem] {
        let entries = try await retrying { try await manager.contentsOfDirectory(atPath: server.smbPath(folder)) }
        if entries.count > 10_002 { throw FlowError("单个目录超过 10000 项，请拆分目录") }
        var out = [RemoteItem]()
        for e in entries {
            guard let name = e[.nameKey] as? String, name != ".", name != ".." else { continue }
            let isDir = (e[.isDirectoryKey] as? Bool) ?? false
            let size = (e[.fileSizeKey] as? NSNumber)?.int64Value ?? 0
            if let p = try? MediaPaths.join(folder, name) { out.append(RemoteItem(path: p, name: name, directory: isDir, size: size)) }
        }
        return out
    }
    func size(_ path: String) async throws -> Int64 {
        let a = try await retrying { try await manager.attributesOfItem(atPath: server.smbPath(path)) }
        return (a[.fileSizeKey] as? NSNumber)?.int64Value ?? 0
    }
    func read(_ path: String, range: Range<Int64>) async throws -> Data {
        try await retrying { try await manager.contents(atPath: server.smbPath(path), range: range, progress: nil) }
    }
    func delete(_ path: String) async throws {
        guard MediaPaths.isVideo(path) else { throw FlowError("只能删除视频文件") }
        do { try await retrying { try await manager.removeFile(atPath: server.smbPath(path)) } }
        catch { throw FlowError("SMB 删除失败，请检查删除权限和文件占用") }
    }
    func close() { manager.disconnectShare(gracefully: false) { _ in } }
}

// MARK: - WebDAV

struct DavUrls {
    let root: URL
    init(_ string: String, allowHttp: Bool) throws {
        guard var c = URLComponents(string: string.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = c.scheme?.lowercased(), let host = c.host, !host.isEmpty else { throw FlowError("WebDAV 地址格式错误") }
        guard scheme == "https" || (scheme == "http" && allowHttp) else { throw FlowError("请用 HTTPS；HTTP 必须单独确认允许") }
        guard c.user == nil, c.password == nil, c.query == nil, c.fragment == nil else { throw FlowError("WebDAV 地址不能含账号、查询参数或片段") }
        var p = c.percentEncodedPath
        if p.isEmpty { p = "/" }
        if !p.hasSuffix("/") { p += "/" }
        _ = try MediaPaths.normalize(try Self.decode(p))
        c.scheme = scheme
        c.percentEncodedPath = p
        guard let u = c.url else { throw FlowError("WebDAV 地址格式错误") }
        root = u
    }
    func url(_ path: String, directory: Bool) throws -> URL {
        let p = try MediaPaths.normalize(path)
        var s = root.absoluteString
        if !p.isEmpty {
            s += p.split(separator: "/").map { Self.encode(String($0)) }.joined(separator: "/")
        }
        if directory && !s.hasSuffix("/") { s += "/" }
        guard let u = URL(string: s) else { throw FlowError("非法路径") }
        return u
    }
    func allows(_ target: URL) -> Bool {
        guard Self.sameOrigin(root, target), target.user == nil, target.fragment == nil else { return false }
        guard let path = try? Self.decode(URLComponents(url: target, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? "/"),
              (try? MediaPaths.normalize(path)) != nil,
              let rp = try? Self.decode(URLComponents(url: root, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? "/") else { return false }
        return path == String(rp.dropLast()) || path.hasPrefix(rp)
    }
    func relative(directory: URL, href: String) throws -> String {
        guard let u = URL(string: href.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "%20"), relativeTo: directory)?.absoluteURL,
              u.query == nil, allows(u) else { throw FlowError("拒绝越界 WebDAV 路径") }
        let p = try Self.decode(URLComponents(url: u, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? "/")
        let rp = try Self.decode(URLComponents(url: root, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? "/")
        if p == String(rp.dropLast()) { return "" }
        return try MediaPaths.normalize(String(p.dropFirst(rp.count)))
    }
    static func decode(_ raw: String) throws -> String {
        let lower = raw.lowercased()
        if lower.contains("%2f") || lower.contains("%5c") || raw.contains("\\") { throw FlowError("不接受编码后的路径分隔符") }
        guard let d = raw.removingPercentEncoding else { throw FlowError("路径编码错误") }
        return d
    }
    private static func encode(_ s: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/;+?#%")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
    static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        func port(_ u: URL) -> Int { u.port ?? (u.scheme?.lowercased() == "https" ? 443 : 80) }
        return a.scheme?.lowercased() == b.scheme?.lowercased() && a.host?.lowercased() == b.host?.lowercased() && port(a) == port(b)
    }
}

private final class DavXML: NSObject, XMLParserDelegate {
    struct Response { var href = ""; var collection = false; var name = ""; var size: Int64 = 0; var ok = false }
    var responses = [Response]()
    private var cur = Response()
    private var stack = [String]()
    private var text = ""
    private var psStatus = ""
    private var psCollection = false, psName = "", psSize: Int64 = 0
    private var inResponse = false
    func parser(_ p: XMLParser, didStartElement e: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        guard namespaceURI == "DAV:" else { return }
        stack.append(e); text = ""
        if e == "response" { cur = Response(); inResponse = true }
        if e == "propstat" { psStatus = ""; psCollection = false; psName = ""; psSize = 0 }
        if e == "collection" { psCollection = true }
    }
    func parser(_ p: XMLParser, foundCharacters s: String) { text += s }
    func parser(_ p: XMLParser, didEndElement e: String, namespaceURI: String?, qualifiedName: String?) {
        guard namespaceURI == "DAV:" else { return }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        switch e {
        case "href": if stack.count >= 2, stack[stack.count - 2] == "response" { cur.href = t }
        case "status": psStatus = t
        case "displayname": psName = t
        case "getcontentlength": psSize = Int64(t) ?? 0
        case "propstat":
            let parts = psStatus.split(separator: " ")
            if parts.count >= 2, parts[1].hasPrefix("2") {
                cur.ok = true
                cur.collection = cur.collection || psCollection
                if !psName.isEmpty { cur.name = psName }
                if psSize > 0 { cur.size = psSize }
            }
        case "response": responses.append(cur); inResponse = false
        default: break
        }
        _ = stack.popLast(); text = ""
    }
}

enum DavParser {
    static let maxXML = 8 * 1024 * 1024
    static func parse(_ data: Data, urls: DavUrls, directory: String) throws -> [RemoteItem] {
        guard data.count <= maxXML else { throw FlowError("目录响应过大") }
        guard let xml = String(data: data, encoding: .utf8) else { throw FlowError("目录响应必须使用 UTF-8") }
        let upper = xml.uppercased()
        if upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY") { throw FlowError("拒绝 XML DTD / 实体声明") }
        let handler = DavXML()
        let p = XMLParser(data: data)
        p.shouldProcessNamespaces = true
        p.shouldResolveExternalEntities = false
        p.delegate = handler
        guard p.parse() else { throw FlowError("WebDAV 目录解析失败") }
        if handler.responses.count > 10_001 { throw FlowError("单个目录超过 10000 项，请拆分目录") }
        let dir = try MediaPaths.normalize(directory)
        let current = try urls.url(dir, directory: true)
        var found = [String: RemoteItem](), order = [String]()
        for r in handler.responses where !r.href.isEmpty && r.ok {
            guard let path = try? urls.relative(directory: current, href: r.href) else { continue }
            if path == dir || MediaPaths.parent(path) != dir { continue }
            if found[path] == nil { order.append(path) }
            found[path] = RemoteItem(path: path, name: r.name, directory: r.collection, size: r.size)
        }
        return order.compactMap { found[$0] }
    }
}

private final class DavRedirectGuard: NSObject, URLSessionTaskDelegate {
    let urls: DavUrls
    init(_ u: DavUrls) { urls = u }
    func urlSession(_ s: URLSession, task: URLSessionTask, willPerformHTTPRedirection r: HTTPURLResponse, newRequest req: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        if let u = req.url, urls.allows(u) { completionHandler(req) } else { completionHandler(nil) }
    }
}

final class WebDAVSource: RemoteSource {
    private let urls: DavUrls
    private let session: URLSession
    private let auth: String?
    private let guardian: DavRedirectGuard
    init(_ server: Server) throws {
        urls = try DavUrls(server.url, allowHttp: server.allowHttp)
        guardian = DavRedirectGuard(urls)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 30
        cfg.httpShouldSetCookies = false
        session = URLSession(configuration: cfg, delegate: guardian, delegateQueue: nil)
        auth = server.username.isEmpty && server.password.isEmpty ? nil
            : "Basic " + Data("\(server.username):\(server.password)".utf8).base64EncodedString()
    }
    private func request(_ url: URL, method: String) -> URLRequest {
        var r = URLRequest(url: url)
        r.httpMethod = method
        if let a = auth { r.setValue(a, forHTTPHeaderField: "Authorization") }
        return r
    }
    func list(_ folder: String) async throws -> [RemoteItem] {
        var r = request(try urls.url(folder, directory: true), method: "PROPFIND")
        r.setValue("1", forHTTPHeaderField: "Depth")
        r.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        r.httpBody = Data("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:displayname/><d:getcontentlength/></d:prop></d:propfind>".utf8)
        let (data, resp) = try await session.data(for: r)
        guard let h = resp as? HTTPURLResponse else { throw FlowError("WebDAV 响应无效") }
        guard h.statusCode == 207 else { throw FlowError("WebDAV PROPFIND 返回 HTTP \(h.statusCode)，请检查地址、认证和权限") }
        return try DavParser.parse(data, urls: urls, directory: folder)
    }
    func size(_ path: String) async throws -> Int64 {
        var r = request(try urls.url(path, directory: false), method: "HEAD")
        r.timeoutInterval = 20
        let (_, resp) = try await session.data(for: r)
        guard let h = resp as? HTTPURLResponse, h.statusCode == 200 else { throw FlowError("无法获取文件大小") }
        return h.expectedContentLength > 0 ? h.expectedContentLength : 0
    }
    func read(_ path: String, range: Range<Int64>) async throws -> Data {
        var r = request(try urls.url(path, directory: false), method: "GET")
        r.setValue("bytes=\(range.lowerBound)-\(range.upperBound - 1)", forHTTPHeaderField: "Range")
        let (data, resp) = try await session.data(for: r)
        guard let h = resp as? HTTPURLResponse else { throw FlowError("WebDAV 响应无效") }
        if h.statusCode == 206 { return data }
        if h.statusCode == 200 && range.lowerBound == 0 && Int64(data.count) <= range.count { return data }
        if h.statusCode == 416 { return Data() }
        throw FlowError("WebDAV 服务器不支持 Range 请求（HTTP \(h.statusCode)）")
    }
    func delete(_ path: String) async throws {
        let p = try MediaPaths.normalize(path)
        guard !p.isEmpty, MediaPaths.isVideo(p) else { throw FlowError("只能删除视频文件") }
        let listed = try await list(MediaPaths.parent(p))
        guard listed.contains(where: { $0.path == p && !$0.directory }) else { throw FlowError("文件不存在或已变为目录，请刷新后重试") }
        let (_, resp) = try await session.data(for: request(try urls.url(p, directory: false), method: "DELETE"))
        guard let h = resp as? HTTPURLResponse, h.statusCode == 200 || h.statusCode == 204 else {
            throw FlowError("删除未确认完成：HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)，请检查权限或刷新目录确认")
        }
    }
    func close() { session.invalidateAndCancel() }
}
