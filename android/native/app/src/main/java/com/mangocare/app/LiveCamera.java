package com.mangocare.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Size;

import androidx.activity.ComponentActivity;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.view.TransformExperimental;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

@OptIn(markerClass = TransformExperimental.class)
final class LiveCamera {
    interface Listener {
        void onFrame(Bitmap bitmap, OutputTransform transform);
        void onError();
    }

    private final ComponentActivity activity;
    private final Executor executor;
    private ImageAnalysis analysis;
    private Preview preview;
    private ProcessCameraProvider provider;
    private volatile int generation;

    LiveCamera(ComponentActivity activity, Executor executor) {
        this.activity = activity;
        this.executor = executor;
    }

    void start(PreviewView view, int rotation, Listener listener) {
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
                CameraSelector camera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                boolean streaming = bind(view, camera, rotation);
                ImageProxyTransformFactory transforms = new ImageProxyTransformFactory();
                transforms.setUsingRotationDegrees(true);
                analysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
                    private long lastFrame;

                    @Override public void analyze(ImageProxy frame) {
                        Bitmap image = null;
                        try {
                            int degrees;
                            OutputTransform transform;
                            try (frame) {
                                long now = SystemClock.elapsedRealtime();
                                if (token != generation || now - lastFrame < 180) return;
                                lastFrame = now;
                                degrees = frame.getImageInfo().getRotationDegrees();
                                transform = streaming ? transforms.getOutputTransform(frame) : null;
                                image = frame.toBitmap();
                            }
                            if (degrees != 0) {
                                Matrix rotation = new Matrix();
                                rotation.postRotate(degrees);
                                Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(),
                                        image.getHeight(), rotation, false);
                                if (rotated != image) image.recycle();
                                image = rotated;
                            }
                            if (token == generation) {
                                Bitmap ready = image;
                                image = null;
                                listener.onFrame(ready, transform);
                            }
                        } catch (RuntimeException error) {
                            activity.runOnUiThread(() -> {
                                if (token == generation) listener.onError();
                            });
                        } finally {
                            if (image != null) image.recycle();
                        }
                    }
                });
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                listener.onError();
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    private boolean bind(PreviewView view, CameraSelector camera, int rotation) {
        var viewport = view.getViewPort(rotation);
        if (viewport != null) {
            preview = new Preview.Builder().setTargetRotation(rotation).build();
            preview.setSurfaceProvider(view.getSurfaceProvider());
            try {
                provider.bindToLifecycle(activity, camera, new UseCaseGroup.Builder()
                        .setViewPort(viewport).addUseCase(preview).addUseCase(analysis).build());
                return true;
            } catch (IllegalArgumentException unsupportedCombination) {
                // Some cameras cannot run this pair of use cases at the same time.
                provider.unbind(preview, analysis);
                preview = null;
            }
        }
        provider.bindToLifecycle(activity, camera, analysis);
        return false;
    }

    void stop() {
        generation++;
        if (analysis != null) {
            analysis.clearAnalyzer();
            if (provider != null) {
                if (preview != null) provider.unbind(analysis, preview);
                else provider.unbind(analysis);
            }
            analysis = null;
        }
        preview = null;
    }
}
