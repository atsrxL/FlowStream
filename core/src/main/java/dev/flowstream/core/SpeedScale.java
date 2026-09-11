package dev.flowstream.core;

import java.util.Locale;

/** Discrete arithmetic uses integer hundredths to avoid cumulative float drift. */
public final class SpeedScale {
    private SpeedScale() { }
    public static final int COUNT = 46;
    public static float at(int index) {
        if (index < 0 || index >= COUNT) throw new IllegalArgumentException("speed index");
        return (index < 40 ? (index + 1) * 5 : 250 + (index - 40) * 50) / 100f;
    }
    public static int nearest(float speed) {
        if (!Float.isFinite(speed)) return 19;
        int best = 0;
        for (int i = 1; i < COUNT; i++) {
            if (Math.abs(at(i) - speed) < Math.abs(at(best) - speed)) best = i;
        }
        return best;
    }
    public static float normalize(float speed) { return at(nearest(speed)); }
    public static String label(float value) { return String.format(Locale.ROOT, "%.2f×", value); }
    /** Bottom hold: 12dp jitter dead zone; -120dp -> 1x; +180dp -> 5x. */
    public static float bottom(float dxDp) {
        if (Math.abs(dxDp) <= 12f) return 2f;
        float raw = dxDp < 0 ? 2f + (dxDp + 12f) / 108f : 2f + (dxDp - 12f) / 56f;
        return Math.round(Math.max(.1f, Math.min(5f, raw)) * 20f) / 20f;
    }
    /** Middle hold: first 24dp are dead zone; then 0.1x per 12dp, up to 2x. */
    public static float reverse(float dxDp) {
        if (dxDp > -24f) return 0f;
        int tenths = 1 + (int) Math.floor((-dxDp - 24f) / 12f);
        return Math.max(1, Math.min(40, tenths)) / 10f;
    }
}
