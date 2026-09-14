package dev.flowstream.player.net;

/** Large configured capacities are verified arithmetically, without allocating gigabytes. */
public final class CacheBudgetChecks {
    public static void run(){
        long mib=1024L*1024;
        check(CacheBudgets.requestedBytes(3072)==3L*1024*1024*1024,"3 GiB uses long byte arithmetic");
        check(CacheBudgets.requestedBytes(Integer.MAX_VALUE)==3L*1024*1024*1024,"disk maximum clamp");
        check(CacheBudgets.requestedBytes(-1)==0,"negative legacy capacity clamp");
        check(CacheBudgets.ramBytes(3072,256*mib,128*mib,0)==32*mib,"3 GiB selection adapts to 256 MiB heap");
        check(CacheBudgets.ramBytes(3072,256*mib,252*mib,0)==mib,"low heap headroom cap");
        check(CacheBudgets.ramBytes(3072,256*mib,256*mib,0)==0,"no allocation without headroom");
        check(CacheBudgets.ramBytes(50,8L*1024*mib,1024*mib,0)==50*mib,"small request respected on large heap");
        check(CacheBudgets.ramBytes(3072,8L*1024*mib,1024*mib,0)==512*mib,"absolute RAM ceiling");
        MemoryPrefix cache=new MemoryPrefix();cache.setBudget(8);long token=cache.begin("test",8);
        check(cache.append(token,new byte[8],8)&&cache.allocatedBytes()==8,"configured memory budget used");
        cache.setBudget(4);check(cache.allocatedBytes()==0&&!cache.append(token,new byte[1],1),"budget shrink invalidates oversized entry");
        token=cache.begin("test",4);cache.clear();check(!cache.append(token,new byte[1],1),"clear cancels outstanding prefix writes");
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
