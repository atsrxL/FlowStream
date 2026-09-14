package dev.flowstream.player.net;

/** Byte arithmetic stays long, including the 3 GiB disk ceiling. */
final class CacheBudgets {
    static final long MIB=1024L*1024, MAX_DISK_BYTES=3072L*MIB;
    static long requestedBytes(int mb){return Math.max(0,Math.min(3072,mb))*MIB;}
    static long ramBytes(int mb,long maxHeap,long usedHeap,long ownCacheBytes){
        long requested=requestedBytes(mb);
        long headroom=Math.max(0,maxHeap-Math.max(0,usedHeap)+Math.max(0,ownCacheBytes));
        long safe=Math.min(512L*MIB,Math.min(Math.max(0,maxHeap)/8,headroom/4));
        return Math.min(requested,safe)/MIB*MIB;
    }
}
