package eu.berdosi.app.heartbeat;

/** What the accelerometer saw over the last few seconds. */
final class Posture {
    /** Unit vector of the average acceleration, i.e. which way "down" is, in phone coordinates. */
    final float gx, gy, gz;
    /** Standard deviation of the acceleration magnitude, m/s²: about 0 at rest, large when walking. */
    final float motion;

    Posture(float gx, float gy, float gz, float motion) {
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
        this.motion = motion;
    }

    float[] gravity() {
        return new float[]{gx, gy, gz};
    }
}
