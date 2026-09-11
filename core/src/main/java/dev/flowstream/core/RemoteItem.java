package dev.flowstream.core;

public final class RemoteItem {
    public final String path, name;
    public final boolean directory;
    public final long size;
    public RemoteItem(String path, String name, boolean directory, long size) {
        this.path = MediaPaths.normalize(path);
        this.name = name == null || name.isEmpty() ? MediaPaths.name(path) : name;
        this.directory = directory; this.size = Math.max(0, size);
    }
}
