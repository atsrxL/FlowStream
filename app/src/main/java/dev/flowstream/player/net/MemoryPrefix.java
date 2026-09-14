package dev.flowstream.player.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Two adjacent prefixes share an adaptive process-local allocation budget. */
final class MemoryPrefix {
    private long maxBytes=50L*1024*1024;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private long token;
    private static final class Entry {
        final ArrayList<byte[]> chunks=new ArrayList<>();
        final ArrayList<Integer> lengths=new ArrayList<>();
        long token,size,allocated,limit,complete=-1;
    }
    synchronized void setBudget(long bytes){
        maxBytes=Math.max(0,bytes);
        java.util.Iterator<Entry> it=entries.values().iterator();
        while(allocatedBytes()>maxBytes&&it.hasNext()){it.next();it.remove();}
    }
    synchronized long allocatedBytes(){long bytes=0;for(Entry e:entries.values())bytes+=e.allocated;return bytes;}
    synchronized long begin(String uri,long bytes){
        entries.remove(uri);
        while(entries.size()>=2)entries.remove(entries.keySet().iterator().next());
        Entry e=new Entry();e.token=++token;e.limit=Math.min(maxBytes,Math.max(0,bytes));entries.put(uri,e);return e.token;
    }
    synchronized void clear(){++token;entries.clear();}
    synchronized boolean append(long ticket,byte[] data,int count){
        Entry e=find(ticket);
        if(e==null || count<=0 || count>data.length || e.allocated+data.length>e.limit)return false;
        long allocated=0;for(Entry other:entries.values())allocated+=other.allocated;
        if(allocated+data.length>maxBytes){
            java.util.Iterator<Entry> it=entries.values().iterator();
            while(it.hasNext() && allocated+data.length>maxBytes){Entry other=it.next();if(other!=e){allocated-=other.allocated;it.remove();}}
        }
        if(allocated+data.length>maxBytes)return false;
        e.chunks.add(data);e.lengths.add(count);e.size+=count;e.allocated+=data.length;return true;
    }
    private Entry find(long ticket){for(Entry e:entries.values())if(e.token==ticket)return e;return null;}
    synchronized void finish(long ticket,long bytes){Entry e=find(ticket);if(e!=null && bytes==e.size)e.complete=bytes;}
    synchronized long length(String uri){Entry e=entries.get(uri);return e==null?-1:e.complete;}
    synchronized int read(String uri,long position,byte[] target,int offset,int length){
        Entry e=entries.get(uri);if(e==null || position>=e.size)return 0;
        long start=0;
        for(int i=0;i<e.chunks.size();i++){
            int count=e.lengths.get(i);
            if(position<start+count){int at=(int)(position-start),n=Math.min(length,count-at);System.arraycopy(e.chunks.get(i),at,target,offset,n);return n;}
            start+=count;
        }
        return 0;
    }
}
