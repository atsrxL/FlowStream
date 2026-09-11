package dev.flowstream.player.playback;
import android.view.*;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import dev.flowstream.core.VideoViewport;
import java.util.Locale;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class VideoTransformController {
    private final TextureView texture;
    private final VideoViewport viewport=new VideoViewport();
    private VideoViewport.Mode preset=VideoViewport.Mode.WIDTH;
    public VideoTransformController(PlayerView view){
        texture=(TextureView)view.getVideoSurfaceView();texture.setOpaque(false);
        texture.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(r-l!=or-ol || b-t!=ob-ot){viewport.setViewport(r-l,b-t);viewport.preset(preset);apply();}
        });
    }
    public boolean inspectMode(){return viewport.mode()==VideoViewport.Mode.CUSTOM;}
    public boolean inspectAt(float x,float y){return inspectMode();}
    public void onVideoSize(VideoSize size){viewport.setVideoSize(size.width,size.height,size.pixelWidthHeightRatio);if(!inspectMode())viewport.preset(preset);apply();}
    public void newVideo(){viewport.clearVideo();viewport.preset(preset);apply();}
    public void resetFit(){setPreset(0);}
    public void setPreset(int which){preset=which==1?VideoViewport.Mode.HEIGHT:which==2?VideoViewport.Mode.NATIVE:VideoViewport.Mode.WIDTH;viewport.preset(preset);apply();}
    public int presetIndex(){return preset==VideoViewport.Mode.HEIGHT?1:preset==VideoViewport.Mode.NATIVE?2:0;}
    public void cyclePreset(){setPreset(inspectMode()?0:(presetIndex()+1)%3);}
    public void pan(float dx,float dy){if(inspectMode()){viewport.pan(dx,dy);apply();}}
    public void pinch(float factor,float ox,float oy,float x,float y){viewport.pinch(factor,ox,oy,x,y);apply();}
    public void finish(){viewport.finishTransform();apply();}
    public String label(){if(inspectMode())return String.format(Locale.ROOT,"自定义缩放 %.2f× · 拖动画面 · 三击恢复宽度适屏",viewport.relativeToFit());return presetIndex()==0?"1 · 宽度适屏":presetIndex()==1?"2 · 高度适屏":"3 · 原始大小";}
    private void apply(){
        texture.setPivotX(0);texture.setPivotY(0);
        boolean ready=viewport.ready()&&texture.getWidth()>0&&texture.getHeight()>0;
        texture.setScaleX(ready?(float)(viewport.width()/texture.getWidth()):1);
        texture.setScaleY(ready?(float)(viewport.height()/texture.getHeight()):1);
        texture.setTranslationX(ready?(float)viewport.left():0);texture.setTranslationY(ready?(float)viewport.top():0);texture.invalidate();
    }
}
