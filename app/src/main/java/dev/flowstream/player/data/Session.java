package dev.flowstream.player.data;

import dev.flowstream.core.RemoteItem;
import java.util.ArrayList;
import java.util.List;

public final class Session {
    public String id, serverId, serverName, folder;
    public List<RemoteItem> queue = new ArrayList<>();
    public int index;
    public long position, duration, updated;
    public float speed = 1f;
    public RemoteItem current() { return queue.get(index); }
}
