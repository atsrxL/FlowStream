package dev.flowstream.player.net;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.util.Arrays;

/** Network-independent instrumentation checks, callable from the app's custom runner. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class MemoryPrefixChecks {
    public static void run() throws Exception {
        byte[] content={0,1,2,3,4,5,6,7};
        MemoryPrefix cache=new MemoryPrefix();
        long token=cache.begin("https://test/video",1024);
        cache.append(token,new byte[]{0,1,2},3);
        Fake upstream=new Fake(content);
        MemoryPrefixDataSource source=new MemoryPrefixDataSource(upstream,cache);
        DataSpec spec=new DataSpec.Builder().setUri("https://test/video").build();
        check(source.open(spec)==C.LENGTH_UNSET,"partial prefix unknown length");
        byte[] result=new byte[8];int n=source.read(result,0,8);
        check(n==3 && upstream.opens==0,"prefix does not open network");
        n+=source.read(result,n,8-n);
        check(n==8 && upstream.start==3 && Arrays.equals(content,result),"seamless miss at prefix end");
        check(source.read(result,0,1)==C.RESULT_END_OF_INPUT,"unknown upstream EOF");source.close();
        token=cache.begin("https://test/video",1024);cache.append(token,content,8);cache.finish(token,8);
        source=new MemoryPrefixDataSource(new Fake(content),cache);
        check(source.open(spec.buildUpon().setPosition(2).setLength(3).build())==3,"bounded cached seek length");
        check(source.read(result,0,8)==3 && result[0]==2 && result[2]==4,"bounded cached seek bytes");
        check(source.read(result,0,1)==C.RESULT_END_OF_INPUT,"bounded EOF");source.close();
        source=new MemoryPrefixDataSource(new Fake(content),cache);
        check(source.open(spec)==8,"complete prefix length");source.read(result,0,8);
        check(source.read(result,0,1)==C.RESULT_END_OF_INPUT,"complete cache EOF");source.close();
        cache.begin("https://test/next",1024);
        check(cache.length("https://test/video")==8,"next prefetch preserves current prefix");
        cache.clear();check(!cache.append(token,content,8),"cancel prevents stale writes");
        token=cache.begin("other",4);check(!cache.append(token,content,8),"memory bound");
        check(cache.read("https://test/video",0,result,0,8)==0,"URI isolation");
    }
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);}
    private static final class Fake implements DataSource {
        final byte[] content;int position,opens;long start;Uri uri;
        Fake(byte[] content){this.content=content;}
        public long open(DataSpec s){opens++;start=s.position;position=(int)start;uri=s.uri;return C.LENGTH_UNSET;}
        public int read(byte[] b,int o,int l){if(position==content.length)return C.RESULT_END_OF_INPUT;int n=Math.min(l,content.length-position);System.arraycopy(content,position,b,o,n);position+=n;return n;}
        public Uri getUri(){return uri;}
        public void close(){}
        public void addTransferListener(TransferListener l){}
    }
}
