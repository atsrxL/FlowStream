package dev.flowstream.core;

/** Platform-free, one-finger gesture arbitration. Coordinates are dp, time is monotonic ms. */
public final class GestureMachine {
    public enum Mode { IDLE, PENDING, VERTICAL, PAN, SCRUB, HOLD_TOP, HOLD_BOTTOM, REJECTED }
    public interface Output {
        void tap();
        default void pan(float dxDp, float dyDp) {}
        default void scrubStarted() {}
        default void scrubMoved(float totalDxDp, float widthDp) {}
        default void scrubEnded(boolean commit) {}
        void switchVideo(int delta); // +1 next, -1 previous
        void holdStarted();
        void forward(float speed);
        void reverse(float magnitude);
        void holdEnded();
        void swipePreview(float dyDp, boolean committedDistance);
    }
    public static final long HOLD_MS = 380;
    public static final long COOLDOWN_MS = 450;
    public static final float AXIS_RATIO = 1.6f;
    private final Output out;
    private Mode mode = Mode.IDLE;
    private float x0, y0, x, y, width, height, slop, maxTravel;
    private int zone;
    private final String[] zoneHold = {"reverse", "none", "forward"};
    private final String[] zoneDrag = {"none", "scrub", "scrub"};
    private float holdRangeDp = 160f / 2.54f;
    private float topEdgeDp=12f,bottomEdgeDp=32f;
    private long downAt, lastSwitch = Long.MIN_VALUE / 2;
    private boolean holdActive, inspectMode;

    public GestureMachine(Output out) { this.out = out; }
    public Mode mode() { return mode; }
    public void setZoneHoldAction(int zone,String action) { zoneHold[zone]=GestureBindings.normalize(zone,"hold",action); }
    public void setZoneDragAction(int zone,String action) { zoneDrag[zone]=GestureBindings.normalize(zone,"drag",action); }
    public void setVerticalEdgeInsets(float top,float bottom) {
        if(Float.isFinite(top)&&Float.isFinite(bottom)&&top>=0&&bottom>=0){topEdgeDp=top;bottomEdgeDp=bottom;}
    }
    /** Physical one-centimetre horizontal distance converted by the Android display metrics. */
    public void setHoldRangeDp(float rangeDp) {
        if (Float.isFinite(rangeDp) && rangeDp > 0) holdRangeDp = rangeDp;
    }
    public void down(float x, float y, float width, float height, float platformSlop, long now) {
        down(x,y,width,height,platformSlop,now,false);
    }
    /** Latch inspection mode on DOWN. A zoomed drag never turns into a video switch. */
    public void down(float x, float y, float width, float height, float platformSlop, long now, boolean inspectMode) {
        cancel(); this.inspectMode = inspectMode;
        this.x0 = this.x = x; this.y0 = this.y = y;
        this.width = Math.max(1, width); this.height = Math.max(1, height);
        this.slop = Math.max(12f, platformSlop); maxTravel = 0;
        downAt = now;
        zone = Math.min(2, Math.max(0, (int) (3f * y / this.height)));
        // Reserve Android back/home gesture edges. An edge touch cannot trigger any player gesture.
        mode = (x < 24 || x > width - 24 || y < topEdgeDp || y > height - bottomEdgeDp)
                ? Mode.REJECTED : Mode.PENDING;
    }
    public void holdTimeout(long now) {
        if (mode != Mode.PENDING || now - downAt < HOLD_MS || maxTravel > slop) return;
        if (inspectMode || "none".equals(zoneHold[zone])) return;
        boolean reverse = "reverse".equals(zoneHold[zone]);
        mode = reverse ? Mode.HOLD_TOP : Mode.HOLD_BOTTOM;
        holdActive = true;
        out.holdStarted();
        if (reverse) out.reverse(1f); else out.forward(2f);
    }
    public void move(float nx, float ny, long now) {
        float previousX = x, previousY = y;
        x = nx; y = ny;
        float dx = x - x0, dy = y - y0;
        maxTravel = Math.max(maxTravel, (float) Math.hypot(dx, dy));
        if (mode == Mode.PAN) { out.pan(x - previousX, y - previousY); return; }
        if (mode == Mode.SCRUB) { out.scrubMoved(dx, width); return; }
        if (mode == Mode.HOLD_TOP) { out.reverse(SpeedScale.reverseHold(dx, holdRangeDp)); return; }
        if (mode == Mode.HOLD_BOTTOM) { out.forward(SpeedScale.forwardHold(dx, holdRangeDp)); return; }
        if (mode == Mode.PENDING) {
            if (maxTravel > slop) {
                if (inspectMode) { mode = Mode.PAN; out.pan(dx, dy); return; }
                if (Math.abs(dy) >= slop && Math.abs(dy) >= AXIS_RATIO * Math.abs(dx)) {
                    mode = Mode.VERTICAL;
                } else if (Math.abs(dx) >= slop && Math.abs(dx) >= AXIS_RATIO * Math.abs(dy)) {
                    if ("scrub".equals(zoneDrag[zone])) {
                        mode = Mode.SCRUB;
                        out.scrubStarted();
                        out.scrubMoved(dx, width);
                    } else mode = Mode.REJECTED;
                } else if (maxTravel > slop * 2.5f) {
                    mode = Mode.REJECTED; // ambiguous diagonal drag is never converted later
                }
            } else { holdTimeout(now); }
        }
        if (mode == Mode.VERTICAL) out.swipePreview(dy, Math.abs(dy) >= distanceThreshold());
    }
    public void up(float nx, float ny, float velocityYDpSec, long now) {
        if (mode == Mode.PENDING || mode == Mode.PAN || mode == Mode.SCRUB) move(nx, ny, now);
        float dx = nx - x0, dy = ny - y0;
        if (mode == Mode.SCRUB) out.scrubEnded(true);
        else if (holdActive) endHold();
        else if (mode == Mode.VERTICAL) {
            boolean axis = Math.abs(dy) >= AXIS_RATIO * Math.abs(dx);
            boolean far = Math.abs(dy) >= distanceThreshold();
            boolean fling = Math.abs(dy) >= 64 && Math.abs(velocityYDpSec) >= 1000
                    && Math.signum(velocityYDpSec) == Math.signum(dy);
            if (axis && (far || fling) && now - lastSwitch >= COOLDOWN_MS) {
                lastSwitch = now;
                out.switchVideo(dy < 0 ? 1 : -1);
            }
        } else if (mode == Mode.PENDING && maxTravel <= slop && now - downAt < HOLD_MS) {
            out.tap();
        }
        out.swipePreview(0, false);
        mode = Mode.IDLE;
    }
    public float distanceThreshold() { return Math.max(96f, height * .14f); }
    /** Also used for a second pointer, ACTION_CANCEL, lost focus, PiP and rotation. */
    public void cancel() {
        if (mode == Mode.SCRUB) out.scrubEnded(false);
        endHold();
        if (mode != Mode.IDLE) out.swipePreview(0, false);
        mode = Mode.IDLE;
    }
    private void endHold() {
        if (holdActive) { holdActive = false; out.holdEnded(); }
    }
}
