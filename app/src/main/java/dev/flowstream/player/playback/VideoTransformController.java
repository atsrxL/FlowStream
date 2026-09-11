package dev.flowstream.player.playback;

import android.graphics.Matrix;
import android.view.TextureView;
import android.view.View;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import dev.flowstream.core.VideoViewport;
import java.util.Locale;

/** Texture-only transform: toolbar, subtitle overlay, and screen-third hit areas never scale. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class VideoTransformController {
    private final TextureView texture;
    private final VideoViewport viewport = new VideoViewport();
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    public VideoTransformController(PlayerView playerView) {
        View surface = playerView.getVideoSurfaceView();
        if (!(surface instanceof TextureView)) throw new IllegalArgumentException("flow_video.xml must use texture_view");
        texture = (TextureView) surface;
        // Transformed textures do not cover the whole view in FIT/native low-res mode.
        texture.setOpaque(false);
        texture.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            viewport.setViewport(r-l,b-t); apply();
        });
    }
    public boolean inspectMode() { return viewport.inspectMode(); }
    public void onVideoSize(VideoSize size) { viewport.setVideoSize(size.width,size.height,size.pixelWidthHeightRatio); apply(); }
    public void newVideo() { viewport.clearVideo(); apply(); }
    public void resetFit() { viewport.resetFit(); apply(); }
    public boolean toggleNative(float x, float y) { boolean changed = viewport.toggleNative(x,y); apply(); return changed; }
    public void pan(float dx, float dy) { viewport.pan(dx,dy); apply(); }
    public void pinch(float factor,float oldX,float oldY,float x,float y) { viewport.pinch(factor,oldX,oldY,x,y); apply(); }
    public void finish() { viewport.finishTransform(); apply(); }
    public String label() {
        if (!viewport.ready()) return "等待视频尺寸";
        if (viewport.mode() == VideoViewport.Mode.FIT) return "适应屏幕";
        if (viewport.mode() == VideoViewport.Mode.NATIVE) return Math.abs(viewport.pixelRatio()-1)<1e-6
                ? "原始尺寸 1:1 · 可拖动" : "原始尺寸 · 已校正非方形像素";
        return String.format(Locale.ROOT,"画面 %.2f× 适屏 · 双击复位",viewport.relativeToFit());
    }
    private void apply() {
        if (!viewport.ready() || texture.getWidth() <= 0 || texture.getHeight() <= 0) { matrix.reset(); texture.setTransform(matrix);texture.setScaleX(1);texture.setScaleY(1);texture.setTranslationX(0);texture.setTranslationY(0);return; }
        // PlayerView RESIZE_MODE_FILL keeps the texture at viewport size. Its aspect ratio is
        // applied here exactly once. Android/Media3 has already handled video rotation.
        values[0]=(float)(viewport.width()/texture.getWidth()); values[1]=0; values[2]=(float)viewport.left();
        values[3]=0; values[4]=(float)(viewport.height()/texture.getHeight()); values[5]=(float)viewport.top();
        values[6]=0; values[7]=0; values[8]=1;
        // View properties redraw paused frames immediately, without waiting for a decoder buffer.
        texture.setPivotX(0); texture.setPivotY(0);
        texture.setScaleX(values[0]); texture.setScaleY(values[4]);
        texture.setTranslationX(values[2]); texture.setTranslationY(values[5]);
        texture.invalidate();
    }
}
