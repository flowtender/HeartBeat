# HeartBeat: agent instructions

Android app (Java, minSdk 21, compile/target 30, material 1.4.0) that measures pulse from the rear camera with the torch lit, and keeps a long-term log of readings. It is built for tracking **POTS**, so the log must be durable day after day and the posture/activity tags matter as much as the pulse. "The user" below means whoever runs the app; the original maintainer's choices are recorded as decisions, not as personal preferences to re-ask.

## Build, test, run

- `./gradlew assembleDebug testDebugUnitTest` (Gradle 7.0.2). Needs a JDK Gradle 7.0.2 supports: JDK 11 works on CI, but some local JDK 11 builds hit a javac NPE, in which case use JDK 17 (`JAVA_HOME=/path/to/jdk17`). The Android SDK comes from `ANDROID_HOME` or `sdk.dir` in the git-ignored `local.properties`.
- The first unit-test run needs network (junit isn't in the offline cache); later runs work with `--offline`.
- If a phone is attached (`adb devices`), smoke-test on it: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, `adb shell pm grant eu.berdosi.app.heartbeat android.permission.CAMERA`, drive with `adb shell input tap`, look with `adb exec-out screencap -p`. With no device, say so; do not claim UI verification.
  - Torch state: `adb shell dumpsys media.camera | grep -A1 "android.flash.mode ("`.
  - Log DB (debug build): `adb exec-out run-as eu.berdosi.app.heartbeat cat databases/readings.db > /tmp/r.db`, then read it with python `sqlite3`.
  - A real reading needs a finger on the camera: ask the user to put theirs on it when you press Start. Without a finger the app correctly waits and times out.
- Only unit-test pure classes (plain JVM, no Robolectric): `ReadingEntry`, `MotionWindow`, `PostureClassifier`, `FingerSettle`, `SignalQuality`, `BeatDetector`. Keep new logic in such classes when possible. Tests encode behaviour and boundaries, not wording; some use intervals or whole signals recorded from real readings (`app/src/test/resources/readings/*.csv`: `ms of day,value`, taken from the `details` dump of the log).
- `app/src/test/resources/readings/` is git-ignored (personal health data), so a fresh clone does not have it and the recorded-reading tests in `BeatDetectorTest` cannot run there. Record your own readings into that folder (same format) if you need them; never commit them.

## Constraints

- minSdk 21: no APIs above 21 in app code without a guard (e.g. no `java.util.function`, no `List.forEach`). Lambdas and try-with-resources are fine (they are desugared). Tests may use anything.
- Theme is `Theme.AppCompat.DayNight`. Do not use Material components that enforce a MaterialComponents theme (Chip, MaterialButton...): they crash. `BottomNavigationView` and `Snackbar` are fine.
- **No keyboard anywhere in the app** (maintainer's rule, keep it): use sliders, spinners, buttons.
- No toasts or snackbars for information that is already visible (selected lens, "saved"...). Messages go into the result text box on the Measure tab. Snackbars are only for errors that need attention (save failure, missing permission, no flash), never for choices the user must make: nothing can be pressed reliably during a reading. They go through `MainActivity.snack()`, which anchors them above the bottom navigation.
- New strings are English only (`values/strings.xml`); the other locales were already incomplete. Never remove a key from the default file while another locale still defines it (lint ExtraTranslation).
- Keep `README.md` and `PRIVACY.md` true: they describe the log, the accelerometer use and the backup.
- Comments say *why*; the existing code style is plain and commented. Match it.

## Architecture (package `eu.berdosi.app.heartbeat`)

Single activity, no fragments. `activity_main.xml` stacks the panels over a bottom nav (`tab_measure`, `tab_log`, `tab_settings`):

- `panelMeasure`: always laid out and visible, because the camera preview `TextureView` must keep receiving frames while the log is shown over it.
- `panelLog` (list + "Hide invalid readings" checkbox), `panelEntry` (one reading) and `panelSettings` (camera lens) are overlays toggled with visibility. Back closes the entry panel (`onBackPressed`).

Measurement flow (`MainActivity.VIEW_STATE`: READY → SETTLING → MEASUREMENT → SHOW_RESULTS):

1. Camera opens on resume with the torch **off** (`CameraService`: camera2, optional pinned physical lens from prefs, autofocus locked).
2. Start → `OutputAnalyzer.measurePulse` → SETTLING. `setViewState` lights the torch for SETTLING and MEASUREMENT only. The start button turns into Cancel. Sampling is frame-driven: `MainActivity.onSurfaceTextureUpdated` calls `OutputAnalyzer.onFrame`, which reads the frame (shrunk 8×, mean colour only) on a single-thread executor, stamped with the callback time. A frame arriving while the previous one is still being read is skipped, never queued, so samples stay in order. SETTLING feeds mean R/G/B to `FingerSettle`. It waits for a finger (bright, red share ≥ 0.55) with the red level steady for 2 s. 20 s timeout → `MESSAGE_SETTLE_FAILED`.
3. Stable → `MainActivity.onMeasurementBegan()` (timestamp, accelerometer snapshot, activity suggestion) → MEASUREMENT: 15 s, every frame adds the mean red to `MeasureStore`. The first 3.5 s (`CLIP_LENGTH_MS`) are charted but not analysed. Every 300 ms `BeatDetector.analyse` is re-run over the whole analysed store for the live pulse and the chart's beat lines (so lines near the newest samples may move); the chart is drawn on its own executor.
4. At 15 s `OutputAnalyzer` runs the analysis once more on the read thread (after the last sample) and builds `OutputAnalyzer.Result` (bpm, cycles, seconds, `SignalQuality` verdict, raw details text) → `MainActivity.saveReading` writes a `ReadingEntry` to `ReadingDb` → SHOW_RESULTS. The torch goes off. The details dump holds the samples as the detector saw them (0–1 scaled, **not** averaged); older dumps are 4-sample rolling averages.
5. The reading on screen is `savedEntry`. Changing the activity or the valid/invalid button on the Measure tab writes to that entry immediately, so the Log tab shows it. It is cleared whenever the state leaves SHOW_RESULTS (New measurement, a new reading, the camera restarting, or `onResume`).

Other classes: `ReadingDb` (SQLiteOpenHelper), `ReadingEntry` (model + duration rule), `ActivityLevel` (enum), `ActivityPicker` (spinner wrapper), `LogAdapter` (list rows), `MotionTracker`/`MotionWindow`/`Posture`/`PostureClassifier` (accelerometer), `FingerSettle`, `BeatDetector` (samples → beats), `SignalQuality`.

## Decisions to keep

**Log durability**
- SQLite `readings.db`; every change is written immediately (no save button); timestamps are epoch ms; the activity is stored by enum **name**.
- Schema changes must be `ALTER TABLE` migrations in `onUpgrade`, never a drop: it is a health record. Auto-backup includes the DB.
- Each row keeps the raw dump (`details`, ~6 KB) so "Export Details" works per reading. The result text is regenerated from bpm/cycles/seconds, not stored.
- The log is newest-first, one row per reading.

**Activity, duration, invalid**
- Activities: LYING, SITTING, STANDING, WALKING, AEROBIC, TESTING. The spinner's `activity_levels` string-array is `Not set` followed by the enum in declaration order. Keep them aligned (index = ordinal + 1).
- `invalid` is the user's flag. `isInvalid()` = `invalid || activity == TESTING`, so a test reading is invalid without overwriting the user's own mark (its button on the Measure tab is locked, its checkbox in the log shows ticked and locked).
- `SignalQuality` sets `invalid = true` at save time (never silently deleted); the user can untick. Rules:
  - fewer than 6 beats;
  - less than 40% of the signal's power in its dominant rhythm (`BeatDetector.Result.periodicity`; recorded readings with a visible pulse had ≥ 0.50, movement-dominated ones ≤ 0.32);
  - the pulse from the beats differs from the signal's dominant rhythm (spectrum) by more than 10%;
  - the pulse from the median beat interval differs from the reported pulse by more than 10%;
  - any single gap of 2.5× the median or more.
  - Chosen against the maintainer's own recorded readings; a "count odd intervals" rule was rejected because it flagged good readings.
- Duration: if the previous reading has the same activity and is ≤ 15 min earlier, the new default = previous duration + minutes between them; otherwise 0; null with no activity. It is **not** snapped to the slider scale (snapping every step would stall small increments). The duration slider offers `ReadingEntry.DURATION_STEPS_MIN` (1–10, 15, 20, 25, 30, 40, 50, 60, 90, 120, 180, 240). The label shows the exact value and the thumb sits on the nearest step. A user-set duration (`durationAuto = false`) no longer follows activity changes.
- Activity selection on the Measure tab is sticky for 15 min (`selectionTime`), then resets to Not set. `selectedAuto` marks a detector-chosen value. At measurement start: a fresh user choice is kept untouched; otherwise the detector fills it in. There is deliberately no prompt about a differing guess (the old snackbar was too brief and too small to press, and the user's hands are occupied during a reading): the pick can be changed after the reading, on the Measure tab or in the log. `restoreSelection()` resumes it after an app restart.

**Posture detection** (accelerometer, no permission)
- WALKING: std-dev of |acceleration| over 3 s ≥ 1.2 m/s² (an estimate, untested while walking).
- LYING/SITTING/STANDING cannot be read from phone tilt in general, so they are learned: k-nearest over the gravity vectors of the user's own earlier readings (≤ 200 recent, within 25°, ≥ 3 voters, ≥ 80% agreement). Readings the detector labelled itself (`activityAuto`), WALKING, AEROBIC and TESTING never train it. No confident vote means no suggestion; it knows nothing on day one.
- Not done, by design: detecting the moment of standing up (needs an always-on background service).

**Torch/UI**
- The torch is on only during SETTLING and MEASUREMENT. There is no flash toggle (the old one was replaced by the invalid button).
- The Start button is one button with four states: Start measurement (READY), Cancel (SETTLING), disabled "Measuring…" (MEASUREMENT), New measurement (SHOW_RESULTS). New measurement does not start a reading: it blanks the result, chart and validity row and returns to READY. It deliberately keeps the activity pick (still lapses after 15 min).
- The validity row under the result (`validityRow`: coloured status text + a button that names the action, "Mark as invalid"/"Mark as valid") is `INVISIBLE`, not `GONE`, while there is no reading, so nothing below it moves when a result appears. A TESTING reading shows "Invalid: testing activity" with the button disabled.
- The settle thresholds (`FingerSettle`: red ≥ 90, share ≥ 0.55, drift ≤ 2%, range ≤ 10% over 2 s) were checked with one finger (R≈150 G≈15 B≈22, about 3.5 s to settle). Other phones or skin tones may need tuning.
- `onResume` restarts the camera even when returning from the share sheet; an interrupted reading resets to READY.

## Known weak spots / ideas not done

- **Beat detection** (`BeatDetector`): samples are laid on a 50 Hz grid, the drift is removed (1 s moving average), then the dominant rhythm is taken from a 40–200 bpm spectrum and valleys are picked: ≥ 0.25 s apart, prominence ≥ 0.2× the median valley's, and valleys closer than 0.55× the rhythm's period count once (stronger kept).
  - Tuned on the 26 readings the maintainer recorded on their own phone before this change (all one person, one phone), compared with the spectrum and by eye. For four readings with a clear pulse of 86–92 bpm the old detector had reported 63–69 bpm (two of them passed as valid). The new beat rate is within 10% of the spectrum on every reading that passes `SignalQuality` (18 of the 26), and within 4% on 14 of those 18.
  - A missed beat inside a movement artefact still lowers the reported pulse (reading 13: 86 bpm from beats, spectrum 92). The 10% rules tolerate that by design.
  - Weakness: with a very narrow, flat-between-beats waveform the 2nd harmonic can beat the fundamental in the spectrum, giving a doubled pulse that agrees with itself and passes. Not seen in any recording; a plain "check f/2" rule was tried and breaks the 106 bpm reading, so it was left out.
  - Absolute accuracy is still unmeasured: there is no reference device.
- The 3.5 s warm-up discard is probably redundant now that the settle phase exists (the warm-up samples are not in the dump, so this can't be checked offline yet).
- Mid-reading lens switches are no longer reported (the toast went); the irregular-beat check is the safety net. The lens is chosen on the Settings tab, not on the Measure tab; changing it restarts the camera and clears the reading on screen (the log keeps it).
- Only camera ID `[0]` is opened. Translations for the new strings are missing.
