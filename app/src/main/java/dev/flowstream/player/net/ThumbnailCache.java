package dev.flowstream.player.net;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import dev.flowstream.core.RemoteItem;
import dev.flowstream.player.data.Server;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** JPEGs survive process restarts in cacheDir; Android and the settings action may evict them. */
public final class ThumbnailCache {
    private final File directory;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,List<Consumer<Bitmap>>> pending=new HashMap<>();
    private final FramePreview extractor=new FramePreview();
    private final Object diskLock=new Object();
    private volatile int epoch;
    public ThumbnailCache(Context c){directory=new File(c.getCacheDir(),"file-thumbnails");}
    private String key(Server s,RemoteItem item){
        try{String raw=s.id+"|"+s.protocol+"|"+s.url+"|"+s.host+"|"+s.share+"|"+s.rootPath+"|"+item.path+"|"+item.size;
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:digest)b.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return b.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public void load(Server server,RemoteItem item,Consumer<Bitmap> result){
        String key=key(server,item);final int token=epoch;
        synchronized(pending){if(pending.containsKey(key)){pending.get(key).add(result);return;}if(pending.size()>=64){main.post(()->result.accept(null));return;}pending.put(key,new ArrayList<>(Collections.singletonList(result)));}
        worker.execute(()->{
            if(token!=epoch)return;
            File file=new File(directory,key+".jpg");Bitmap bitmap=BitmapFactory.decodeFile(file.getPath());
            if(bitmap!=null)file.setLastModified(System.currentTimeMillis());
            if(bitmap==null){
                CountDownLatch latch=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Bitmap> image=new java.util.concurrent.atomic.AtomicReference<>();
                extractor.request(server,item.path,item.size,-1,b->{image.set(b);latch.countDown();});
                try{if(latch.await(25,TimeUnit.SECONDS))bitmap=image.get();else extractor.cancel();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                synchronized(diskLock){if(bitmap!=null && token==epoch){directory.mkdirs();File temporary=new File(directory,key+".tmp");try(FileOutputStream out=new FileOutputStream(temporary)){bitmap.compress(Bitmap.CompressFormat.JPEG,80,out);}catch(IOException ignored){}if(temporary.exists())temporary.renameTo(file);trim();}}
            }
            final Bitmap ready=bitmap;main.post(()->{if(token!=epoch)return;List<Consumer<Bitmap>> listeners;synchronized(pending){listeners=pending.remove(key);}if(listeners!=null)for(Consumer<Bitmap> listener:listeners)listener.accept(ready);});
        });
    }
    private void trim(){File[] files=directory.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long bytes=0;for(File f:files)bytes+=f.length();for(File f:files){if(bytes<=128L*1024*1024)break;long length=f.length();if(f.delete())bytes-=length;}}
    public void clear(Runnable done){
        synchronized(diskLock){epoch++;extractor.cancel();}
        synchronized(pending){pending.clear();}
        worker.execute(()->{synchronized(diskLock){File[] files=directory.listFiles();if(files!=null)for(File f:files)f.delete();}main.post(done);});
    }
}
