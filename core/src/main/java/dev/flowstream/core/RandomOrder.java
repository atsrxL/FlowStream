package dev.flowstream.core;

import java.util.*;

/** Process-local folder traversal. Visited items never re-enter the random candidate pool. */
public final class RandomOrder {
    private final Set<String> seen=new HashSet<>();
    private final List<String> history=new ArrayList<>();
    private final Random random;
    private int cursor=-1;
    private String reserved;
    public RandomOrder(){this(new Random());}
    public RandomOrder(Random random){this.random=random;}
    public void visit(String path){
        if(cursor>=0 && history.get(cursor).equals(path)){seen.add(path);return;}
        while(history.size()>cursor+1)history.remove(history.size()-1);
        history.add(path);cursor=history.size()-1;seen.add(path);reserved=null;
    }
    public String peekNext(List<String> available){
        if(reserved!=null && available.contains(reserved) && !seen.contains(reserved))return reserved;
        List<String> candidates=new ArrayList<>();for(String p:available)if(!seen.contains(p))candidates.add(p);
        reserved=candidates.isEmpty()?null:candidates.get(random.nextInt(candidates.size()));return reserved;
    }
    public String next(List<String> available){String p=peekNext(available);if(p!=null)visit(p);return p;}
    public String previous(List<String> available){
        for(int i=cursor-1;i>=0;i--)if(available.contains(history.get(i))){cursor=i;reserved=null;return history.get(i);}
        return null;
    }
}
