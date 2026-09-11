package dev.flowstream.player.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import dev.flowstream.core.GestureMachine;
import dev.flowstream.core.MultiTapResolver;

/** One owner per gesture: transport, inspection, or multi-touch. Controls sit above this view. */
public final class GestureOverlay extends View {
    public interface Output extends GestureMachine.Output {
        boolean inspectMode();
        default boolean inspectAt(float x,float y){return inspectMode();}
        void singleTap(float xPx,float yPx);
        void doubleTap(float xPx, float yPx);
        void tripleTap();
        void panPixels(float dxPx, float dyPx);
        void pinch(float factor, float previousX, float previousY, float focusX, float focusY);
        void transformFinished();
    }
    private final GestureMachine machine;
    private final MultiTapResolver taps;
    private final ScaleGestureDetector scaleDetector;
    private final Output output;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final int touchSlop;
    private VelocityTracker tracker;
    private int pointer = -1;
    private boolean active, blocked, multi, tapEligible;
    private float lastX, lastY, focusX, focusY;
    private final Runnable longPress;
    private final Runnable confirmTap;

    public GestureOverlay(Context c, Output out) {
        super(c);
        output = out; density = c.getResources().getDisplayMetrics().density;
        ViewConfiguration config = ViewConfiguration.get(c);
        touchSlop = config.getScaledTouchSlop();
        taps = new MultiTapResolver(ViewConfiguration.getDoubleTapTimeout(), config.getScaledDoubleTapSlop(), new MultiTapResolver.Output() {
            @Override public void singleTap(){performClick();}
            @Override public void singleTapAt(float x,float y){output.singleTap(x,y);}
            @Override public void doubleTap(){output.doubleTap(0,0);}
            @Override public void tripleTap(){output.tripleTap();}
        });
        machine = new GestureMachine(new GestureMachine.Output() {
            @Override public void tap() { tapEligible = true; } // Only a candidate, never immediate pause.
            @Override public void pan(float dx, float dy) { output.panPixels(dx * density, dy * density); }
            @Override public void switchVideo(int delta) { output.switchVideo(delta); }
            @Override public void holdStarted() { cancelTaps(); output.holdStarted(); }
            @Override public void forward(float speed) { output.forward(speed); }
            @Override public void reverse(float magnitude) { output.reverse(magnitude); }
            @Override public void holdEnded() { output.holdEnded(); }
            @Override public void swipePreview(float dy, boolean ready) { output.swipePreview(dy, ready); }
        });
        longPress = () -> { if (active && !blocked && !multi) machine.holdTimeout(SystemClock.uptimeMillis()); };
        confirmTap = () -> taps.confirm(SystemClock.uptimeMillis());
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (!active || blocked || !multi) return false;
                focusX = detector.getFocusX(); focusY = detector.getFocusY();
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (!active || blocked || !multi) return false;
                float x = detector.getFocusX(), y = detector.getFocusY();
                output.pinch(detector.getScaleFactor(), focusX, focusY, x, y);
                focusX = x; focusY = y;
                return true;
            }
        });
        // All tap counting belongs to MultiTapResolver, never quick-scale.
        scaleDetector.setQuickScaleEnabled(false);
        scaleDetector.setStylusScaleEnabled(false);
        setClickable(true); setFocusable(true);
        setContentDescription("视频手势区域。图像内单击播放或暂停，黑边单击显示或隐藏菜单；双击恢复宽度适屏；三击切换宽度适屏、高度适屏、原始大小；双指缩放后拖动画面且不切片；三种预设模式上下滑切片；中下区长按回看或变速。");
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // Keep the first tap's pending confirmation between normal contacts.
            if (active) cancelGesture();
            handler.removeCallbacks(longPress); handler.removeCallbacks(confirmTap);
            machine.cancel(); recycle();
            active = true; blocked = multi = tapEligible = false;
            pointer = e.getPointerId(0); lastX = e.getX(); lastY = e.getY();
            machine.down(lastX/density,lastY/density,getWidth()/density,getHeight()/density,
                    touchSlop/density,e.getEventTime(),output.inspectAt(lastX,lastY));
            if (machine.mode() == GestureMachine.Mode.REJECTED) {
                cancelGesture(); return false; // Leave OS navigation edges alone.
            }
            taps.down(lastX, lastY, e.getEventTime());
            tracker = VelocityTracker.obtain(); tracker.addMovement(e);
            scaleDetector.onTouchEvent(e);
            handler.postAtTime(longPress, e.getEventTime() + GestureMachine.HOLD_MS);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) { cancelGesture(); return true; }
        if (!active || blocked) return true;
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            // Cancel transport immediately, even before the pinch span threshold is met.
            handler.removeCallbacks(longPress); cancelTaps(); machine.cancel(); recycle(); multi = true;
        }
        scaleDetector.onTouchEvent(e); // Feed a complete DOWN..UP/CANCEL stream, not MOVE alone.
        if (multi) {
            if (action == MotionEvent.ACTION_POINTER_UP) {
                int remaining = e.getPointerCount() - 1;
                if (remaining == 1) {
                    int index = e.getActionIndex() == 0 ? 1 : 0;
                    pointer = e.getPointerId(index); lastX = e.getX(index); lastY = e.getY(index);
                }
            } else if (action == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1) {
                int index = e.findPointerIndex(pointer);
                if (index < 0) { cancelGesture(); return true; }
                float x = e.getX(index), y = e.getY(index);
                output.panPixels(x - lastX, y - lastY); lastX = x; lastY = y;
            } else if (action == MotionEvent.ACTION_UP) {
                output.transformFinished(); finishContact();
            }
            // Until ALL pointers lift, never synthesize a tap, a hold, or a video switch.
            return true;
        }
        int index = e.findPointerIndex(pointer);
        if (index < 0) { cancelGesture(); return true; }
        float x = e.getX(index), y = e.getY(index);
        if (tracker != null) tracker.addMovement(e);
        if (action == MotionEvent.ACTION_MOVE) {
            machine.move(x/density,y/density,e.getEventTime());
            if (machine.mode() != GestureMachine.Mode.PENDING) cancelTaps();
        } else if (action == MotionEvent.ACTION_UP) {
            float velocity = 0;
            if (tracker != null) { tracker.computeCurrentVelocity(1000); velocity = tracker.getYVelocity(pointer)/density; }
            handler.removeCallbacks(longPress); tapEligible = false;
            machine.up(x/density,y/density,velocity,e.getEventTime());
            // A switch callback may have synchronously cancelled this overlay.
            if (!active) return true;
            taps.up(tapEligible,x,y,e.getEventTime());
            output.transformFinished();
            finishContact(); scheduleTap();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private void scheduleTap() {
        handler.removeCallbacks(confirmTap);
        long deadline = taps.deadline();
        if (deadline >= 0) handler.postAtTime(confirmTap, deadline);
    }
    private void cancelTaps() { handler.removeCallbacks(confirmTap); taps.cancel(); }
    private void finishContact() { active = false; multi = false; pointer = -1; recycle(); }
    /** Toolbar, focus loss, video switch, PiP and rotation also cancel deferred single taps. */
    public void cancelGesture() {
        handler.removeCallbacks(longPress); cancelTaps(); machine.cancel();
        if (active) {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);
            scaleDetector.onTouchEvent(cancel); cancel.recycle();
        }
        output.transformFinished(); blocked = true; finishContact();
    }
    private void recycle() { if (tracker != null) { tracker.recycle(); tracker = null; } }
    @Override protected void onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow(); }
}
