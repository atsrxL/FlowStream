package dev.flowstream.core;

/** Shared persisted action names and defaults for the nine playback zones. */
public final class GestureBindings {
    public static final int COUNT = 9;
    private GestureBindings() {}
    /** Three equal-height rows and columns of 20%, 60%, 20%. */
    public static int zoneAt(float x,float y,float width,float height) {
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(width)||!Float.isFinite(height)
                ||width<=0||height<=0||x<0||x>=width||y<0||y>=height)return -1;
        int row=Math.min(2,(int)(3f*y/height));
        int col=x<width*.2f?0:x<width*.8f?1:2;
        return row*3+col;
    }
    public static String[] actions(String kind) {
        switch(kind) {
            case "tap": case "double": return new String[]{"none","hud","play","fit","seek_back_1","seek_forward_1"};
            case "hold": return new String[]{"none","reverse","forward"};
            case "drag": return new String[]{"none","scrub"};
            default: throw new IllegalArgumentException("gesture kind");
        }
    }
    public static String defaultAction(int zone,String kind) {
        if(zone<0||zone>=COUNT)throw new IllegalArgumentException("gesture zone");
        int row=zone/3,col=zone%3;
        switch(kind) {
            case "tap": return row==0?"none":"hud";
            case "double": return row==0?"none":col==0?"seek_back_1":col==2?"seek_forward_1":row==1?"fit":"play";
            case "hold": return row==0?"none":row==1?"reverse":"forward";
            case "drag": return row==0?"none":"scrub";
            default: throw new IllegalArgumentException("gesture kind");
        }
    }

    public static String normalize(int zone,String kind,String action) {
        String fallback=defaultAction(zone,kind);
        for(String allowed:actions(kind))if(allowed.equals(action))return action;
        return fallback;
    }
    public static String label(String action) {
        switch(action) {
            case "hud": return "显示 / 隐藏菜单";
            case "play": return "播放 / 暂停";
            case "fit": return "切换适屏模式";
            case "reverse": return "快退调速";
            case "forward": return "快进调速";
            case "scrub": return "调整播放进度";
            case "seek_back_1": return "后退 1 秒";
            case "seek_forward_1": return "前进 1 秒";
            default: return "无操作";
        }
    }
}
