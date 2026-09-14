package dev.flowstream.player.data;

import dev.flowstream.core.RemoteItem;

/** A user-created persistent mark; it contains no credentials or playback history. */
public final class MarkedItem {
    public final String serverId;
    public final RemoteItem item;
    public final long markedAt;
    public MarkedItem(String serverId,RemoteItem item,long markedAt){this.serverId=serverId;this.item=item;this.markedAt=markedAt;}
}
