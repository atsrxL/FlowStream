package dev.flowstream.player.net;
import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import dev.flowstream.player.data.Server;
import java.io.IOException;
import java.util.concurrent.*;

/** A single background extractor with bounded output; cancelled requests never update the UI. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class FramePreview implements AutoCloseable {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile int generation;
 public void request(Server server,String path,long size,long millis,java.util.function.Consumer<Bitmap> callback){
  final int token=++generation;
  if(path.endsWith(".m3u8")||path.endsWith(".mpd")){callback.accept(null);return;}
  worker.execute(()->{if(token!=generation)return;Bitmap result=null;
   try(SourceBundle bundle=new SourceBundle(server)){
    MediaMetadataRetriever retriever=new MediaMetadataRetriever();
    MediaDataSource input=new MediaDataSource(){
     private DataSource source;private long next=-1;private long length=size;
     public synchronized int readAt(long position,byte[] b,int offset,int count)throws IOException{
      if(token!=generation)throw new IOException("Preview cancelled");
      if(source==null||next!=position){if(source!=null)source.close();source=bundle.factory.createDataSource();long remaining=source.open(new DataSpec.Builder().setUri(bundle.uri(path)).setPosition(position).build());if(length<=0&&remaining>=0)length=position+remaining;next=position;}
      int n=source.read(b,offset,count);if(n>0)next+=n;return n;
     }
     public long getSize(){return length>0?length:-1;}
     public void close()throws IOException{if(source!=null)source.close();}
    };
    try{retriever.setDataSource(input);if(android.os.Build.VERSION.SDK_INT>=27)result=retriever.getScaledFrameAtTime(millis*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,240,135);else{Bitmap full=retriever.getFrameAtTime(millis*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(full!=null){result=Bitmap.createScaledBitmap(full,240,135,true);if(result!=full)full.recycle();}}if(result==null && millis<0){Bitmap full=retriever.getFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(full!=null){int width=240,height=Math.max(1,Math.round(240f*full.getHeight()/full.getWidth()));result=Bitmap.createScaledBitmap(full,width,height,true);if(result!=full)full.recycle();}}}finally{retriever.release();input.close();}
   }catch(Exception error){android.util.Log.w("FlowPreview","Frame extraction failed",error);}
   if(token==generation)callback.accept(result);else if(result!=null)result.recycle();
  });
 }
 public void cancel(){generation++;}
 public void close(){cancel();worker.shutdownNow();}
}
