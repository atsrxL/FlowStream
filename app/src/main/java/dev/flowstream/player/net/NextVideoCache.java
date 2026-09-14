package dev.flowstream.player.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/** Prefix prefetch with adjustable disk capacity and a heap-aware RAM budget. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class NextVideoCache {
    private static final java.util.Map<String,DiskState> DISKS=new java.util.HashMap<>();
    private static final java.util.Map<String,MemoryPrefix> RAM=new java.util.HashMap<>();
    private static final class DiskState {
        final AdjustableCacheEvictor evictor;final SimpleCache cache;
        DiskState(Context context,int mb){evictor=new AdjustableCacheEvictor(CacheBudgets.requestedBytes(mb));cache=new SimpleCache(directory(context),evictor,new StandaloneDatabaseProvider(context));}
    }
    private static File directory(Context context){return new File(context.getFilesDir(),"video-cache");}
    private static String scope(Context context){return directory(context).getAbsolutePath();}
    private static synchronized MemoryPrefix memory(Context context){return RAM.computeIfAbsent(scope(context),key->new MemoryPrefix());}
    private static final Set<NextVideoCache> OWNERS=ConcurrentHashMap.newKeySet();
    private static final ExecutorService MAINTENANCE=Executors.newSingleThreadExecutor();
    private static final ExecutorService CANCELLATION=Executors.newCachedThreadPool();
    private static final ReentrantReadWriteLock DISK_ACCESS=new ReentrantReadWriteLock();
    private static volatile boolean clearing;
    private static int pendingClears;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile CacheWriter writer;
    private volatile DataSource reader;
    private volatile boolean closed;
    private volatile long generation;
    private String requested="";
    private String ownerScope="";
    private MemoryPrefix ownerMemory;
    public NextVideoCache(){OWNERS.add(this);}
    private static synchronized DiskState diskState(Context c){
        String key=scope(c);DiskState state=DISKS.get(key);
        if(state==null){
            int mb=50;Context app=c.getApplicationContext();
            if(app instanceof dev.flowstream.player.FlowApp && ((dev.flowstream.player.FlowApp)app).store!=null)mb=((dev.flowstream.player.FlowApp)app).store.preloadMb();
            state=new DiskState(c,mb);DISKS.put(key,state);
        }
        return state;
    }
    public static SimpleCache cache(Context c){return diskState(c).cache;}
    /** Effective RAM is at most 1/8 of heap and 1/4 of available headroom, capped at 512 MiB. */
    public static long effectiveRamBudgetBytes(Context context,int requestedMB){
        Runtime runtime=Runtime.getRuntime();long used=runtime.totalMemory()-runtime.freeMemory();
        return CacheBudgets.ramBytes(requestedMB,runtime.maxMemory(),used,memory(context).allocatedBytes());
    }
    public static CacheDataSource.Factory factory(Context c,DataSource.Factory upstream){
        return new CacheDataSource.Factory().setCache(cache(c)).setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
    public static DataSource.Factory factory(Context c,DataSource.Factory upstream,boolean memory){
        return memory?()->new MemoryPrefixDataSource(upstream.createDataSource(),memory(c)):factory(c,upstream);
    }
    public void preload(Context c,dev.flowstream.player.data.Server server,String path,int mb){preload(c,server,path,mb,false);}
    private synchronized DataSource cancelRequest(){++generation;requested="";CacheWriter w=writer;if(w!=null)w.cancel();return reader;}
    private static void closeQuietly(DataSource source){if(source!=null)try{source.close();}catch(Exception ignored){}}
    public synchronized void preload(Context c,dev.flowstream.player.data.Server server,String path,int mb,boolean memory){
        long requestedBytes=CacheBudgets.requestedBytes(mb);
        if(closed||clearing)return;
        Context app=c.getApplicationContext();MemoryPrefix ram=memory(app);ownerMemory=ram;ownerScope=scope(app);
        String key=server.id+":"+server.url+":"+path+":"+requestedBytes+":"+memory;
        if(key.equals(requested))return;
        DataSource previous=cancelRequest();if(previous!=null)CANCELLATION.execute(()->closeQuietly(previous));
        requested=key;long ticket=generation;
        if(!memory||requestedBytes==0)ram.clear();
        if(requestedBytes==0||path.endsWith(".m3u8")||path.endsWith(".mpd"))return;
        worker.execute(()->{
            if(closed||clearing||ticket!=generation)return;
            try(SourceBundle source=new SourceBundle(server)){
                DataSpec spec=new DataSpec.Builder().setUri(source.uri(path)).build();
                if(memory){
                    long limit=effectiveRamBudgetBytes(app,mb);if(limit==0)return;
                    DataSource input=source.factory.createDataSource();
                    try{
                        long token;
                        synchronized(NextVideoCache.this){
                            if(closed||clearing||ticket!=generation)return;
                            reader=input;ram.setBudget(limit);token=ram.begin(spec.uri.toString(),limit);
                        }
                        long total=input.open(spec),loaded=0;
                        while(!closed&&!clearing&&ticket==generation&&loaded<limit){
                            byte[] chunk=new byte[(int)Math.min(64*1024,limit-loaded)];int count=0,emptyReads=0;boolean eof=false;
                            while(count<chunk.length&&!closed&&!clearing&&ticket==generation){
                                int n=input.read(chunk,count,chunk.length-count);
                                if(n==C.RESULT_END_OF_INPUT){eof=true;break;}
                                if(n==0){if(++emptyReads>=3)throw new IOException("Cache upstream returned no data");continue;}
                                emptyReads=0;count+=n;
                                if(total!=C.LENGTH_UNSET&&loaded+count>=total){eof=true;break;}
                            }
                            synchronized(NextVideoCache.this){
                                if(closed||clearing||ticket!=generation)break;
                                if(count>0&&!ram.append(token,chunk,count))break;
                                loaded+=count;if(eof){ram.finish(token,loaded);break;}
                            }
                        }
                    }finally{closeQuietly(input);if(reader==input)reader=null;}
                }else{
                    DISK_ACCESS.readLock().lock();
                    try{
                        if(closed||clearing||ticket!=generation)return;
                        DiskState state=diskState(app);SimpleCache disk=state.cache;synchronized(disk){state.evictor.setBudget(disk,requestedBytes);}
                        CacheDataSource input=new CacheDataSource.Factory().setCache(disk).setUpstreamDataSourceFactory(source.factory).createDataSource();
                        CacheWriter w=new CacheWriter(input,spec.buildUpon().setLength(requestedBytes).build(),new byte[64*1024],null);
                        synchronized(NextVideoCache.this){if(closed||clearing||ticket!=generation){w.cancel();return;}reader=input;writer=w;}
                        try{w.cache();}finally{closeQuietly(input);if(reader==input)reader=null;if(writer==w)writer=null;}
                    }finally{DISK_ACCESS.readLock().unlock();}
                }
            }catch(Exception ignored){/* A successfully received prefix remains useful. */}
        });
    }
    /** Cancels all prefetchers and removes RAM/disk bytes asynchronously. Callback is on main. */
    public static void clear(Context context,Consumer<Exception> completion){
        final Context app=context.getApplicationContext();
        final ArrayList<DataSource> readers=new ArrayList<>();
        synchronized(NextVideoCache.class){pendingClears++;clearing=true;}
        for(NextVideoCache owner:OWNERS){synchronized(owner){if(!owner.ownerScope.equals(scope(app)))continue;DataSource source=owner.cancelRequest();if(source!=null)readers.add(source);}}
        MAINTENANCE.execute(()->{
            Exception error=null;
            try{
                for(DataSource source:readers)closeQuietly(source);
                DISK_ACCESS.writeLock().lock();
                try{
                    memory(app).clear();
                    DiskState state;
                    synchronized(NextVideoCache.class){state=DISKS.get(scope(app));}
                    SimpleCache disk=state==null?null:state.cache;
                    if(disk==null&&directory(app).exists())disk=cache(app);
                    if(disk!=null)for(String key:new ArrayList<>(disk.getKeys()))disk.removeResource(key);
                    // Old versions stored only disposable video data in this exact owned folder.
                    // Never instantiate another SimpleCache on that legacy directory.
                    deleteLegacyDirectory(new File(app.getCacheDir(),"next-video"));
                }
                finally{DISK_ACCESS.writeLock().unlock();}
            }catch(Exception failure){error=failure;}
            finally{synchronized(NextVideoCache.class){clearing=--pendingClears>0;}}
            final Exception result=error;if(completion!=null)new Handler(Looper.getMainLooper()).post(()->completion.accept(result));
        });
    }
    private static void deleteLegacyDirectory(File folder)throws IOException{
        if(!folder.exists())return;
        if(SimpleCache.isCacheFolderLocked(folder))throw new IOException("旧视频缓存正在使用");
        java.nio.file.Path root=folder.toPath();
        try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(root)){
            java.util.Iterator<java.nio.file.Path> entries=paths.sorted(java.util.Comparator.reverseOrder()).iterator();
            while(entries.hasNext())java.nio.file.Files.deleteIfExists(entries.next());
        }
    }
    public synchronized void close(){
        if(closed)return;closed=true;DataSource input=cancelRequest();if(input!=null)CANCELLATION.execute(()->closeQuietly(input));
        worker.shutdownNow();OWNERS.remove(this);if(ownerMemory!=null)ownerMemory.clear();
    }
}
