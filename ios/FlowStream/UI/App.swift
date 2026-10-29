import SwiftUI
import UIKit

@main
struct FlowStreamApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @AppStorage("theme") private var theme = "system"
    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(theme == "black" ? .dark : theme == "white" ? .light : nil)
                .tint(Color(red: 0.03, green: 0.52, blue: 1))
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        OrientationLock.mask
    }
}

enum PlayerLauncher {
    static func present(session: Session, server: Server) {
        guard var top = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else { return }
        while let p = top.presentedViewController { top = p }
        top.present(PlayerViewController(session: session, server: server), animated: true)
    }

    /// Builds the folder queue and opens the player at `item`.
    static func open(server: Server, folder: String, item: RemoteItem, queue: [RemoteItem]) {
        let videos = queue.filter { !$0.directory }
        guard let index = videos.firstIndex(where: { $0.path == item.path }) else { return }
        present(session: Session(server: server, folder: folder, queue: videos, index: index), server: server)
    }
}

#if DEBUG
/// Simulator smoke test: FS_TEST_PROTO=dav|smb FS_TEST_OPEN=<file> pre-configures a server and opens a video.
enum DebugAutoOpen {
    private static var done = false
    /// Once per launch: RootView.onAppear fires again whenever the player is dismissed.
    static func run() {
        guard !done else { return }
        done = true
        let env = ProcessInfo.processInfo.environment
        guard let proto = env["FS_TEST_PROTO"] else { return }
        var s = Server()
        s.name = "测试"; s.username = "demo"; s.password = "secret"
        if proto == "dav" { s.protocolName = "WEBDAV"; s.url = "http://127.0.0.1:8765/dav/"; s.allowHttp = true }
        else { s.protocolName = "SMB"; s.host = "127.0.0.1"; s.port = 4445; s.share = "videos" }
        s.id = "debug-test"
        try? Store.shared.saveServer(s)
        Store.shared.activeServer = s.id
        guard let file = env["FS_TEST_OPEN"] else { return }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard let list = try? await Sources.browse(s, folder: ""), let item = list.first(where: { $0.name == file }) else { return }
            PlayerLauncher.open(server: s, folder: "", item: item, queue: list)
        }
    }
}
#endif

struct RootView: View {
    var body: some View {
        TabView {
            FilesView().tabItem { Label("文件", systemImage: "folder") }
            MarksView().tabItem { Label("标记", systemImage: "bookmark") }
            SettingsView().tabItem { Label("设置", systemImage: "gearshape") }
        }
        #if DEBUG
        .onAppear { DebugAutoOpen.run() }
        #endif
    }
}
