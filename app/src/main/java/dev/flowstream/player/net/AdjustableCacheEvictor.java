package dev.flowstream.player.net;

import androidx.media3.common.C;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheEvictor;
import androidx.media3.datasource.cache.CacheSpan;
import java.util.TreeSet;

/** Called under SimpleCache's monitor, including explicit budget changes. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class AdjustableCacheEvictor implements CacheEvictor {
    private final TreeSet<CacheSpan> oldest=new TreeSet<>((a,b)->{
        int result=Long.compare(a.lastTouchTimestamp,b.lastTouchTimestamp);return result!=0?result:a.compareTo(b);
    });
    private long budget=50L*CacheBudgets.MIB,bytes;
    AdjustableCacheEvictor(long initial){budget=Math.max(0,Math.min(CacheBudgets.MAX_DISK_BYTES,initial));}
    void setBudget(Cache cache,long requested){budget=Math.max(0,Math.min(CacheBudgets.MAX_DISK_BYTES,requested));evict(cache,0);}
    public boolean requiresCacheSpanTouches(){return true;}
    public void onCacheInitialized(){}
    public void onStartFile(Cache cache,String key,long position,long length){if(length!=C.LENGTH_UNSET)evict(cache,length);}
    public void onSpanAdded(Cache cache,CacheSpan span){if(oldest.add(span))bytes+=span.length;evict(cache,0);}
    public void onSpanRemoved(Cache cache,CacheSpan span){if(oldest.remove(span))bytes-=span.length;}
    public void onSpanTouched(Cache cache,CacheSpan old,CacheSpan updated){onSpanRemoved(cache,old);onSpanAdded(cache,updated);}
    private void evict(Cache cache,long incoming){
        while(bytes+incoming>budget&&!oldest.isEmpty()){
            CacheSpan span=oldest.first();
            cache.removeSpan(span);
            // If a concurrently missing file did not notify its listener, avoid a spin.
            if(oldest.remove(span))bytes-=span.length;
        }
    }
}
