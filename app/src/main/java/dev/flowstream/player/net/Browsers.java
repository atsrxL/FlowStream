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
    /** Deletes only an explicitly selected video file, never a directory or a redirected target. */
    public static void deleteVideo(Server server,RemoteItem item)throws IOException{
        String path=MediaPaths.normalize(item.path);
        if(item.directory||path.isEmpty()||!MediaPaths.video(path))throw new IOException("只能删除视频文件");
        if(server.protocol.equals("SMB")){
            try(SmbConnection connection=new SmbConnection(server)){connection.share.rm(server.smbPath(path));}
            catch(Exception e){throw new IOException("SMB 删除失败，请检查删除权限和文件占用",e);}return;
        }
        boolean file=false;for(RemoteItem listed:list(server,MediaPaths.parent(path)))if(listed.path.equals(path)&&!listed.directory){file=true;break;}
        if(!file)throw new IOException("文件不存在或已变为目录，请刷新后重试");
        DavUrls urls=new DavUrls(server.url,server.allowHttp);
        OkHttpClient client=HttpNetwork.client(server).newBuilder().followRedirects(false).retryOnConnectionFailure(false).build();
        try(Response response=client.newCall(new Request.Builder().url(urls.uri(path,false).toString()).delete().build()).execute()){
            if(response.code()!=200&&response.code()!=204)throw new IOException("删除未确认完成：HTTP "+response.code()+"，请检查权限或刷新目录确认");
        }finally{HttpNetwork.close(client);}
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
