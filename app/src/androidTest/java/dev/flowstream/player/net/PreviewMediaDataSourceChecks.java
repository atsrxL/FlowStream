package dev.flowstream.player.net;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic transport counters: reuse, bounded cache, independent ranges and EOF. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PreviewMediaDataSourceChecks {
    public static void run()throws Exception{
        int block=PreviewMediaDataSource.BLOCK_BYTES;
        AtomicInteger opens=new AtomicInteger(),reads=new AtomicInteger(),live=new AtomicInteger();
        DataSource.Factory factory=()->new Fake(40L*block+17,false,opens,reads,live);
        PreviewMediaDataSource input=new PreviewMediaDataSource(factory,Uri.parse("https://test/preview"),40L*block+17);
        byte[] b=new byte[20];
        check(input.readAt(12,b,0,b.length)==20&&b[0]==12,"first range bytes");int before=reads.get();
        input.readAt(18,b,0,b.length);check(reads.get()==before&&opens.get()==1,"same block is cache hit");
        input.readAt(30L*block,b,0,b.length);check(opens.get()==2,"distant metadata uses second stream");
        input.readAt(block,b,0,b.length);check(opens.get()==2,"forward decode reuses first stream");
        for(int i=2;i<39;i++)input.readAt((long)i*block,b,0,b.length);
        check(input.cachedBlocks()<=32&&live.get()<=3,"bounded block and stream counts");
        check(input.readAt(40L*block+15,b,0,b.length)==2,"short final block");
        check(input.readAt(40L*block+17,b,0,b.length)==-1,"known EOF");input.close();check(live.get()==0,"close releases streams");
        boolean rejected=false;try{input.readAt(0,b,0,1);}catch(IOException expected){rejected=true;}check(rejected,"cancelled transport rejects reads");
        input=new PreviewMediaDataSource(()->new Fake(17,true,new AtomicInteger(),new AtomicInteger(),new AtomicInteger()),Uri.parse("https://test/unknown"),-1);
        check(input.getSize()==-1,"unknown length permitted");check(input.readAt(0,b,0,20)==17,"unknown stream short EOF");check(input.getSize()==17&&input.readAt(17,b,0,1)==-1,"EOF records actual size");input.close();
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static final class Fake implements DataSource {
        final long length;final boolean unknown;final AtomicInteger opens,reads,live;long position;boolean open;Uri uri;
        Fake(long length,boolean unknown,AtomicInteger opens,AtomicInteger reads,AtomicInteger live){this.length=length;this.unknown=unknown;this.opens=opens;this.reads=reads;this.live=live;}
        public long open(DataSpec spec){opens.incrementAndGet();live.incrementAndGet();open=true;uri=spec.uri;position=spec.position;return unknown?C.LENGTH_UNSET:length-position;}
        public int read(byte[] b,int offset,int count){reads.incrementAndGet();if(position>=length)return C.RESULT_END_OF_INPUT;int n=(int)Math.min(count,length-position);for(int i=0;i<n;i++)b[offset+i]=(byte)(position+i);position+=n;return n;}
        public Uri getUri(){return uri;}
        public void close(){if(open){open=false;live.decrementAndGet();}}
        public void addTransferListener(TransferListener l){}
    }
}
