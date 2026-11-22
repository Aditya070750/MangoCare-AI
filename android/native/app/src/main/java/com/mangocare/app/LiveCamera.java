package com.mangocare.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Size;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

final class LiveCamera {
    interface Listener {
        void onFrame(Bitmap bitmap);
        void onError();
    }

    private final ComponentActivity activity;
    private final Executor executor;
    private ImageAnalysis analysis;
    private ProcessCameraProvider provider;
    private volatile int generation;

    LiveCamera(ComponentActivity activity, Executor executor) {
        this.activity = activity;
        this.executor = executor;
    }

    void start(int rotation, Listener listener) {
        stop();
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError();
            return;
        }
        int token = generation;
        var pending = ProcessCameraProvider.getInstance(activity);
        pending.addListener(() -> {
            if (token != generation) return;
            try {
                provider = pending.get();
                ResolutionSelector resolution = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();
                analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
                    private long lastFrame;

                    @Override public void analyze(ImageProxy frame) {
                        try (frame) {
                            long now = SystemClock.elapsedRealtime();
                            if (token != generation || now - lastFrame < 180) return;
                            lastFrame = now;
                            Bitmap image = frame.toBitmap();
                            int degrees = frame.getImageInfo().getRotationDegrees();
                            if (degrees != 0) {
                                Matrix rotation = new Matrix();
                                rotation.postRotate(degrees);
                                Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(),
                                        image.getHeight(), rotation, false);
                                if (rotated != image) image.recycle();
                                image = rotated;
                            }
                            if (token == generation) listener.onFrame(image);
                            else image.recycle();
                        } catch (RuntimeException error) {
                            activity.runOnUiThread(() -> {
                                if (token == generation) listener.onError();
                            });
                        }
                    }
                });
                CameraSelector camera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                provider.bindToLifecycle(activity, camera, analysis);
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                listener.onError();
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    void stop() {
        generation++;
        if (analysis != null) {
            analysis.clearAnalyzer();
            if (provider != null) provider.unbind(analysis);
            analysis = null;
        }
    }
}
