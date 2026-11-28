package com.exeai.assistant;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

/**
 * Camera preview drawn on top of the EXE AI screen, so the user never leaves the app.
 * Buttons: close, switch front/back, take a photo. The photo goes to the Listener.
 */
public final class InAppCamera {

    public interface Listener {
        void onPhoto(Bitmap bitmap);

        void onError(String message);
    }

    private final AppCompatActivity activity;
    private final Listener listener;
    private FrameLayout overlay = null;
    private PreviewView preview = null;
    private ImageCapture imageCapture = null;
    private ProcessCameraProvider provider = null;
    private boolean front = false;

    public InAppCamera(AppCompatActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    /** Opens the preview; useFront picks the front camera. Call only when the CAMERA permission is granted. */
    public void open(boolean useFront) {
        front = useFront;
        if (overlay != null) {
            bind();
            return;
        }
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0xff000000);

        preview = new PreviewView(activity);
        overlay.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(12), dp(12), dp(12), dp(32));
        bar.addView(button("Kapat", v -> close()), buttonParams());
        bar.addView(button("Çevir", v -> switchCamera()), buttonParams());
        bar.addView(button("Çek", v -> capture()), buttonParams());
        overlay.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        activity.addContentView(overlay, new ViewGroup.LayoutParams(-1, -1));
        bind();
    }

    /** Stops the camera and removes the preview. */
    public void close() {
        if (provider != null) provider.unbindAll();
        if (overlay != null) {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) parent.removeView(overlay);
        }
        overlay = null;
        preview = null;
        imageCapture = null;
    }

    public boolean isOpen() {
        return overlay != null;
    }

    private void switchCamera() {
        front = !front;
        bind();
    }

    private void bind() {
        if (preview == null) return;
        var future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            try {
                provider = future.get();
                provider.unbindAll();

                Preview previewUseCase = new Preview.Builder().build();
                previewUseCase.setSurfaceProvider(preview.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector selector = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                provider.bindToLifecycle(activity, selector, previewUseCase, imageCapture);
            } catch (Exception e) {
                listener.onError("Kamera açılamadı efendim. Bu cihazda " + (front ? "ön" : "arka") + " kamera yok olabilir.");
                close();
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    private void capture() {
        if (imageCapture == null) return;
        imageCapture.takePicture(ContextCompat.getMainExecutor(activity), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap bitmap = null;
                int rotation = image.getImageInfo().getRotationDegrees();
                try {
                    bitmap = image.toBitmap();
                } catch (Exception ignored) {
                    // handled below
                } finally {
                    image.close();
                }
                if (bitmap != null && rotation != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(rotation);
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                }
                close();
                if (bitmap == null) {
                    listener.onError("Fotoğraf çözülemedi efendim, tekrar dener misin?");
                } else {
                    listener.onPhoto(bitmap);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                listener.onError("Fotoğraf çekilemedi efendim. Tekrar dener misin?");
            }
        });
    }

    private TextView button(String label, View.OnClickListener click) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setTextColor(0xffffffff);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setBackgroundColor(0xcc1a1a1a);
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
