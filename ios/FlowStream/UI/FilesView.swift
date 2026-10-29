import SwiftUI

struct ThumbView: View {
    let server: Server?
    let item: RemoteItem
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Color(.secondarySystemBackground)
            if let image = image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: item.directory ? "folder.fill" : "play.rectangle.fill")
                    .font(.system(size: 26)).foregroundStyle(item.directory ? Color.accentColor : Color.secondary)
            }
        }
        .clipped()
        .task(id: item.path) {
            guard !item.directory, let server = server else { return }
            image = await Thumbnails.shared.image(server: server, item: item)
        }
    }
}

@MainActor
final class BrowserModel: ObservableObject {
    @Published var entries: [RemoteItem] = []
    @Published var loading = false
    @Published var message = "还没有选择服务器\n到“设置”添加 SMB 或 WebDAV 来源"
    @Published var server: Server?
    @Published var folder = ""
    private var generation = 0

    func reload() {
        let store = Store.shared
        let active = store.activeServer
        generation += 1
        let token = generation
        guard !active.isEmpty, let s = store.server(active) else {
            server = nil; entries = []; loading = false
            message = "还没有选择服务器\n到“设置”添加 SMB 或 WebDAV 来源"
            return
        }
        if server?.id != s.id { folder = store.lastFolder(s.id) }
        let requested = folder
        loading = true; message = "正在读取…"
        Task {
            do {
                let list = try await Sources.browse(s, folder: requested)
                guard token == generation else { return }
                server = s; entries = list; loading = false
                message = "这个目录没有可显示的视频或子目录"
                store.setLastFolder(s.id, requested)
            } catch {
                guard token == generation else { return }
                server = s; entries = []; loading = false
                message = error.localizedDescription + "\n可点击刷新重试，或返回上一级"
            }
        }
    }
    func open(_ item: RemoteItem) {
        guard !loading, let server = server else { return }
        if item.directory { folder = item.path; reload(); return }
        PlayerLauncher.open(server: server, folder: folder, item: item, queue: entries)
    }
    func up() { if !folder.isEmpty { folder = MediaPaths.parent(folder); reload() } }
}

struct FilesView: View {
    @StateObject private var model = BrowserModel()
    @State private var grid = Store.shared.thumbnailMode
    @State private var random = Store.shared.randomMode
    @Environment(\.scenePhase) private var phase

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Text(model.folder.isEmpty ? "根目录" : "/" + model.folder)
                        .font(.footnote).foregroundStyle(.secondary).lineLimit(1).truncationMode(.head)
                    Spacer()
                    Toggle("随机", isOn: $random).toggleStyle(.switch).labelsHidden()
                        .onChange(of: random) { Store.shared.randomMode = $0 }
                    Text("随机").font(.footnote).foregroundStyle(.secondary)
                }.padding(.horizontal, 16).padding(.vertical, 6)
                ZStack {
                    if model.entries.isEmpty {
                        VStack { Spacer(); Text(model.message).multilineTextAlignment(.center).foregroundStyle(.secondary).padding(24); Spacer() }
                    } else if grid {
                        ScrollView {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 10)], spacing: 14) {
                                ForEach(model.entries) { item in
                                    Button { model.open(item) } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            ThumbView(server: model.server, item: item).aspectRatio(16 / 9, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 10))
                                            Text(item.name).font(.subheadline).lineLimit(2).multilineTextAlignment(.leading).frame(minHeight: 36, alignment: .top)
                                            Text(item.directory ? "文件夹" : Fmt.size(item.size)).font(.caption2).foregroundStyle(.secondary)
                                        }
                                    }.buttonStyle(.plain)
                                }
                            }.padding(.horizontal, 12)
                        }
                    } else {
                        List(model.entries) { item in
                            Button { model.open(item) } label: {
                                HStack(spacing: 12) {
                                    ThumbView(server: model.server, item: item).frame(width: 104, height: 64).clipShape(RoundedRectangle(cornerRadius: 10))
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(item.name).font(.body).lineLimit(2)
                                        Text(item.directory ? "文件夹" : Fmt.size(item.size)).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }.buttonStyle(.plain)
                        }.listStyle(.plain)
                    }
                    if model.loading { ProgressView().padding().background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12)) }
                }
            }
            .navigationTitle(model.server?.name ?? "文件")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    Button { model.up() } label: { Image(systemName: "chevron.up") }.disabled(model.folder.isEmpty)
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { grid.toggle(); Store.shared.thumbnailMode = grid } label: { Image(systemName: grid ? "list.bullet" : "square.grid.2x2") }
                    Button { model.reload() } label: { Image(systemName: "arrow.clockwise") }
                }
            }
        }
        .onAppear { model.reload(); random = Store.shared.randomMode }
        .onReceive(NotificationCenter.default.publisher(for: .storeChanged)) { _ in model.reload() }
    }
}

struct MarksView: View {
    @State private var marks = [MarkedItem]()
    @State private var busy = false
    @State private var alert: String?
    @State private var pendingDelete: MarkedItem?
    @State private var notice: String?

    var body: some View {
        NavigationStack {
            Group {
                if marks.isEmpty {
                    Text("还没有标记的视频\n播放时点击“标记”即可加入这里").multilineTextAlignment(.center).foregroundStyle(.secondary)
                } else {
                    List(marks) { mark in
                        Button { open(mark) } label: {
                            HStack(spacing: 12) {
                                ThumbView(server: Store.shared.server(mark.serverId), item: mark.item).frame(width: 104, height: 64).clipShape(RoundedRectangle(cornerRadius: 10))
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(mark.item.name).lineLimit(2)
                                    Text((Store.shared.server(mark.serverId)?.name ?? "来源不可用") + " · " + Fmt.size(mark.item.size)).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }.buttonStyle(.plain)
                        .swipeActions(allowsFullSwipe: false) {
                            Button("删除文件", role: .destructive) { pendingDelete = mark }.tint(.red)
                            Button("取消标记") { unmark(mark) }.tint(.orange)
                        }
                        .contextMenu {
                            Button { unmark(mark) } label: { Label("取消标记", systemImage: "bookmark.slash") }
                            Button(role: .destructive) { pendingDelete = mark } label: { Label("删除文件", systemImage: "trash") }
                        }
                    }.listStyle(.plain)
                }
            }
            .overlay { if busy { ProgressView().padding().background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
            .navigationTitle("标记")
            .navigationBarTitleDisplayMode(.inline)
            .alert("操作失败", isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } })) { Button("好") {} } message: { Text(alert ?? "") }
            .alert("删除这个视频？", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }), presenting: pendingDelete) { mark in
                Button("删除", role: .destructive) { delete(mark) }
                Button("取消", role: .cancel) {}
            } message: { mark in
                Text(mark.item.name + "\n将从服务器磁盘删除原文件，不能在本应用内撤销。")
            }
            .overlay(alignment: .bottom) {
                if let notice = notice {
                    Text(notice).font(.footnote).padding(.horizontal, 14).padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: Capsule()).padding(.bottom, 16)
                        .task { try? await Task.sleep(nanoseconds: 2_000_000_000); self.notice = nil }
                }
            }
        }
        .onAppear { marks = Store.shared.markedItems() }
    }

    private func unmark(_ mark: MarkedItem) {
        Store.shared.removeMarked(mark.serverId, mark.item.path)
        marks = Store.shared.markedItems()
    }

    /// Deletes the original file on the server, then removes the mark.
    private func delete(_ mark: MarkedItem) {
        guard !busy else { return }
        guard let server = Store.shared.server(mark.serverId) else { alert = "标记的来源不可用，请检查服务器设置"; return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let source = try Sources.make(server)
                defer { source.close() }
                try await source.delete(mark.item.path)
                Store.shared.removeMarked(mark.serverId, mark.item.path)
                marks = Store.shared.markedItems()
                notice = "已从服务器删除 " + mark.item.name
            } catch { alert = error.localizedDescription }
        }
    }

    private func open(_ mark: MarkedItem) {
        guard !busy else { return }
        guard let server = Store.shared.server(mark.serverId) else { alert = "标记的来源不可用，请检查服务器设置"; return }
        busy = true
        let dir = MediaPaths.parent(mark.item.path)
        Task {
            defer { busy = false }
            do {
                let found = try await Sources.browse(server, folder: dir)
                guard let item = found.first(where: { !$0.directory && $0.path == mark.item.path }) else { throw FlowError("标记的视频已不存在，可左滑或长按取消标记") }
                PlayerLauncher.open(server: server, folder: dir, item: item, queue: found)
            } catch { alert = error.localizedDescription }
        }
    }
}
