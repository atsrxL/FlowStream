package dev.flowstream.player.net;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.*;
import java.io.File;
import java.util.concurrent.*;

/** Bounded prefix prefetch; RAM mode never constructs or writes the disk cache. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class NextVideoCache {
    private static SimpleCache cache;
    static final MemoryPrefix MEMORY = new MemoryPrefix();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile CacheWriter writer;
    private volatile DataSource reader;
    private volatile boolean closed;
    private volatile long generation;
    private volatile String requested="";
    public static synchronized SimpleCache cache(Context c) {
        if(cache==null) cache=new SimpleCache(new File(c.getCacheDir(),"next-video"),new LeastRecentlyUsedCacheEvictor(50L*1024*1024),new StandaloneDatabaseProvider(c));
        return cache;
    }
    public static CacheDataSource.Factory factory(Context c,DataSource.Factory upstream) {
        return new CacheDataSource.Factory().setCache(cache(c)).setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
    public static DataSource.Factory factory(Context c,DataSource.Factory upstream,boolean memory) {
        return memory ? () -> new MemoryPrefixDataSource(upstream.createDataSource(),MEMORY) : factory(c,upstream);
    }
    public void preload(Context c,dev.flowstream.player.data.Server server,String path,int mb) {
        preload(c,server,path,mb,false);
    }
    public synchronized void preload(Context c,dev.flowstream.player.data.Server server,String path,int mb,boolean memory) {
        int bounded=Math.max(0,Math.min(50,mb));
        String key=server.id+":"+server.url+":"+path+":"+bounded+":"+memory;
        if(closed || key.equals(requested))return;
        requested=key;
        long ticket=++generation;
        CacheWriter old=writer;if(old!=null)old.cancel();
        // Interrupt an outstanding network read before the next queued request starts.
        DataSource previous=reader;if(previous!=null)try{previous.close();}catch(Exception ignored){}
        if(!memory || bounded==0)MEMORY.clear();
        if(bounded==0 || path.endsWith(".m3u8") || path.endsWith(".mpd"))return;
        Context app=c.getApplicationContext();
        worker.execute(()->{
            if(closed || ticket!=generation)return;
            try(SourceBundle source=new SourceBundle(server)) {
                DataSpec spec=new DataSpec.Builder().setUri(source.uri(path)).build();
                if(memory) {
                    DataSource input=source.factory.createDataSource();
                    try {
                        long token;
                        synchronized(NextVideoCache.this){
                            if(closed || ticket!=generation)return;
                            reader=input;
                            token=MEMORY.begin(spec.uri.toString(),bounded*1024L*1024);
                        }
                        long total=input.open(spec);
                        long loaded=0,limit=bounded*1024L*1024;
                        while(!closed && ticket==generation && loaded<limit) {
                            byte[] chunk=new byte[(int)Math.min(64*1024,limit-loaded)];
                            int count=0;boolean eof=false;
                            while(count<chunk.length && !closed && ticket==generation){
                                int n=input.read(chunk,count,chunk.length-count);
                                if(n==C.RESULT_END_OF_INPUT){eof=true;break;}
                                count+=n;
                                if(total!=C.LENGTH_UNSET && loaded+count>=total){eof=true;break;}
                            }
                            if(count>0 && !MEMORY.append(token,chunk,count))break;
                            loaded+=count;
                            if(eof){MEMORY.finish(token,loaded);break;}
                        }
                    } finally {try{input.close();}finally{if(reader==input)reader=null;}}
                } else {
                    CacheDataSource.Factory f=new CacheDataSource.Factory().setCache(cache(app)).setUpstreamDataSourceFactory(source.factory);
                    CacheWriter w=new CacheWriter(f.createDataSource(),spec.buildUpon().setLength(bounded*1024L*1024).build(),new byte[64*1024],null);
                    writer=w;
                    try{if(closed || ticket!=generation){w.cancel();return;}w.cache();}
                    finally{if(writer==w)writer=null;}
                }
            } catch(Exception ignored){/* Retain useful prefix; playback handles its own network errors. */}
        });
    }
    public synchronized void close(){closed=true;++generation;CacheWriter w=writer;if(w!=null)w.cancel();DataSource r=reader;if(r!=null)try{r.close();}catch(Exception ignored){}worker.shutdownNow();MEMORY.clear();}
}
