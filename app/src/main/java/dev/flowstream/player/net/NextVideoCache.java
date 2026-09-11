package dev.flowstream.player.net;

import android.content.Context;
import android.net.Uri;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.*;
import java.io.File;
import java.util.concurrent.*;

/** App-private bounded temporary cache. Playback reads cached ranges and streams misses. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class NextVideoCache {
    private static SimpleCache cache;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile CacheWriter writer;
    private volatile boolean closed;
    private volatile String requested="";
    public static synchronized SimpleCache cache(Context c) {
        if(cache==null) cache=new SimpleCache(new File(c.getCacheDir(),"next-video"),new LeastRecentlyUsedCacheEvictor(50L*1024*1024),new StandaloneDatabaseProvider(c));
        return cache;
    }
    public static CacheDataSource.Factory factory(Context c,DataSource.Factory upstream) {
        return new CacheDataSource.Factory().setCache(cache(c)).setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
    public synchronized void preload(Context c,dev.flowstream.player.data.Server server,String path,int mb) {
        String key=server.id+":"+path+":"+mb;
        if(closed || key.equals(requested))return;requested=key;
        CacheWriter old=writer;if(old!=null)old.cancel();
        if(mb<=0 || path.endsWith(".m3u8") || path.endsWith(".mpd"))return;
        worker.execute(()->{
            if(closed || !key.equals(requested))return;
            try(SourceBundle source=new SourceBundle(server)) {
                CacheDataSource.Factory f=new CacheDataSource.Factory().setCache(cache(c)).setUpstreamDataSourceFactory(source.factory);
                CacheWriter w=new CacheWriter(f.createDataSource(),new DataSpec.Builder().setUri(source.uri(path)).setLength(mb*1024L*1024).build(),new byte[64*1024],null);
                writer=w;if(closed || !key.equals(requested)){w.cancel();return;}
                try{w.cache();}catch(java.io.IOException ignored){/* Partial prefix remains useful; playback reports its own errors. */}
            } catch(Exception ignored){}
        });
    }
    public void close(){closed=true;CacheWriter w=writer;if(w!=null)w.cancel();worker.shutdownNow();}
}
