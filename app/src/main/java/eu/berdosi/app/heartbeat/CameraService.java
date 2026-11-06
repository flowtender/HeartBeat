package eu.berdosi.app.heartbeat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SizeF;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

class CameraService {
    static final String PREFS_NAME = "camera";
    /** Physical camera ID to pin, or "" to let the phone choose (original behaviour). */
    static final String PREF_PHYSICAL_ID = "physicalCameraId";

    private String cameraId;
    private final Activity activity;
    private final Handler handler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession previewSession;

    private CaptureRequest.Builder previewCaptureRequestBuilder;

    /** Whether the torch should be lit; applied when the session starts and whenever it changes. */
    private boolean torchOn = false;

    CameraService(Activity _activity, Handler _handler) {
        activity = _activity;
        handler = _handler;
    }

    /** One physical lens behind the logical rear camera. */
    static class Lens {
        final String id;
        final float focalLengthMm;
        final double horizontalFovDeg;
        String label;
        String kind;

        Lens(String id, float focalLengthMm, double horizontalFovDeg) {
            this.id = id;
            this.focalLengthMm = focalLengthMm;
            this.horizontalFovDeg = horizontalFovDeg;
        }
    }

    /**
     * Lists the physical lenses behind the first (logical) camera, widest first,
     * labelled ultrawide / main / telephoto by field of view. Empty if the phone
     * doesn't expose them or Android is older than 9.
     */
    static List<Lens> listPhysicalLenses(Context context) {
        List<Lens> lenses = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return lenses;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String logicalId = manager.getCameraIdList()[0];
            Set<String> physicalIds = manager.getCameraCharacteristics(logicalId).getPhysicalCameraIds();
            for (String id : physicalIds) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (focals == null || focals.length == 0 || sensor == null) continue;
                float f = focals[0];
                double fov = Math.toDegrees(2 * Math.atan(sensor.getWidth() / (2 * f)));
                lenses.add(new Lens(id, f, fov));
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.e("camera", "Could not list physical lenses", e);
        }
        // widest field of view first
        Collections.sort(lenses, new java.util.Comparator<Lens>() {
            @Override
            public int compare(Lens a, Lens b) {
                return Double.compare(b.horizontalFovDeg, a.horizontalFovDeg);
            }
        });
        for (int i = 0; i < lenses.size(); i++) {
            String kind;
            if (lenses.size() == 1) kind = "main";
            else if (i == 0) kind = "ultrawide";
            else if (i == 1) kind = "main";
            else kind = "telephoto";
            Lens l = lenses.get(i);
            l.kind = kind;
            l.label = String.format(java.util.Locale.ROOT, "%s (lens %s, %.1f mm, %.0f° wide)",
                    kind, l.id, l.focalLengthMm, l.horizontalFovDeg);
        }
        return lenses;
    }

    void start(Surface previewSurface) {

        CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = Objects.requireNonNull(cameraManager).getCameraIdList()[0];
        } catch (CameraAccessException | NullPointerException | ArrayIndexOutOfBoundsException e) {
            Log.e("camera", "No access to camera", e);
            handler.sendMessage(Message.obtain(
                handler,
                MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                "No access to camera...."));
        }

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final String pinnedPhysicalId = prefs.getString(PREF_PHYSICAL_ID, "");
        // every camera session starts with the torch off; a reading turns it on
        torchOn = false;
        previewSession = null;
        previewCaptureRequestBuilder = null;

        try {

            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.println(Log.ERROR, "camera", "No permission to take photos");
                handler.sendMessage(Message.obtain(
                    handler,
                    MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                    "No permission to take photos"));
                return;
            }

            // message has been sent to MainActivity, this method can return.
            if (cameraId == null) {
                return;
            };

            final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            Objects.requireNonNull(cameraManager).openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;

                    CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            previewSession = session;
                            try {

                                previewCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                previewCaptureRequestBuilder.addTarget(previewSurface); // this is previewSurface
                                previewCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                        torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                                lockFocus(previewCaptureRequestBuilder, characteristics);

                                previewSession.setRepeatingRequest(
                                        previewCaptureRequestBuilder.build(), null, null);

                            } catch (CameraAccessException e) {
                                if (e.getMessage() != null) {
                                    Log.println(Log.ERROR, "camera", e.getMessage());
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.println(Log.ERROR, "camera", "Session configuration failed");
                        }
                    };

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !pinnedPhysicalId.isEmpty()) {
                            // Pin the stream to one physical lens so the phone can't swap lenses mid-reading.
                            OutputConfiguration output = new OutputConfiguration(previewSurface);
                            output.setPhysicalCameraId(pinnedPhysicalId);
                            SessionConfiguration config = new SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    Collections.singletonList(output),
                                    activity.getMainExecutor(),
                                    stateCallback);
                            camera.createCaptureSession(config);
                            Log.i("camera", "Pinned to physical lens " + pinnedPhysicalId);
                        } else {
                            // deprecated in API 30, but changing it would bump minSdkVersion to 28.
                            //noinspection deprecation
                            camera.createCaptureSession(Collections.singletonList(previewSurface), stateCallback, null); //1
                        }
                    } catch (CameraAccessException | IllegalArgumentException e) {
                        Log.e("camera", "Could not create capture session", e);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                }
            }, null);
        } catch (CameraAccessException | SecurityException e) {
            if (e.getMessage() != null) {
                Log.println(Log.ERROR, "camera", e.getMessage());
                handler.sendMessage(Message.obtain(
                    handler,
                    MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                    e.getMessage()));
            }
        }
    }

    /** Lights or turns off the torch: right away if the camera is running, else when it starts. */
    void setTorch(boolean on) {
        torchOn = on;
        if (previewSession == null || previewCaptureRequestBuilder == null) return;
        try {
            previewCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                    on ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            previewSession.setRepeatingRequest(previewCaptureRequestBuilder.build(), null, null);
        } catch (CameraAccessException | IllegalStateException e) {
            // the session is closing; closing the camera turns the torch off anyway
            Log.e("camera", "cannot switch the torch", e);
        }
    }

    /**
     * Turns autofocus off and parks the lens at a fixed distance. Focus doesn't matter for
     * pulse readings, and continuous autofocus hunting on a fingertip may be what prompts
     * the phone to switch to its close-focus lens.
     */
    private static void lockFocus(CaptureRequest.Builder builder, CameraCharacteristics c) {
        Float minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocusDistance == null || minFocusDistance <= 0f) return; // fixed-focus lens
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f); // 0 = infinity
    }

    void stop() {
        try {
            cameraDevice.close();
        } catch (Exception e) {
            Log.println(Log.ERROR, "camera", "cannot close camera device" + e.getMessage());
        }
        previewSession = null;
        previewCaptureRequestBuilder = null;
    }
}
