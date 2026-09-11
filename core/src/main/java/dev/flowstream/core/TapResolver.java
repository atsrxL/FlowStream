package dev.flowstream.core;

/**
 * Delays single taps until the double-tap window has closed. Unlike Android's
 * onDoubleTap (second DOWN), a double tap is committed on a valid second UP;
 * second-contact drags, holds, CANCEL and pinch cannot accidentally zoom/pause.
 * Coordinates are rendering pixels, times are monotonic milliseconds.
 */
public final class TapResolver {
    public interface Output { void singleTap(); default void singleTapAt(float x,float y){singleTap();} void doubleTap(float x, float y); }
    private final Output out;
    private final long timeoutMs;
    private final float distancePx;
    private boolean pending, down, candidate;
    private long firstUp, downAt;
    private float firstX, firstY, downX, downY;
    public TapResolver(long timeoutMs, float distancePx, Output out) {
        if (timeoutMs <= 0 || !(distancePx > 0) || !Float.isFinite(distancePx)) throw new IllegalArgumentException("Invalid tap thresholds");
        this.timeoutMs = timeoutMs; this.distancePx = distancePx; this.out = java.util.Objects.requireNonNull(out);
    }
    public void down(float x, float y, long now) {
        candidate = pending && now >= firstUp && now - firstUp <= timeoutMs
                && Math.hypot(x - firstX, y - firstY) <= distancePx;
        if (pending && !candidate) { pending = false; out.singleTapAt(firstX,firstY); }
        down = true; downAt = now; downX = x; downY = y;
    }
    public void up(boolean eligibleTap, float x, float y, long now) {
        if (!down) return;
        down = false;
        if (!eligibleTap || now < downAt || now - downAt >= GestureMachine.HOLD_MS) { cancel(); return; }
        if (candidate) {
            cancel(); out.doubleTap(downX, downY); return;
        }
        pending = true; candidate = false; firstUp = now; firstX = x; firstY = y;
    }
    public void confirm(long now) {
        if (pending && !down && now - firstUp >= timeoutMs) { pending = false; out.singleTapAt(firstX,firstY); }
    }
    /** -1 means no timer is needed; the Android adapter uses Handler.postAtTime. */
    public long deadline() { return pending && !down ? firstUp + timeoutMs : -1; }
    public void cancel() { pending = down = candidate = false; }
}
