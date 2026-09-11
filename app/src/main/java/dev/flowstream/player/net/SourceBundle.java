package dev.flowstream.player.net;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import dev.flowstream.core.DavUrls;
import dev.flowstream.core.MediaPaths;
import dev.flowstream.player.data.Server;
import okhttp3.OkHttpClient;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SourceBundle implements AutoCloseable {
    public final DataSource.Factory factory;
    private final Server server;
    private SmbPool smb;
    private OkHttpClient http;
    private DavUrls urls;
    public SourceBundle(Server server) {
        this.server = server;
        if (server.protocol.equals("SMB")) {
            smb = new SmbPool(server); factory = () -> new SmbDataSource(server, smb);
        } else {
            urls = new DavUrls(server.url, server.allowHttp); http = HttpNetwork.client(server);
            factory = new OkHttpDataSource.Factory(http).setUserAgent("FlowStream/0.3.0");
        }
    }
    public Uri uri(String path) {
        String normalized = MediaPaths.normalize(path);
        return server.protocol.equals("SMB") ? new Uri.Builder().scheme("smbx").authority(server.id).path("/" + normalized).build()
                : Uri.parse(urls.uri(normalized, false).toString());
    }
    /** Call after player.release(), on a worker thread. */
    @Override public void close() { if (smb != null) smb.close(); if (http != null) HttpNetwork.close(http); }
}
