package eu.berdosi.app.heartbeat;

import android.graphics.Bitmap;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.view.TextureView;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measures a pulse from the camera picture.
 *
 * Sampling is driven by the camera's frames ({@link #onFrame}), not by a timer: every preview frame
 * is read once, in order, on one thread, and stamped with the time its arrival was announced. Frames
 * that arrive while the previous one is still being read are skipped rather than queued, so the
 * samples never pile up or overtake each other. The beats are then found by {@link BeatDetector}.
 */
class OutputAnalyzer {
    private final MainActivity activity;

    private final ChartDrawer chartDrawer;

    private MeasureStore store;
    /** Samples from the first clipLength ms: shown on the chart, but never analysed. */
    private MeasureStore warmupStore;

    private static final int MEASUREMENT_LENGTH_MS = 15000;
    private static final int CLIP_LENGTH_MS = 3500;
    /** Longest the app waits for a finger and a steady picture before giving up. */
    private static final long SETTLE_TIMEOUT_MS = 20000;
    /** How often the beats are searched again, and the pulse shown, while measuring. */
    private static final long LIVE_UPDATE_MS = 300;
    /** The chart averages this many samples; the analysis does its own smoothing. */
    private static final int CHART_ROLLING_AVERAGE = 4;
    /** Frames are shrunk by this factor before averaging; the mean colour is all that is used. */
    private static final int FRAME_SHRINK = 8;

    private enum Phase {
        IDLE,
        /** Waiting for the finger and the picture to settle. */
        SETTLING,
        /** Settled; the measurement is being started on the main thread. No frames are read. */
        STARTING,
        MEASURING
    }

    private volatile Phase phase = Phase.IDLE;
    /** {@link #stop()} was called; nothing may start any more. */
    private volatile boolean stopped;
    private final AtomicBoolean reading = new AtomicBoolean();
    private final AtomicBoolean drawing = new AtomicBoolean();

    /** Beats found by the latest search, for the chart. */
    private volatile List<Long> beatMarkers = Collections.emptyList();

    private TextureView preview;
    private CountDownTimer settleTimer;
    private ExecutorService readExecutor;
    private ExecutorService drawExecutor;
    private final Runnable finishTask = this::finishMeasurement;
    private long measurementStart;

    // touched by the read executor's thread only
    private Bitmap frame;
    private int[] pixels;
    private FingerSettle settleDetector;
    private FingerSettle.Status reportedStatus;
    private long lastLiveUpdate;

    private final Handler mainHandler;

    OutputAnalyzer(MainActivity activity, TextureView graphTextureView, Handler mainHandler) {
        this.activity = activity;
        this.chartDrawer = new ChartDrawer(graphTextureView);
        this.mainHandler = mainHandler;
    }

    /** What a finished reading produced. */
    static final class Result {
        final float bpm;
        final int cycles;
        final float seconds;
        /** Whether the beats were regular enough to trust the pulse. */
        final SignalQuality.Verdict quality;
        /** Summary followed by the raw samples and detected peaks, as exported by "Export Details". */
        final String details;

        Result(float bpm, int cycles, float seconds, SignalQuality.Verdict quality, String details) {
            this.bpm = bpm;
            this.cycles = cycles;
            this.seconds = seconds;
            this.quality = quality;
            this.details = details;
        }
    }

    /** Blanks the chart. */
    void clearChart() {
        chartDrawer.clear();
    }

    /** Waits until a finger covers the camera and the colour has steadied, then measures the pulse. */
    void measurePulse(TextureView textureView) {
        store = new MeasureStore();
        warmupStore = new MeasureStore();
        preview = textureView;
        readExecutor = Executors.newSingleThreadExecutor();
        drawExecutor = Executors.newSingleThreadExecutor();
        settleDetector = new FingerSettle();
        reportedStatus = null;

        phase = Phase.SETTLING;
        activity.setViewState(MainActivity.VIEW_STATE.SETTLING);

        settleTimer = new CountDownTimer(SETTLE_TIMEOUT_MS, SETTLE_TIMEOUT_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
            }

            @Override
            public void onFinish() {
                if (phase != Phase.SETTLING) return;
                phase = Phase.IDLE;
                sendMessage(MainActivity.MESSAGE_SETTLE_FAILED, null);
            }
        };
        settleTimer.start();
    }

    /** A new preview frame is ready. Call on the main thread, from onSurfaceTextureUpdated. */
    void onFrame() {
        Phase current = phase;
        if (current != Phase.SETTLING && current != Phase.MEASURING) return;
        if (!reading.compareAndSet(false, true)) return;
        final long now = System.currentTimeMillis();
        try {
            readExecutor.execute(() -> {
                try {
                    read(current, now);
                } finally {
                    reading.set(false);
                }
            });
        } catch (RejectedExecutionException e) { // stop() raced with the frame
            reading.set(false);
        }
    }

    private void read(Phase readPhase, long now) {
        int width = Math.max(1, preview.getWidth() / FRAME_SHRINK);
        int height = Math.max(1, preview.getHeight() / FRAME_SHRINK);
        if (preview.getWidth() == 0 || preview.getHeight() == 0) return;
        if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pixels = new int[width * height];
        }
        preview.getBitmap(frame);
        frame.getPixels(pixels, 0, width, 0, 0, width, height);

        long red = 0, green = 0, blue = 0;
        for (int pixel : pixels) {
            red += (pixel >> 16) & 0xff;
            green += (pixel >> 8) & 0xff;
            blue += pixel & 0xff;
        }
        double count = pixels.length;

        if (readPhase == Phase.SETTLING) {
            settle(now, red / count, green / count, blue / count);
        } else {
            measure(now, red / count);
        }
    }

    private void settle(long now, double red, double green, double blue) {
        FingerSettle.Status status = settleDetector.update(now, red, green, blue);
        if (status != reportedStatus) {
            reportedStatus = status;
            sendMessage(MainActivity.MESSAGE_SETTLE_STATUS, status);
        }
        if (status == FingerSettle.Status.STABLE && phase == Phase.SETTLING) {
            phase = Phase.STARTING;
            mainHandler.post(this::startMeasurement);
        }
    }

    /** The finger is on and the colour steady: record the pulse. */
    private void startMeasurement() {
        if (stopped) return;
        settleTimer.cancel();

        measurementStart = System.currentTimeMillis();
        lastLiveUpdate = 0;
        activity.onMeasurementBegan();
        activity.setViewState(MainActivity.VIEW_STATE.MEASUREMENT);
        phase = Phase.MEASURING;
        mainHandler.postDelayed(finishTask, MEASUREMENT_LENGTH_MS);
    }

    private void measure(long now, double red) {
        // the first measurements are broken by exposure metering: chart them, don't analyse them
        boolean warmup = now - measurementStart < CLIP_LENGTH_MS;
        (warmup ? warmupStore : store).add(now, red);

        if (!warmup && now - lastLiveUpdate >= LIVE_UPDATE_MS) {
            lastLiveUpdate = now;
            BeatDetector.Result found = BeatDetector.analyse(store.times(), store.values());
            beatMarkers = found.beatList();
            if (found.beatTimes.length >= 2) {
                int cycles = found.beatTimes.length - 1;
                sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, describe(
                        pulseOf(found), cycles, (now - measurementStart - CLIP_LENGTH_MS) / 1000f));
            }
        }

        if (drawing.compareAndSet(false, true)) {
            try {
                drawExecutor.execute(() -> {
                    try {
                        chartDrawer.draw(warmupStore.getStdValues(CHART_ROLLING_AVERAGE),
                                store.getStdValues(CHART_ROLLING_AVERAGE), beatMarkers);
                    } finally {
                        drawing.set(false);
                    }
                });
            } catch (RejectedExecutionException e) {
                drawing.set(false);
            }
        }
    }

    /** Beats per minute from the first beat to the last. */
    private static float pulseOf(BeatDetector.Result found) {
        int cycles = found.beatTimes.length - 1;
        float seconds = (found.beatTimes[cycles] - found.beatTimes[0]) / 1000f;
        return 60f * cycles / Math.max(1, seconds);
    }

    private String describe(float bpm, int cycles, float seconds) {
        return String.format(
                Locale.getDefault(),
                activity.getResources().getQuantityString(R.plurals.measurement_output_template, cycles),
                bpm, cycles, seconds);
    }

    /** The time is up. Runs on the main thread; the analysis follows the last sample on the read thread. */
    private void finishMeasurement() {
        if (stopped) return;
        phase = Phase.IDLE;
        try {
            readExecutor.execute(this::analyseAndReport);
        } catch (RejectedExecutionException e) {
            // stopped meanwhile
        }
    }

    private void analyseAndReport() {
        BeatDetector.Result found = BeatDetector.analyse(store.times(), store.values());

        // If the camera only provided a static image, there are no valleys in the signal.
        // A camera not available error is shown, which is the most likely cause.
        if (found.beatTimes.length == 0) {
            mainHandler.sendMessage(Message.obtain(
                    mainHandler,
                    MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                    "No valleys detected - there may be an issue when accessing the camera."));
            return;
        }

        final int cycles = found.beatTimes.length - 1;
        final float seconds = (found.beatTimes[cycles] - found.beatTimes[0]) / 1000f;
        final float bpm = pulseOf(found);

        String currentValue = describe(bpm, cycles, seconds);
        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);

        StringBuilder returnValueSb = new StringBuilder();
        returnValueSb.append(currentValue);
        returnValueSb.append(activity.getString(R.string.row_separator));
        returnValueSb.append(activity.getString(R.string.raw_values));
        returnValueSb.append(activity.getString(R.string.row_separator));

        // the samples as the detector saw them, scaled to 0-1 but not averaged
        SimpleDateFormat timeFormat = new SimpleDateFormat(
                activity.getString(R.string.dateFormatGranular), Locale.getDefault());
        CopyOnWriteArrayList<Measurement<Float>> values = store.getStdValues(1);
        for (Measurement<Float> value : values) {
            returnValueSb.append(timeFormat.format(value.timestamp));
            returnValueSb.append(activity.getString(R.string.separator));
            returnValueSb.append(value.measurement);
            returnValueSb.append(activity.getString(R.string.row_separator));
        }

        returnValueSb.append(activity.getString(R.string.output_detected_peaks_header));
        returnValueSb.append(activity.getString(R.string.row_separator));
        for (long beat : found.beatTimes) {
            returnValueSb.append(beat);
            returnValueSb.append(activity.getString(R.string.row_separator));
        }

        sendMessage(MainActivity.MESSAGE_UPDATE_FINAL, new Result(
                bpm, cycles, seconds, SignalQuality.assess(found), returnValueSb.toString()));
    }

    void stop() {
        stopped = true;
        phase = Phase.IDLE;
        if (settleTimer != null) settleTimer.cancel();
        mainHandler.removeCallbacks(finishTask);
        if (readExecutor != null) readExecutor.shutdownNow();
        if (drawExecutor != null) drawExecutor.shutdownNow();
    }

    void sendMessage(int what, Object message) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = message;
        mainHandler.sendMessage(msg);
    }
}
