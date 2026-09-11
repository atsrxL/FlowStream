package dev.flowstream.player.net;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import dev.flowstream.player.data.Server;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Must be opened and closed off the UI thread. Uses SMB2/3, never SMB1. */
public final class SmbConnection implements AutoCloseable {
    private SMBClient client;
    private Connection connection;
    private Session session;
    public DiskShare share;
    public SmbConnection(Server s) throws IOException {
        try {
            SmbConfig config = SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS)
                    .withSoTimeout(25, TimeUnit.SECONDS).withReadBufferSize(256 * 1024)
                    .withEncryptData(s.encryptSmb).build();
            client = new SMBClient(config);
            connection = client.connect(s.host, s.port);
            AuthenticationContext auth = s.username.isEmpty() ? AuthenticationContext.anonymous()
                    : new AuthenticationContext(s.username, s.password.toCharArray(), s.domain);
            session = connection.authenticate(auth);
            com.hierynomus.smbj.share.Share connected = session.connectShare(s.share);
            if (!(connected instanceof DiskShare)) { connected.close(); throw new IOException("该共享不是文件共享"); }
            share = (DiskShare) connected;
        } catch (Exception e) { close(); throw new IOException("SMB 连接失败：检查地址、共享名、认证及服务端加密设置", e); }
    }
    @Override public void close() {
        if (share != null) { try { share.close(); } catch (Exception ignored) { } share = null; }
        if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; }
        if (connection != null) { try { connection.close(); } catch (Exception ignored) { } connection = null; }
        if (client != null) { try { client.close(); } catch (Exception ignored) { } client = null; }
    }
}
