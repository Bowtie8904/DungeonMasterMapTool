package dmmt.audio;

/** Equal-power loop blend, interpolating coarse media timestamps after playback starts. */
public final class LoopCrossfade {
    private final double startMs;
    private final double durationSeconds;
    private double elapsed;
    private boolean resumed;

    public LoopCrossfade(double startMs, double durationSeconds) {
        this.startMs = startMs;
        this.durationSeconds = durationSeconds;
    }

    public void resume() {
        resumed = true;
    }

    public void advance(double positionMs, double deltaSeconds) {
        double positionSeconds = Math.max(0, (positionMs - startMs) / 1000);
        elapsed = elapsed > 0 && !resumed
                ? Math.max(positionSeconds, elapsed + Math.max(0, deltaSeconds))
                : Math.max(elapsed, positionSeconds);
        resumed = false;
    }

    private double progress() {
        return Math.min(1, elapsed / durationSeconds);
    }

    public double incomingGain() {
        return Math.sin(progress() * Math.PI / 2);
    }

    public double outgoingGain() {
        return Math.cos(progress() * Math.PI / 2);
    }

    public boolean finished() {
        return progress() >= 1;
    }
}
