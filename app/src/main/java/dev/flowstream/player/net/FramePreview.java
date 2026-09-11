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
     private long length=size,blockStart=-1;private byte[] block=new byte[256*1024];private int blockSize;
     public synchronized int readAt(long position,byte[] b,int offset,int count)throws IOException{
      if(token!=generation)throw new IOException("Preview cancelled");
      if(count==0)return 0;if(position<0||(length>0&&position>=length))return -1;
      if(blockStart<0||position<blockStart||position>=blockStart+blockSize){
       blockStart=position;blockSize=0;DataSource source=bundle.factory.createDataSource();
       int wanted=(int)(length>0?Math.min(block.length,length-position):block.length);
       try{source.open(new DataSpec.Builder().setUri(bundle.uri(path)).setPosition(position).setLength(wanted).build());
        while(blockSize<wanted){if(token!=generation)throw new IOException("Preview cancelled");int n=source.read(block,blockSize,wanted-blockSize);if(n<0)break;if(n==0)break;blockSize+=n;}
       }finally{source.close();}
      }
      int available=Math.min(count,blockSize-(int)(position-blockStart));if(available<=0)return -1;System.arraycopy(block,(int)(position-blockStart),b,offset,available);return available;
     }
     public long getSize(){return length>0?length:-1;}
     public void close(){blockSize=0;blockStart=-1;}
    };
    try{retriever.setDataSource(input);if(android.os.Build.VERSION.SDK_INT>=27)result=retriever.getScaledFrameAtTime(Math.max(0,millis)*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,240,135);else{Bitmap full=retriever.getFrameAtTime(Math.max(0,millis)*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(full!=null){result=Bitmap.createScaledBitmap(full,240,135,true);if(result!=full)full.recycle();}}if(result==null && millis<0){Bitmap full=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(full!=null){int width=240,height=Math.max(1,Math.round(240f*full.getHeight()/full.getWidth()));result=Bitmap.createScaledBitmap(full,width,height,true);if(result!=full)full.recycle();}}}finally{retriever.release();input.close();}
   }catch(Exception error){android.util.Log.w("FlowPreview","Frame extraction failed",error);}
   if(token==generation)callback.accept(result);else if(result!=null)result.recycle();
  });
 }
 public void cancel(){generation++;}
 public void close(){cancel();worker.shutdownNow();}
}
