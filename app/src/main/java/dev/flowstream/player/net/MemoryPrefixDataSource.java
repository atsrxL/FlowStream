package dev.flowstream.player.net;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Reads RAM bytes first, then opens upstream at precisely the first missing byte. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class MemoryPrefixDataSource implements DataSource {
    private final DataSource upstream;
    private final MemoryPrefix cache;
    private DataSpec spec;
    private long position,remaining;
    private boolean opened,networkOpened;
    MemoryPrefixDataSource(DataSource upstream,MemoryPrefix cache){this.upstream=upstream;this.cache=cache;}
    @Override public void addTransferListener(TransferListener listener){upstream.addTransferListener(listener);}
    @Override public long open(DataSpec request)throws IOException{
        spec=request;position=request.position;
        long complete=cache.length(request.uri.toString());
        remaining=request.length;
        if(complete>=0){if(position>complete)throw new IOException("读取位置超出文件末尾");remaining=remaining==C.LENGTH_UNSET?complete-position:Math.min(remaining,complete-position);}
        opened=true;return remaining;
    }
    @Override public int read(byte[] buffer,int offset,int length)throws IOException{
        if(length==0)return 0;
        if(!opened)throw new IOException("数据源尚未打开");
        if(remaining==0)return C.RESULT_END_OF_INPUT;
        int wanted=remaining==C.LENGTH_UNSET?length:(int)Math.min(length,remaining);
        int n=networkOpened?0:cache.read(spec.uri.toString(),position,buffer,offset,wanted);
        if(n>0){position+=n;if(remaining!=C.LENGTH_UNSET)remaining-=n;return n;}
        if(!networkOpened){
            if(cache.length(spec.uri.toString())==position){remaining=0;return C.RESULT_END_OF_INPUT;}
            networkOpened=true;
            long available=upstream.open(spec.subrange(position-spec.position));
            if(available!=C.LENGTH_UNSET)remaining=remaining==C.LENGTH_UNSET?available:Math.min(remaining,available);
            if(remaining==0)return C.RESULT_END_OF_INPUT;
            wanted=remaining==C.LENGTH_UNSET?length:(int)Math.min(length,remaining);
        }
        n=upstream.read(buffer,offset,wanted);
        if(n==C.RESULT_END_OF_INPUT){remaining=0;return n;}
        position+=n;if(remaining!=C.LENGTH_UNSET)remaining-=n;return n;
    }
    @Override public Uri getUri(){return networkOpened?upstream.getUri():spec==null?null:spec.uri;}
    @Override public Map<String,List<String>> getResponseHeaders(){return networkOpened?upstream.getResponseHeaders():java.util.Collections.emptyMap();}
    @Override public void close()throws IOException{try{if(networkOpened)upstream.close();}finally{networkOpened=false;spec=null;if(opened){opened=false;}}}
}
