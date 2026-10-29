import SwiftUI

struct SettingsView: View {
    private let store = Store.shared
    @AppStorage("theme") private var theme = "system"
    @State private var preloadMb = Store.shared.preloadMb
    @State private var memory = Store.shared.memoryPreload
    @State private var hud = Double(Store.shared.hudTimeoutMs) / 1000
    @State private var servers = Store.shared.servers()
    @State private var active = Store.shared.activeServer
    @State private var editing: Server?
    @State private var toast: String?
    @State private var gestureRefresh = 0
    @State private var cacheBusy = false

    var body: some View {
        NavigationStack {
            Form {
                Section("外观") {
                    Picker("主题", selection: $theme) {
                        Text("跟随系统").tag("system"); Text("黑").tag("black"); Text("白").tag("white")
                    }
                }
                Section {
                    Button("清除文件缩略图缓存") { Thumbnails.shared.clear(); toast = "文件缩略图缓存已清除" }
                    Button("清除视频缓存") {
                        Task { await VideoCache.shared.clear(); toast = "视频缓存已清除" }
                    }
                    VStack(alignment: .leading) {
                        Text("下一条预读 · \(preloadMb) MB")
                        Slider(value: Binding(get: { Double(preloadMb) }, set: { preloadMb = Int($0); store.preloadMb = preloadMb }), in: 50...3072, step: 1)
                        Stepper("微调 50 MB", value: Binding(get: { preloadMb }, set: { preloadMb = max(50, min(3072, $0)); store.preloadMb = preloadMb }), in: 50...3072, step: 50)
                    }
                    Toggle("预读保存在内存", isOn: $memory).onChange(of: memory) { store.memoryPreload = $0 }
                    Text(memory ? String(format: "请求 %d MB · 当前可用预读预算 %.1f MB", preloadMb, Double(VideoCache.effectiveRamBudget(requestedMb: preloadMb)) / 1_048_576)
                                : "磁盘缓存 · 按设定大小预读；HLS / DASH 暂不支持。缩略图缓存上限 128 MB。")
                        .font(.footnote).foregroundStyle(.secondary)
                } header: { Text("缓存") }
                Section("播放") {
                    VStack(alignment: .leading) {
                        Text(String(format: "菜单自动隐藏 · %.1f 秒", hud))
                        Slider(value: Binding(get: { hud }, set: { hud = ($0 * 2).rounded() / 2; store.hudTimeoutMs = Int(hud * 1000) }), in: 1...10, step: 0.5)
                    }
                }
                Section {
                    NavigationLink("九宫格手势") { GestureSettings() }
                } footer: { Text("缩放时仅平移和双击适屏。") }
                Section("当前来源") {
                    if servers.isEmpty { Text("尚未添加服务器").foregroundStyle(.secondary) }
                    ForEach(servers) { s in
                        HStack {
                            Button {
                                store.activeServer = s.id; active = s.id
                                NotificationCenter.default.post(name: .storeChanged, object: nil)
                            } label: {
                                HStack {
                                    Image(systemName: active == s.id ? "largecircle.fill.circle" : "circle")
                                    VStack(alignment: .leading) { Text(s.name); Text(s.summary).font(.caption).foregroundStyle(.secondary) }
                                }
                            }.buttonStyle(.plain)
                            Spacer()
                            Button { editing = s } label: { Image(systemName: "pencil") }.buttonStyle(.borderless)
                        }
                        .swipeActions {
                            Button("删除", role: .destructive) { store.deleteServer(s.id); reloadServers() }
                        }
                    }
                    Button { editing = Server() } label: { Label("添加服务器", systemImage: "plus") }
                }
                Section {
                    Text("WebDAV 支持 Basic 认证及匿名访问。iOS 系统播放器原生支持 MP4 / MOV / M4V / 3GP；MKV、WebM、AVI 无法播放。")
                        .font(.footnote).foregroundStyle(.secondary)
                    Text("FlowStream iOS 0.9.1").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(item: $editing, onDismiss: reloadServers) { s in ServerForm(server: s) }
            .alert(toast ?? "", isPresented: Binding(get: { toast != nil }, set: { if !$0 { toast = nil } })) { Button("好") {} }
        }
        .onAppear(perform: reloadServers)
    }
    private func reloadServers() {
        servers = store.servers(); active = store.activeServer
    }
}

struct GestureSettings: View {
    @State private var refresh = 0
    var body: some View {
        Form {
            ForEach(0..<9, id: \.self) { zone in
                Section(GestureBindings.zoneNames[zone]) {
                    ForEach(GestureBindings.kinds, id: \.0) { kind, title in
                        Picker(title, selection: Binding(
                            get: { Store.shared.gestureAction(zone, kind) },
                            set: { Store.shared.setGestureAction(zone, kind, $0); refresh += 1 })) {
                            ForEach(GestureBindings.actions(kind), id: \.self) { Text(GestureBindings.label($0)).tag($0) }
                        }
                    }
                }
            }
        }
        .id(refresh)
        .navigationTitle("九宫格手势")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ServerForm: View {
    @Environment(\.dismiss) private var dismiss
    @State var server: Server
    @State private var portText = ""
    @State private var pathText = ""
    @State private var status = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("名称", text: $server.name)
                    Picker("协议", selection: $server.protocolName) { Text("SMB / Samba").tag("SMB"); Text("WebDAV").tag("WEBDAV") }
                }
                if server.isSMB {
                    Section("SMB") {
                        TextField("主机 IP / 域名", text: $server.host).textInputAutocapitalization(.never).autocorrectionDisabled()
                        TextField("端口", text: $portText).keyboardType(.numberPad)
                        LabeledContent("路径") {
                            TextField("/share", text: $pathText)
                                .multilineTextAlignment(.trailing)
                                .textInputAutocapitalization(.never).autocorrectionDisabled()
                                .accessibilityIdentifier("smb-path")
                        }
                        TextField("域 / 工作组，可留空", text: $server.domain).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Toggle("要求 SMB3 传输加密", isOn: $server.encryptSmb)
                    }
                } else {
                    Section("WebDAV") {
                        TextField("WebDAV 地址", text: $server.url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Toggle("允许明文 HTTP", isOn: $server.allowHttp)
                    }
                }
                Section("认证") {
                    TextField("用户名（匿名访问可留空）", text: $server.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("密码", text: $server.password)
                }
                if !status.isEmpty { Section { Text(status).font(.footnote).foregroundStyle(.secondary) } }
                Section {
                    Button("测试连接") { run(save: false) }.disabled(busy)
                }
            }
            .navigationTitle("服务器配置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存") { run(save: true) }.disabled(busy) }
            }
            .onAppear { portText = String(server.port); pathText = server.location }
        }
    }

    private func prepared() throws -> Server {
        var s = server
        if s.isSMB {
            guard let p = Int(portText) else { throw FlowError("端口应在 1–65535 之间") }
            s.port = p
            s.setLocation(pathText)
        }
        try s.validate()
        return s
    }
    private func run(save: Bool) {
        let s: Server
        do { s = try prepared() } catch { status = error.localizedDescription; return }
        busy = true; status = save ? "正在保存…" : "正在连接…"
        Task {
            defer { busy = false }
            do {
                if save { try Store.shared.saveServer(s); dismiss() }
                else { let n = try await Sources.browse(s, folder: "").count; status = "连接成功，项目数：\(n)" }
            } catch { status = error.localizedDescription }
        }
    }
}
