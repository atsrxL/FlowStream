package dev.flowstream.player;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import dev.flowstream.player.data.Store;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FlowApp extends Application {
    public final ExecutorService io = Executors.newFixedThreadPool(3);
    public final ExecutorService disk = Executors.newSingleThreadExecutor();
    public final Handler main = new Handler(Looper.getMainLooper());
    public Store store;
    public dev.flowstream.player.net.ThumbnailCache thumbnails;
    public final java.util.Map<String,dev.flowstream.core.RandomOrder> randomOrders=new java.util.HashMap<>();
    @Override public void onCreate() { super.onCreate(); store = new Store(this);thumbnails=new dev.flowstream.player.net.ThumbnailCache(this); }
}
