package eu.berdosi.app.heartbeat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.graphics.Typeface;
import android.util.TypedValue;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.view.TextureView;
import android.view.Surface;
import android.view.View;

import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Button;

import com.google.android.material.snackbar.Snackbar;

import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.database.sqlite.SQLiteException;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.ListView;

import java.text.DateFormat;

public class MainActivity extends Activity implements ActivityCompat.OnRequestPermissionsResultCallback {
    private OutputAnalyzer analyzer;

    private final int REQUEST_CODE_CAMERA = 0;
    public static final int MESSAGE_UPDATE_REALTIME = 1;
    public static final int MESSAGE_UPDATE_FINAL = 2;
    public static final int MESSAGE_CAMERA_NOT_AVAILABLE = 3;
    public static final int MESSAGE_SETTLE_STATUS = 4;
    public static final int MESSAGE_SETTLE_FAILED = 5;

    public enum VIEW_STATE {
        READY,
        /** The torch is on; waiting for a finger and a steady picture. */
        SETTLING,
        MEASUREMENT,
        SHOW_RESULTS
    }

    private boolean justShared = false;

    private ReadingDb db;
    private MotionTracker motion;
    private LogAdapter logAdapter;
    private ActivityPicker measurePicker;
    private ActivityPicker entryPicker;

    /** The activity picked on the Measure tab, for the reading being prepared or just finished. */
    private ActivityLevel selectedLevel;
    /** {@link #selectedLevel} came from the orientation detector, not from the user. */
    private boolean selectedAuto;
    /** When the selection was last made or used by a reading. It lapses after 15 minutes. */
    private long selectionTime;
    private long readingStartedAt;
    private Posture readingPosture;
    /** The reading whose results are on screen, already in the log; null otherwise. */
    private ReadingEntry savedEntry;
    /** The reading shown in the entry panel; null while the panel is closed. */
    private ReadingEntry openEntry;
    /** The entry panel's widgets are being filled in by code, not edited by the user. */
    private boolean binding;
    /** The duration slider is being dragged; the entry is written when the finger lifts. */
    private boolean durationDragging;

    private VIEW_STATE viewState = VIEW_STATE.READY;

    private static final String LOG_PREFS = "log";
    private static final String PREF_HIDE_INVALID = "hideInvalid";


    @SuppressLint("HandlerLeak")
    private final Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);

            if (msg.what ==  MESSAGE_UPDATE_REALTIME) {
                showReading(msg.obj.toString());
            }

            if (msg.what == MESSAGE_UPDATE_FINAL) {
                OutputAnalyzer.Result result = (OutputAnalyzer.Result) msg.obj;
                saveReading(result);
                setViewState(VIEW_STATE.SHOW_RESULTS);
                if (result.quality != SignalQuality.Verdict.OK) {
                    // the result is already on screen: say why the reading was marked invalid
                    showReading(((TextView) findViewById(R.id.textView)).getText() + "\n" + getString(
                            result.quality == SignalQuality.Verdict.TOO_FEW_BEATS
                                    ? R.string.invalid_too_few
                                    : R.string.invalid_irregular));
                }
            }

            if (msg.what == MESSAGE_CAMERA_NOT_AVAILABLE) {
                Log.println(Log.WARN, "camera", msg.obj.toString());

                setViewState(VIEW_STATE.READY);
                showMessage(R.string.camera_not_found);
                analyzer.stop();
            }

            if (msg.what == MESSAGE_SETTLE_STATUS && viewState == VIEW_STATE.SETTLING) {
                showMessage(msg.obj == FingerSettle.Status.NO_FINGER ? R.string.place_finger : R.string.hold_still);
            }

            if (msg.what == MESSAGE_SETTLE_FAILED && viewState == VIEW_STATE.SETTLING) {
                setViewState(VIEW_STATE.READY);
                showMessage(R.string.no_steady_finger);
            }
        }
    };

    private void setupCameraControls() {
        final android.content.SharedPreferences prefs =
                getSharedPreferences(CameraService.PREFS_NAME, MODE_PRIVATE);

        // lens buttons: Auto plus one per physical lens, if the phone exposes them
        final RadioGroup group = findViewById(R.id.lensGroup);
        final java.util.List<CameraService.Lens> lenses = CameraService.listPhysicalLenses(this);
        if (lenses.isEmpty()) {
            group.setVisibility(View.GONE);
            ((TextView) findViewById(R.id.lensHint)).setText(R.string.lens_unavailable);
        } else {
            String current = prefs.getString(CameraService.PREF_PHYSICAL_ID, "");
            int checkedId = -1;
            for (int i = -1; i < lenses.size(); i++) {
                RadioButton button = new RadioButton(this);
                button.setId(View.generateViewId());
                // big enough to hit without looking
                button.setTextSize(18);
                button.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
                if (i < 0) {
                    button.setText(R.string.lens_auto);
                    button.setTag("");
                } else {
                    CameraService.Lens lens = lenses.get(i);
                    button.setText(Character.toUpperCase(lens.kind.charAt(0)) + lens.kind.substring(1));
                    button.setTag(lens.id);
                    button.setContentDescription(lens.label);
                }
                group.addView(button);
                if (i < 0 || button.getTag().equals(current)) checkedId = button.getId();
            }
            group.check(checkedId);
            group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup g, int id) {
                    String physicalId = (String) g.findViewById(id).getTag();
                    prefs.edit().putString(CameraService.PREF_PHYSICAL_ID, physicalId).apply();
                    restartMeasurement();
                }
            });
        }
    }

    /** The lens is fixed when the camera session opens, so changing it restarts the reading. */
    private void restartMeasurement() {
        if (analyzer != null) analyzer.stop();
        cameraService.stop();
        prepareNewMeasurement();
    }

    /** Opens the camera, torch off; the torch is lit by the reading itself. */
    private void startCamera() {
        SurfaceTexture previewSurfaceTexture = ((TextureView) findViewById(R.id.textureView2)).getSurfaceTexture();
        // the TextureView isn't ready at the very first onResume; its listener starts the camera then.
        if (previewSurfaceTexture == null) return;
        cameraService.start(new Surface(previewSurfaceTexture));
    }

    private final CameraService cameraService = new CameraService(this, mainHandler);

    @Override
    protected void onResume() {
        super.onResume();
        motion.start();

        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler);

        // justShared is set when leaving for the share sheet; finished results stay as they are.
        // A reading that was interrupted can't continue, the camera having been closed.
        boolean returning = justShared && (viewState == VIEW_STATE.READY || viewState == VIEW_STATE.SHOW_RESULTS);
        justShared = false;

        if (((TextureView) findViewById(R.id.textureView2)).getSurfaceTexture() != null) {
            if (!returning) {
                // show warning when there is no flash
                if (!this.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                    snack(getString(R.string.noFlashWarning), Snackbar.LENGTH_LONG).show();
                }

                setViewState(VIEW_STATE.READY);
                expireSelection();
            }
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        motion.stop();
        cameraService.stop();
        if (analyzer != null) analyzer.stop();
        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CODE_CAMERA);
        setupCameraControls();

        db = new ReadingDb(this);
        motion = new MotionTracker(this);
        measurePicker = new ActivityPicker(findViewById(R.id.activitySpinner), this::onMeasureActivityPicked);
        setupLog();
        restoreSelection();
        findViewById(R.id.invalidButton).setOnClickListener(v -> {
            if (savedEntry == null || savedEntry.activity == ActivityLevel.TESTING) return;
            savedEntry.invalid = !savedEntry.invalid;
            persist(savedEntry);
            showSavedInvalid();
        });

        ((BottomNavigationView) findViewById(R.id.bottomNav)).setOnItemSelectedListener(item -> {
            // the log and settings panels are drawn over the measure panel, which keeps running underneath
            int tab = item.getItemId();
            hideEntry();
            findViewById(R.id.panelLog).setVisibility(tab == R.id.tab_log ? View.VISIBLE : View.GONE);
            findViewById(R.id.panelSettings).setVisibility(tab == R.id.tab_settings ? View.VISIBLE : View.GONE);
            if (tab == R.id.tab_log) refreshLog();
            return true;
        });

        ((TextureView) findViewById(R.id.textureView2)).setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                setViewState(VIEW_STATE.READY);
                startCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                // every camera frame is one sample of the pulse
                if (analyzer != null) analyzer.onFrame();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_CAMERA) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                snack(getString(R.string.cameraPermissionRequired), Snackbar.LENGTH_LONG).show();
            }
        }
    }

    public void setViewState(VIEW_STATE state) {
        viewState = state;
        // the results on screen are only tied to a log entry while they are shown
        if (state != VIEW_STATE.SHOW_RESULTS) savedEntry = null;
        // the torch is only lit from pressing Start until the reading is done
        cameraService.setTorch(state == VIEW_STATE.SETTLING || state == VIEW_STATE.MEASUREMENT);

        // one button: starts a reading, cancels the wait for a finger, or clears a finished reading
        TextView start = findViewById(R.id.startButton);
        start.setEnabled(state != VIEW_STATE.MEASUREMENT);
        start.setText(state == VIEW_STATE.SETTLING ? R.string.cancel_measurement
                : state == VIEW_STATE.MEASUREMENT ? R.string.measuring
                : state == VIEW_STATE.SHOW_RESULTS ? R.string.new_measurement
                : R.string.start_measurement);
        if (state == VIEW_STATE.READY) showMessage(R.string.ready_prompt);
        if (state == VIEW_STATE.SETTLING) showMessage(R.string.place_finger);
        showSavedInvalid();
    }

    /** The pulse summary: bold, scaled to fit the box. */
    private void showReading(String text) {
        showText(text, 24, Typeface.DEFAULT_BOLD);
    }

    /** Instructions and warnings: smaller and plain, still scaled and wrapped to fit the box. */
    private void showMessage(int resId) {
        showText(getString(resId), 16, Typeface.DEFAULT);
    }

    private void showText(String text, int maxSizeSp, Typeface typeface) {
        TextView view = findViewById(R.id.textView);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                view, 10, maxSizeSp, 1, TypedValue.COMPLEX_UNIT_SP);
        view.setTypeface(typeface);
        view.setText(text);
    }

    /** Back to the ready state: clears the results and reopens the camera. */
    private void prepareNewMeasurement() {
        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler);
        setViewState(VIEW_STATE.READY);
        startCamera();
    }

    /**
     * Starts a reading, which begins once the app sees a finger and a steady picture. While waiting
     * for the finger the same button cancels; once a reading is shown it clears it.
     */
    public void onClickStart(View view) {
        if (viewState == VIEW_STATE.SETTLING) {
            analyzer.stop();
            setViewState(VIEW_STATE.READY);
            return;
        }
        if (analyzer != null) analyzer.stop();
        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler);
        if (viewState == VIEW_STATE.SHOW_RESULTS) {
            // the reading stays in the log; the Measure tab goes back to blank. The activity pick is
            // kept (it lapses by itself after 15 minutes), as the next reading is usually the same.
            analyzer.clearChart();
            setViewState(VIEW_STATE.READY);
            return;
        }
        ((TextView) findViewById(R.id.textView)).setText("");
        savedEntry = null;
        analyzer.measurePulse(findViewById(R.id.textureView2));
    }

    /**
     * The finger is on and the picture steady: the recording starts now. The phone has been held
     * in its reading position for the last seconds, so that is when its orientation is read.
     */
    void onMeasurementBegan() {
        readingStartedAt = System.currentTimeMillis();
        readingPosture = motion.snapshot();
        expireSelection();
        applyDetectedActivity();
    }

    // ---- activity of the reading on the Measure tab -------------------------------------------

    /** The user's pick; it also retags the reading on screen if it is already logged. */
    private void onMeasureActivityPicked(ActivityLevel level) {
        selectedLevel = level;
        selectedAuto = false;
        selectionTime = System.currentTimeMillis();
        if (savedEntry != null) applyActivity(savedEntry, level);
    }

    /** After a restart, carries on with the activity of the latest reading if it is recent. */
    private void restoreSelection() {
        ReadingEntry last = db.previous(Long.MAX_VALUE);
        if (last == null || System.currentTimeMillis() - last.timestamp > ReadingEntry.CARRY_OVER_MS) return;
        selectedLevel = last.activity;
        selectedAuto = last.activityAuto;
        selectionTime = last.timestamp;
        measurePicker.set(selectedLevel);
    }

    /** A selection nobody has used for 15 minutes is probably stale: back to "not set". */
    private void expireSelection() {
        if (savedEntry != null || selectedLevel == null) return;
        if (System.currentTimeMillis() - selectionTime > ReadingEntry.CARRY_OVER_MS) {
            selectedLevel = null;
            selectedAuto = false;
            measurePicker.set(null);
        }
    }

    /**
     * Fills in the accelerometer's guess when nothing has been picked recently. A pick made in the
     * last 15 minutes always stays: the guess can't tell sitting from standing when the phone is
     * held at the same tilt, and nothing can be asked of the user while a reading is taken.
     */
    private void applyDetectedActivity() {
        if (selectedLevel != null && !selectedAuto) return;
        final ActivityLevel detected = PostureClassifier.suggest(readingPosture, db.list());
        selectedLevel = detected;
        selectedAuto = detected != null;
        measurePicker.set(detected);
    }

    // ---- the log ------------------------------------------------------------------------------

    /** Writes a finished reading to the log, tagged with the selected activity. */
    private void saveReading(OutputAnalyzer.Result result) {
        ReadingEntry entry = new ReadingEntry(
                readingStartedAt, result.bpm, result.cycles, result.seconds,
                readingPosture == null ? null : readingPosture.gravity());
        entry.activity = selectedLevel;
        entry.activityAuto = selectedAuto;
        entry.invalid = result.quality != SignalQuality.Verdict.OK;
        try {
            entry.durationMin = ReadingEntry.suggestDuration(
                    db.previous(entry.timestamp), entry.activity, entry.timestamp);
            db.insert(entry, result.details);
        } catch (SQLiteException e) {
            Log.e("log", "could not save reading", e);
            snack(getString(R.string.save_failed), Snackbar.LENGTH_LONG).show();
            return;
        }
        savedEntry = entry;
        selectionTime = entry.timestamp;
    }

    private void setupLog() {
        logAdapter = new LogAdapter(this);
        ListView list = findViewById(R.id.logList);
        list.setAdapter(logAdapter);
        list.setEmptyView(findViewById(R.id.logEmpty));
        list.setOnItemClickListener((parent, row, position, id) -> showEntry(logAdapter.getItem(position)));

        CheckBox hide = findViewById(R.id.hideInvalid);
        hide.setChecked(hideInvalid());
        hide.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(LOG_PREFS, MODE_PRIVATE).edit().putBoolean(PREF_HIDE_INVALID, checked).apply();
            refreshLog();
        });

        entryPicker = new ActivityPicker(findViewById(R.id.entrySpinner), this::onEntryActivityPicked);

        SeekBar slider = findViewById(R.id.entryDuration);
        slider.setMax(ReadingEntry.DURATION_STEPS_MIN.length - 1);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || openEntry == null) return;
                openEntry.durationMin = ReadingEntry.DURATION_STEPS_MIN[progress];
                openEntry.durationAuto = false;
                showEntryDuration(openEntry, false);
                if (!durationDragging) persist(openEntry);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                durationDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                durationDragging = false;
                if (openEntry != null) persist(openEntry);
            }
        });

        ((CheckBox) findViewById(R.id.entryInvalid)).setOnCheckedChangeListener((button, checked) -> {
            if (binding || openEntry == null) return;
            openEntry.invalid = checked;
            persist(openEntry);
        });
    }

    private boolean hideInvalid() {
        return getSharedPreferences(LOG_PREFS, MODE_PRIVATE).getBoolean(PREF_HIDE_INVALID, false);
    }

    private void refreshLog() {
        List<ReadingEntry> all = db.list();
        List<ReadingEntry> shown = all;
        if (hideInvalid()) {
            shown = new ArrayList<>();
            for (ReadingEntry entry : all) {
                if (!entry.isInvalid()) shown.add(entry);
            }
        }
        logAdapter.setEntries(shown);
        ((TextView) findViewById(R.id.logEmpty)).setText(all.isEmpty() ? R.string.log_empty : R.string.log_all_hidden);
    }

    private void showEntry(ReadingEntry entry) {
        openEntry = entry;
        binding = true;
        ((TextView) findViewById(R.id.entryTitle)).setText(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                        .format(new Date(entry.timestamp)));
        ((TextView) findViewById(R.id.entrySummary)).setText(summaryOf(entry));
        entryPicker.set(entry.activity);
        showEntryDuration(entry, true);
        showEntryInvalid(entry);
        binding = false;
        findViewById(R.id.panelEntry).setVisibility(View.VISIBLE);
    }

    /** A test reading is always invalid: shown ticked and locked, the user's own mark kept underneath. */
    private void showEntryInvalid(ReadingEntry entry) {
        boolean wasBinding = binding;
        binding = true;
        CheckBox box = findViewById(R.id.entryInvalid);
        box.setChecked(entry.isInvalid());
        box.setEnabled(entry.activity != ActivityLevel.TESTING);
        binding = wasBinding;
    }

    /**
     * Shows the duration as a number, always exact: a computed or older value may sit between
     * two slider steps, so the thumb is only moved to the closest step when {@code moveThumb}.
     */
    private void showEntryDuration(ReadingEntry entry, boolean moveThumb) {
        SeekBar slider = findViewById(R.id.entryDuration);
        slider.setEnabled(entry.activity != null);
        if (moveThumb) {
            slider.setProgress(entry.durationMin == null
                    ? 0 : ReadingEntry.nearestDurationStep(entry.durationMin));
        }
        ((TextView) findViewById(R.id.entryDurationValue)).setText(
                entry.durationMin == null
                        ? getString(R.string.duration_not_set)
                        : getString(R.string.minutes_format, entry.durationMin));
    }

    private void hideEntry() {
        openEntry = null;
        findViewById(R.id.panelEntry).setVisibility(View.GONE);
    }

    public void onClickEntryBack(View view) {
        hideEntry();
        refreshLog();
    }

    @Override
    public void onBackPressed() {
        if (openEntry != null) {
            onClickEntryBack(null);
        } else {
            super.onBackPressed();
        }
    }

    private void onEntryActivityPicked(ActivityLevel level) {
        if (openEntry == null) return;
        applyActivity(openEntry, level);
        showEntryDuration(openEntry, true);
        showEntryInvalid(openEntry);
    }

    /** The user's activity for an entry; a duration that was never typed in follows it. */
    private void applyActivity(ReadingEntry entry, ActivityLevel level) {
        entry.activity = level;
        entry.activityAuto = false;
        if (entry.durationAuto) {
            entry.durationMin = ReadingEntry.suggestDuration(
                    db.previous(entry.timestamp), level, entry.timestamp);
        }
        persist(entry);
        if (entry == savedEntry) showSavedInvalid();
    }

    private void persist(ReadingEntry entry) {
        try {
            db.update(entry);
        } catch (SQLiteException e) {
            Log.e("log", "could not update reading", e);
            snack(getString(R.string.save_failed), Snackbar.LENGTH_LONG).show();
        }
    }

    /** Errors and prompts that need a snackbar sit above the navigation bar, never over it. */
    private Snackbar snack(String text, int length) {
        return Snackbar.make(findViewById(R.id.constraintLayout), text, length)
                .setAnchorView(R.id.bottomNav);
    }

    /**
     * The validity line and button under the result follow the reading on screen. The button
     * names what it will do. A test reading is always invalid, so its button is locked.
     */
    private void showSavedInvalid() {
        findViewById(R.id.validityRow).setVisibility(savedEntry == null ? View.INVISIBLE : View.VISIBLE);
        if (savedEntry == null) return;

        boolean testing = savedEntry.activity == ActivityLevel.TESTING;
        boolean invalid = savedEntry.isInvalid();
        TextView status = findViewById(R.id.validityText);
        status.setText(!invalid ? R.string.status_valid
                : testing ? R.string.status_invalid_testing : R.string.status_invalid);
        status.setTextColor(ContextCompat.getColor(this, invalid ? R.color.status_invalid : R.color.status_valid));
        Button button = findViewById(R.id.invalidButton);
        button.setText(invalid ? R.string.mark_valid : R.string.mark_invalid);
        button.setEnabled(!testing);
    }

    private String summaryOf(ReadingEntry entry) {
        return getResources().getQuantityString(
                R.plurals.measurement_output_template, entry.cycles, entry.bpm, entry.cycles, entry.seconds);
    }

    public void onClickExportResult(View view) {
        if (openEntry == null) return;
        share(summaryOf(openEntry), openEntry.timestamp);
    }

    public void onClickExportDetails(View view) {
        if (openEntry == null) return;
        share(db.details(openEntry.id), openEntry.timestamp);
    }

    private void share(String text, long timestamp) {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(
                Intent.EXTRA_SUBJECT,
                String.format(
                        getString(R.string.output_header_template),
                        new SimpleDateFormat(
                                getString(R.string.dateFormat),
                                Locale.getDefault()
                        ).format(new Date(timestamp))
                ));
        intent.putExtra(Intent.EXTRA_TEXT, text);
        justShared = true;
        startActivity(Intent.createChooser(intent, getString(R.string.send_output_to)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        db.close();
    }
}
