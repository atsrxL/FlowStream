package dev.flowstream.core;

/** Shared persisted action names and defaults for the three playback zones. */
public final class GestureBindings {
    private GestureBindings() {}
    public static String[] actions(String kind) {
        switch(kind) {
            case "tap": case "double": return new String[]{"none","hud","play","fit"};
            case "hold": return new String[]{"none","reverse","forward"};
            case "drag": return new String[]{"none","scrub"};
            default: throw new IllegalArgumentException("gesture kind");
        }
    }
    public static String defaultAction(int zone,String kind) {
        if(zone<0||zone>2)throw new IllegalArgumentException("gesture zone");
        switch(kind) {
            case "tap": return zone==2?"hud":"none";
            case "double": return zone==1?"fit":zone==2?"play":"none";
            case "hold": return zone==0?"reverse":zone==2?"forward":"none";
            case "drag": return zone==1?"scrub":"none";
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
            case "reverse": return "倒放调速";
            case "forward": return "快进调速";
            case "scrub": return "调整播放进度";
            default: return "无操作";
        }
    }
}
