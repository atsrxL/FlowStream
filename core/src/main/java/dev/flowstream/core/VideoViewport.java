package dev.flowstream.core;

/**
 * Video geometry in rendering pixels, NEVER dp. The input size is Media3's displayed
 * VideoSize (rotation already applied). The texture fills the viewport before this
 * transform. No bitmap/decoded-frame copy or decoder resize is needed while pinching.
 */
public final class VideoViewport {
    public enum Mode { FIT, NATIVE, CUSTOM }
    private double viewportWidth, viewportHeight, sourceWidth, sourceHeight, pixelRatio = 1;
    private double scale = 1, left, top;
    private Mode mode = Mode.FIT;

    public boolean ready() { return viewportWidth > 0 && viewportHeight > 0 && sourceWidth > 0 && sourceHeight > 0; }
    public Mode mode() { return mode; }
    public boolean inspectMode() { return ready() && mode != Mode.FIT; }
    public double scale() { return scale; }
    public double left() { return left; }
    public double top() { return top; }
    public double width() { return sourceWidth * pixelRatio * scale; }
    public double height() { return sourceHeight * scale; }
    public double viewportWidth() { return viewportWidth; }
    public double viewportHeight() { return viewportHeight; }
    public double pixelRatio() { return pixelRatio; }
    public double fitScale() { return ready() ? Math.min(viewportWidth / (sourceWidth * pixelRatio), viewportHeight / sourceHeight) : 1; }
    /** Includes native size even for low-res video which FIT had enlarged. */
    public double minScale() { return Math.min(1, fitScale()); }
    /** Eight times FIT, or native size, whichever is larger. */
    public double maxScale() { return Math.max(1, fitScale() * 8); }
    public double relativeToFit() { return scale / fitScale(); }

    public void setViewport(double w, double h) {
        if (!positive(w) || !positive(h)) return; // Ignore transient zero-sized layouts.
        if (w == viewportWidth && h == viewportHeight) return;
        viewportWidth = w; viewportHeight = h;
        resetFit(); // Rotation, PiP and multi-window must not inherit stale crop offsets.
    }
    public void setVideoSize(int w, int h, double ratio) {
        ratio = positive(ratio) ? ratio : 1;
        if (w <= 0 || h <= 0) { clearVideo(); return; }
        if (sourceWidth == w && sourceHeight == h && pixelRatio == ratio) return;
        sourceWidth = w; sourceHeight = h; pixelRatio = ratio;
        resetFit(); // Includes adaptive-stream resolution changes.
    }
    public void clearVideo() { sourceWidth = sourceHeight = 0; mode = Mode.FIT; scale = 1; left = top = 0; }
    public void resetFit() {
        mode = Mode.FIT; scale = fitScale();
        left = (viewportWidth - width()) / 2; top = (viewportHeight - height()) / 2;
        clamp();
    }
    /** FIT -> native 1:1 around the tap; NATIVE/CUSTOM -> FIT. */
    public boolean toggleNative(double focusX, double focusY) {
        if (!ready() || !finite(focusX) || !finite(focusY)) return false;
        if (mode != Mode.FIT) { resetFit(); return true; }
        rescale(1, focusX, focusY, focusX, focusY);
        mode = Mode.NATIVE;
        clamp(); // Integer origin in native square-pixel mode avoids half-pixel sampling.
        return true;
    }
    public void pan(double dx, double dy) {
        if (!inspectMode() || !finite(dx) || !finite(dy)) return;
        left += dx; top += dy; clamp();
    }
    /** Keep the video sample under the previous two-finger centroid under the new centroid. */
    public void pinch(double factor, double previousX, double previousY, double focusX, double focusY) {
        if (!ready() || !positive(factor) || !finite(previousX) || !finite(previousY)
                || !finite(focusX) || !finite(focusY)) return;
        double target = Math.max(minScale(), Math.min(maxScale(), scale * factor));
        rescale(target, previousX, previousY, focusX, focusY);
        mode = Mode.CUSTOM; clamp();
    }
    /** Only snap after all pointers are up, never change gesture ownership during a pinch. */
    public void finishTransform() {
        if (!ready()) return;
        if (mode == Mode.CUSTOM && Math.abs(scale / fitScale() - 1) <= .015) resetFit();
        else clamp();
    }
    private void rescale(double target, double oldX, double oldY, double newX, double newY) {
        double ratio = target / scale;
        left = newX - (oldX - left) * ratio;
        top = newY - (oldY - top) * ratio;
        scale = target;
    }
    private void clamp() {
        if (!ready()) return;
        left = clampAxis(left, width(), viewportWidth);
        top = clampAxis(top, height(), viewportHeight);
        if (mode == Mode.NATIVE && Math.abs(pixelRatio - 1) < 1e-6) {
            left = pixelAligned(left, width(), viewportWidth);
            top = pixelAligned(top, height(), viewportHeight);
        }
    }
    private static double clampAxis(double offset, double content, double viewport) {
        return content <= viewport ? (viewport - content) / 2 : Math.max(viewport - content, Math.min(0, offset));
    }
    private static double pixelAligned(double offset, double content, double viewport) {
        return content <= viewport ? Math.rint((viewport - content) / 2)
                : Math.max(Math.ceil(viewport - content), Math.min(0, Math.rint(offset)));
    }
    private static boolean finite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
    private static boolean positive(double x) { return finite(x) && x > 0; }
}
