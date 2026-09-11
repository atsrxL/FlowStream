package dev.flowstream.player.net;

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import dev.flowstream.core.*;
import dev.flowstream.player.data.Server;
import okhttp3.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.text.Collator;
import java.util.*;

public final class Browsers {
    private Browsers() { }
    public static List<RemoteItem> list(Server s, String folder) throws IOException {
        String path = MediaPaths.normalize(folder);
        List<RemoteItem> found = s.protocol.equals("SMB") ? smb(s, path) : dav(s, path);
        found.removeIf(item -> !item.directory && !MediaPaths.video(item.path));
        Collator collator = Collator.getInstance(Locale.getDefault());
        found.sort((a,b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : collator.compare(a.name, b.name));
        return found;
    }
    private static List<RemoteItem> smb(Server s, String folder) throws IOException {
        try (SmbConnection c = new SmbConnection(s)) {
            List<RemoteItem> found = new ArrayList<>();
            // SMBJ list() materializes a directory. The UI bound below prevents huge playback queues.
            List<FileIdBothDirectoryInformation> entries = c.share.list(s.smbPath(folder));
            if (entries.size() > DavParser.MAX_ITEMS + 2) throw new IOException("单个目录超过 10000 项，请拆分目录");
            for (FileIdBothDirectoryInformation f : entries) {
                String name = f.getFileName(); if (name.equals(".") || name.equals("..")) continue;
                try { found.add(new RemoteItem(MediaPaths.join(folder, name), name, (f.getFileAttributes() & 0x10L) != 0, f.getEndOfFile())); }
                catch (IllegalArgumentException ignored) { }
            }
            return found;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("SMB 目录读取失败，请检查权限或连接", e); }
    }
    private static List<RemoteItem> dav(Server s, String folder) throws IOException {
        DavUrls urls = new DavUrls(s.url, s.allowHttp); OkHttpClient client = HttpNetwork.client(s);
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:displayname/><d:getcontentlength/></d:prop></d:propfind>";
        Request request = new Request.Builder().url(urls.uri(folder, true).toString()).header("Depth", "1")
                .method("PROPFIND", RequestBody.create(MediaType.get("application/xml; charset=utf-8"), body)).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 207) throw new IOException("WebDAV PROPFIND 返回 HTTP " + response.code() + "，请检查地址、认证和权限");
            ResponseBody rb = response.body(); if (rb == null) throw new IOException("空 WebDAV 响应");
            if (rb.contentLength() > DavParser.MAX_XML_BYTES) throw new IOException("目录响应过大");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int n;
            try (InputStream in = rb.byteStream()) {
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > DavParser.MAX_XML_BYTES) throw new IOException("目录响应过大");
                    bytes.write(buffer, 0, n);
                }
            }
            String xml = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            if (xml.startsWith("\uFEFF")) xml = xml.substring(1);
            return DavParser.parse(xml, urls, folder);
        } finally { HttpNetwork.close(client); }
    }
}
