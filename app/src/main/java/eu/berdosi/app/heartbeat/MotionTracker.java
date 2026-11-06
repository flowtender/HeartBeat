package eu.berdosi.app.heartbeat;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/** Feeds the accelerometer into a {@link MotionWindow} while started. Main thread only. */
final class MotionTracker implements SensorEventListener {
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final MotionWindow window = new MotionWindow();

    MotionTracker(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    void start() {
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    void stop() {
        if (accelerometer != null) sensorManager.unregisterListener(this);
    }

    /** The last few seconds of motion; null without an accelerometer or before enough samples. */
    Posture snapshot() {
        return window.snapshot(SystemClock.elapsedRealtime());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // stamped on arrival: SensorEvent.timestamp isn't on a common clock across devices
        window.add(SystemClock.elapsedRealtime(), event.values[0], event.values[1], event.values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
