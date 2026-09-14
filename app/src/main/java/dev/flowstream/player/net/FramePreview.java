package dev.flowstream.player.net;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import dev.flowstream.player.data.Server;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Reuses one independent extractor per media and coalesces drag targets without abort starvation. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class FramePreview implements AutoCloseable {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService aborter=Executors.newSingleThreadExecutor();
    private Request pending;
    private boolean draining,closed;
    private long epoch;
    private String requestedKey="";
    private volatile Extraction active;
    private static final class Request {
        Server server;String path,key;long size,millis,epoch;boolean prepareOnly;Consumer<Bitmap> callback;
    }
    private static final class Extraction implements AutoCloseable {
        final String key;
        final SourceBundle bundle;
        final PreviewMediaDataSource input;
        MediaMetadataRetriever retriever;
        volatile boolean aborted;
        Extraction(Request request){key=request.key;bundle=new SourceBundle(request.server);input=new PreviewMediaDataSource(bundle.factory,bundle.uri(request.path),request.size);}
        void prepare() throws java.io.IOException {
            if(aborted)throw new java.io.IOException("Preview initialization cancelled");
            retriever=new MediaMetadataRetriever();
            if(aborted)throw new java.io.IOException("Preview initialization cancelled");
            retriever.setDataSource(input);
        }
        Bitmap frame(long millis){
            long at=Math.max(0,millis)*1000;
            if(android.os.Build.VERSION.SDK_INT>=27){
                try {
                    Bitmap scaled=retriever.getScaledFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180);
                    if(scaled!=null)return scaled;
                }catch(RuntimeException ignored){/* Vendor scaled decoder may be unsupported. */}
            }
            Bitmap full=retriever.getFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if(full==null)return null;
            float scale=Math.min(320f/full.getWidth(),180f/full.getHeight());
            int w=Math.max(1,Math.round(full.getWidth()*scale)),h=Math.max(1,Math.round(full.getHeight()*scale));
            Bitmap small=Bitmap.createScaledBitmap(full,w,h,true);if(small!=full)full.recycle();return small;
        }
        void abort(){aborted=true;input.close();bundle.close();}
        @Override public void close(){input.close();if(retriever!=null)try{retriever.release();}catch(Exception ignored){}bundle.close();}
    }
    /**
     * Active same-media decode completes; intermediate queued targets are coalesced.
     * Its callback retains its own timestamp supplied by the caller, even if a newer target exists.
     * Callbacks run on a background thread; caller owns returned Bitmap. cancel() suppresses callbacks.
     */
    public synchronized void request(Server server,String path,long size,long millis,Consumer<Bitmap> callback){
        if(closed)return;
        String key=key(server,path,size);
        if(!key.equals(requestedKey)){
            ++epoch;requestedKey=key;
            Extraction old=active;if(old!=null&&!old.key.equals(key)){old.aborted=true;aborter.execute(old::abort);}
        }
        Request r=new Request();r.server=server;r.path=path;r.key=key;r.size=size;r.millis=millis;r.callback=callback;r.epoch=epoch;
        pending=r;
        if(!draining){draining=true;worker.execute(this::drain);}
    }
    private String key(Server server,String path,long size){return server.id+"|"+server.protocol+"|"+server.url+"|"+server.host+"|"+server.share+"|"+server.rootPath+"|"+path+"|"+size;}
    /** Optional background initialization after playback is ready; never replaces a queued drag. */
    public synchronized void prepare(Server server,String path,long size){
        if(closed||draining||pending!=null)return;
        String media=key(server,path,size);if(active!=null&&active.key.equals(media)&&!active.aborted)return;
        request(server,path,size,0,b->{if(b!=null)b.recycle();});
        if(pending!=null)pending.prepareOnly=true;
    }
    /** Media change invalidates decoder, range streams, pending requests and callbacks. */
    public synchronized void reset(){
        if(closed)return;
        ++epoch;pending=null;requestedKey="";
        Extraction old=active;if(old!=null){old.aborted=true;aborter.execute(old::abort);worker.execute(()->{if(active==old){active=null;old.close();}});}
    }
    private void drain(){
        while(true){
            Request r;
            synchronized(this){if(closed||pending==null){draining=false;return;}r=pending;pending=null;}
            Bitmap bitmap=null;
            try {
                if(!r.path.endsWith(".m3u8")&&!r.path.endsWith(".mpd")){
                    Extraction current;
                    boolean initialize;
                    synchronized(this){
                        if(closed||r.epoch!=epoch)continue;
                        current=active;
                        initialize=current==null||!current.key.equals(r.key)||current.aborted;
                        if(initialize)active=null;
                    }
                    if(initialize){
                        // Native release / transport construction must not block UI cancellation.
                        if(current!=null)current.close();
                        Extraction candidate=new Extraction(r);
                        boolean accepted;
                        synchronized(this){
                            accepted=!closed&&r.epoch==epoch;
                            // Publishing and the cancellation check are atomic. reset/close can
                            // now always see (and abort) this extraction before it starts I/O.
                            if(accepted)active=candidate;
                        }
                        if(!accepted){candidate.close();continue;}
                        current=candidate;
                        current.prepare();
                    }
                    synchronized(this){if(closed||r.epoch!=epoch||current.aborted)continue;}
                    if(!r.prepareOnly)bitmap=current.frame(r.millis);
                    // Some codec implementations fail scaled extraction but support full keyframes.
                    if(!r.prepareOnly && bitmap==null && r.millis<0)bitmap=current.frame(0);
                }
            }catch(Exception error){
                android.util.Log.w("FlowPreview","Frame extraction failed",error);
                Extraction failed=active;active=null;if(failed!=null)failed.close();
            }
            boolean deliver;synchronized(this){deliver=!closed&&r.epoch==epoch;}
            if(deliver){try{r.callback.accept(bitmap);}catch(RuntimeException error){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();android.util.Log.w("FlowPreview","Preview callback failed",error);}}
            else if(bitmap!=null)bitmap.recycle();
        }
    }
    /** Cancel a scrub without discarding initialized decoder metadata and cached ranges. */
    public synchronized void cancel(){++epoch;pending=null;}
    @Override public synchronized void close(){
        if(closed)return;closed=true;++epoch;pending=null;
        Extraction old=active;if(old!=null){old.aborted=true;aborter.execute(old::abort);}
        worker.execute(()->{Extraction last=active;active=null;if(last!=null)last.close();});
        worker.shutdown();aborter.shutdown();
    }
}
