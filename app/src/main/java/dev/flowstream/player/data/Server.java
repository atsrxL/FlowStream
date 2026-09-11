package dev.flowstream.player.data;

import dev.flowstream.core.DavUrls;
import dev.flowstream.core.MediaPaths;
import java.util.UUID;

public final class Server {
    public String id = UUID.randomUUID().toString();
    public String name = "", protocol = "SMB", host = "", share = "", rootPath = "", url = "";
    public String username = "", password = "", domain = "";
    public int port = 445;
    public boolean allowHttp = false, encryptSmb = false;
    public void validate() {
        name = name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("请填写服务器名称");
        if (protocol.equals("SMB")) {
            host = host.trim(); share = share.trim(); rootPath = MediaPaths.normalize(rootPath.trim());
            if (host.isEmpty() || host.contains("/") || host.contains("\\") || host.contains("@") || host.contains(" "))
                throw new IllegalArgumentException("SMB 主机填写 IP / 主机名，不包含 smb:// 或共享名");
            if (share.isEmpty() || share.contains("/") || share.contains("\\") || share.equals(".."))
                throw new IllegalArgumentException("共享名应为单个名称，例如 videos");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应在 1–65535 之间");
        } else if (protocol.equals("WEBDAV")) {
            url = new DavUrls(url, allowHttp).root();
            if (username.contains(":")) throw new IllegalArgumentException("Basic 认证用户名不能含冒号");
        } else throw new IllegalArgumentException("未知来源协议");
    }
    public String description() {
        return protocol.equals("SMB") ? "SMB · " + host + "/" + share : "WebDAV · " + url;
    }
    public String smbPath(String relative) {
        String p = MediaPaths.normalize(relative);
        return (rootPath.isEmpty() ? p : (p.isEmpty() ? rootPath : rootPath + "/" + p)).replace('/', '\\');
    }
}
