package dev.flowstream.core;

/** Single/double commit only after the next-tap window; triple commits on its valid UP. */
public final class MultiTapResolver {
    public interface Output { void singleTap(); default void singleTapAt(float x,float y){singleTap();} void doubleTap(); void tripleTap(); }
    private final long timeout; private final float slop; private final Output output;
    private int count; private boolean down; private long lastUp,downAt; private float x,y;
    public MultiTapResolver(long timeout,float slop,Output output){this.timeout=timeout;this.slop=slop;this.output=output;}
    private void commit(){int n=count;cancel();if(n==1)output.singleTapAt(x,y);else if(n==2)output.doubleTap();else if(n==3)output.tripleTap();}
    public void down(float px,float py,long now){
        if(count>0 && (now-lastUp>timeout || Math.hypot(px-x,py-y)>slop))commit();
        down=true;downAt=now;x=px;y=py;
    }
    public void up(boolean eligible,float px,float py,long now){
        if(!down)return;down=false;
        if(!eligible||now<downAt||now-downAt>=GestureMachine.HOLD_MS){cancel();return;}
        count++;lastUp=now;if(count==3)commit();
    }
    public void confirm(long now){if(!down&&count>0&&now>=lastUp+timeout)commit();}
    public long deadline(){return !down&&count>0?lastUp+timeout:-1;}
    public void cancel(){count=0;down=false;}
}
