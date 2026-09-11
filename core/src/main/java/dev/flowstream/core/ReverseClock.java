package dev.flowstream.core;

/** Reverse target follows wall time, not the result of an asynchronous/rounded seek. */
public final class ReverseClock {
    private double position;
    private long lastTime;
    private float magnitude;
    public void start(long positionMs, long now, float speed) {
        position = Math.max(0, positionMs); lastTime = now; setMagnitude(speed);
    }
    public void setMagnitude(float speed) { magnitude = Math.max(.1f, Math.min(4f, speed)); }
    public long target(long now) {
        // Limit advances across a stalled UI frame or suspended app to avoid surprise large jumps.
        long elapsed = Math.max(0, Math.min(500, now - lastTime));
        position = Math.max(0, position - elapsed * (double) magnitude);
        lastTime = now;
        return Math.round(position);
    }
}
