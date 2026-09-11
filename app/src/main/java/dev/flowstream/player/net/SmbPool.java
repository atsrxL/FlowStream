package dev.flowstream.player.net;

import dev.flowstream.player.data.Server;
import java.io.IOException;

/** One transport per player, reused across seek/reopen operations. File handles remain per DataSource. */
public final class SmbPool implements AutoCloseable {
    private final Server server;
    private SmbConnection connection;
    private boolean closed;
    public SmbPool(Server server) { this.server = server; }
    public synchronized SmbConnection acquire() throws IOException {
        if (closed) throw new IOException("播放连接已经关闭");
        if (connection == null) connection = new SmbConnection(server);
        return connection;
    }
    public synchronized void invalidate(SmbConnection failed) {
        if (failed == connection) { connection.close(); connection = null; }
    }
    @Override public synchronized void close() {
        closed = true;
        if (connection != null) { connection.close(); connection = null; }
    }
}
