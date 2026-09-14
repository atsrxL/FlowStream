package dev.flowstream.player.net;

import android.media.MediaDataSource;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Seekable preview transport, independent of playback. Keeps metadata and frame ranges hot. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PreviewMediaDataSource extends MediaDataSource {
    static final int BLOCK_BYTES=256*1024, MAX_BLOCKS=32, MAX_STREAMS=3;
    private final DataSource.Factory factory;
    private final Uri uri;
    private final LinkedHashMap<Long,byte[]> blocks=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<Long,Integer> lengths=new LinkedHashMap<>();
    private final ArrayList<Stream> streams=new ArrayList<>();
    private final CopyOnWriteArrayList<DataSource> opened=new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile long length;
    private long clock;
    private static final class Stream { DataSource source; long position,lastUse; }
    PreviewMediaDataSource(DataSource.Factory factory,Uri uri,long size){this.factory=factory;this.uri=uri;length=size>0?size:-1;}
    private void checkOpen()throws IOException{if(closed)throw new IOException("Preview transport closed");}
    private Stream stream(long position)throws IOException{
        checkOpen();
        for(Stream s:streams)if(s.position==position){s.lastUse=++clock;return s;}
        if(streams.size()>=MAX_STREAMS){
            Stream oldest=streams.get(0);for(Stream s:streams)if(s.lastUse<oldest.lastUse)oldest=s;
            streams.remove(oldest);opened.remove(oldest.source);oldest.source.close();
        }
        Stream s=new Stream();s.source=factory.createDataSource();s.position=position;s.lastUse=++clock;
        opened.add(s.source);
        try {
            checkOpen();
            long available=s.source.open(new DataSpec.Builder().setUri(uri).setPosition(position).build());
            checkOpen();
            if(available!=C.LENGTH_UNSET)length=position+available;
            streams.add(s);return s;
        }catch(IOException|RuntimeException ex){opened.remove(s.source);try{s.source.close();}catch(Exception ignored){}throw ex;}
    }
    @Override public synchronized int readAt(long position,byte[] buffer,int offset,int count)throws IOException{
        checkOpen();if(count==0)return 0;if(position<0)throw new IOException("Negative preview offset");if(length>=0&&position>=length)return -1;
        long start=position/BLOCK_BYTES*BLOCK_BYTES;
        byte[] block=blocks.get(start);
        if(block==null){
            Stream s=stream(start);
            int wanted=length>=0?(int)Math.min(BLOCK_BYTES,length-start):BLOCK_BYTES;
            if(wanted<=0)return -1;
            block=new byte[wanted];int used=0,emptyReads=0;
            try {
                while(used<wanted){checkOpen();int n=s.source.read(block,used,wanted-used);if(n==C.RESULT_END_OF_INPUT){length=start+used;break;}if(n==0){if(++emptyReads>=3)throw new IOException("Preview upstream returned no data");continue;}emptyReads=0;used+=n;s.position+=n;}
            }catch(IOException|RuntimeException ex){streams.remove(s);opened.remove(s.source);try{s.source.close();}catch(Exception ignored){}throw ex;}
            checkOpen();
            if(used==0)return -1;
            // Evict before retaining the new block; maximum retained byte buffers is 8 MiB.
            if(blocks.size()>=MAX_BLOCKS){Long oldest=blocks.keySet().iterator().next();blocks.remove(oldest);lengths.remove(oldest);}
            blocks.put(start,block);lengths.put(start,used);
        }
        int at=(int)(position-start),available=lengths.get(start)-at;
        if(available<=0)return -1;
        int n=Math.min(count,available);System.arraycopy(block,at,buffer,offset,n);return n;
    }
    @Override public synchronized long getSize()throws IOException{checkOpen();if(length<0)stream(0);return length;}
    /** May be called outside the decoding thread to interrupt an outstanding range read. */
    @Override public void close(){closed=true;for(DataSource source:opened)try{source.close();}catch(Exception ignored){}opened.clear();}
    synchronized int cachedBlocks(){return blocks.size();}
}
