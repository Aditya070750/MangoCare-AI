package com.mangocare.app;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MangoModels implements AutoCloseable {
    private static final int DETECTION_SIZE = 640;
    private static final int CLASSIFICATION_SIZE = 224;
    private static final int ANCHORS = 8400;
    private static final float[] RGB_VALUES = normalizedChannels();
    private final AssetManager assets;
    private final List<String> detectionLabels;
    private final List<String> classificationLabels;
    private Interpreter detector;
    private Interpreter classifier;
    private ModelInput detectionInput;
    private ModelInput classificationInput;
    private final float[][][] prediction = new float[1][7][ANCHORS];
    private final float[][] probabilities = new float[1][2];

    record Detection(String label, float confidence, RectF box) {}
    record Score(String label, float confidence) {}
    private record Input(int left, int top, int width, int height) {}

    MangoModels(AssetManager assets) throws IOException {
        this.assets = assets;
        detectionLabels = readLabels("detector_labels.txt");
        classificationLabels = readLabels("classifier_labels.txt");
        if (detectionLabels.size() != 3 || classificationLabels.size() != 2) {
            throw new IOException("Unexpected model label count.");
        }
    }

    private List<String> readLabels(String file) throws IOException {
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(assets.open(file), StandardCharsets.UTF_8))) {
            String label;
            while ((label = reader.readLine()) != null) {
                if (!label.trim().isEmpty()) labels.add(label);
            }
        }
        return labels;
    }

    private Interpreter open(String name) throws IOException {
        AssetFileDescriptor asset = assets.openFd(name);
        try (FileInputStream stream = asset.createInputStream()) {
            ByteBuffer model = stream.getChannel().map(FileChannel.MapMode.READ_ONLY, asset.getStartOffset(), asset.getLength());
            int threads = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            return new Interpreter(model, new Interpreter.Options().setNumThreads(threads));
        }
    }

    List<Score> classify(Bitmap photo) throws IOException {
        if (classifier == null) {
            classifier = open("mangocare_classifier_float32.tflite");
            classificationInput = new ModelInput(CLASSIFICATION_SIZE);
        }
        classificationInput.prepare(photo, true);
        classifier.run(classificationInput.bytes, probabilities);
        List<Score> scores = new ArrayList<>();
        for (int index = 0; index < classificationLabels.size(); index++) {
            scores.add(new Score(classificationLabels.get(index), probabilities[0][index]));
        }
        scores.sort(Comparator.comparingDouble(Score::confidence).reversed());
        return scores;
    }

    List<Detection> detect(Bitmap photo, float threshold) throws IOException {
        if (detector == null) {
            detector = open("mangocare_yolo11n_float32.tflite");
            detectionInput = new ModelInput(DETECTION_SIZE);
        }
        Input input = detectionInput.prepare(photo, false);
        detector.run(detectionInput.bytes, prediction);
        float[][] values = prediction[0];
        List<Detection> candidates = new ArrayList<>();
        for (int index = 0; index < ANCHORS; index++) {
            int best = 0;
            for (int label = 1; label < detectionLabels.size(); label++) {
                if (values[4 + label][index] > values[4 + best][index]) best = label;
            }
            float score = values[4 + best][index];
            if (!(score > threshold)) continue;
            // This export's xywh channels are already in 640×640 input pixels.
            float halfWidth = values[2][index] / 2;
            float halfHeight = values[3][index] / 2;
            if (!(halfWidth > 0 && halfHeight > 0)) continue;
            float x = values[0][index];
            float y = values[1][index];
            candidates.add(new Detection(detectionLabels.get(best), score,
                    new RectF(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)));
        }
        candidates.sort(Comparator.comparingDouble(Detection::confidence).reversed());
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : candidates) {
            boolean suppressed = false;
            for (Detection selection : kept) {
                if (selection.label.equals(candidate.label) && overlap(selection.box, candidate.box) > 0.7f) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) kept.add(candidate);
            if (kept.size() == 300) break;
        }
        List<Detection> results = new ArrayList<>();
        for (Detection detection : kept) {
            RectF box = detection.box;
            RectF normalized = new RectF(
                    clamp((box.left - input.left) / input.width), clamp((box.top - input.top) / input.height),
                    clamp((box.right - input.left) / input.width), clamp((box.bottom - input.top) / input.height));
            if (!normalized.isEmpty()) results.add(new Detection(detection.label, detection.confidence, normalized));
        }
        return results;
    }

    private static float clamp(float number) {
        return Math.max(0, Math.min(1, number));
    }

    private static float[] normalizedChannels() {
        float[] values = new float[256];
        for (int i = 0; i < values.length; i++) values[i] = i / 255f;
        return values;
    }

    private static float overlap(RectF a, RectF b) {
        float width = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float height = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float intersection = width * height;
        return intersection / (a.width() * a.height() + b.width() * b.height() - intersection);
    }

    private static final class ModelInput {
        private final int size;
        private final Bitmap bitmap;
        private final Canvas canvas;
        private final int[] pixels;
        private final ByteBuffer bytes;
        private final FloatBuffer channels;
        private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF bounds = new RectF();

        ModelInput(int size) {
            this.size = size;
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            pixels = new int[size * size];
            bytes = ByteBuffer.allocateDirect(pixels.length * 3 * Float.BYTES).order(ByteOrder.nativeOrder());
            channels = bytes.asFloatBuffer();
        }

        Input prepare(Bitmap photo, boolean crop) {
            int reference = crop ? Math.min(photo.getWidth(), photo.getHeight()) : Math.max(photo.getWidth(), photo.getHeight());
            float scale = (float) size / reference;
            int width = crop ? Math.max(size, (int) (photo.getWidth() * scale)) : Math.max(1, Math.round(photo.getWidth() * scale));
            int height = crop ? Math.max(size, (int) (photo.getHeight() * scale)) : Math.max(1, Math.round(photo.getHeight() * scale));
            int left = crop ? -(int) Math.rint((width - size) / 2.0) : (size - width) / 2;
            int top = crop ? -(int) Math.rint((height - size) / 2.0) : (size - height) / 2;
            bounds.set(left, top, left + width, top + height);
            canvas.drawColor(0xff727272);
            canvas.drawBitmap(photo, null, bounds, filter);
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
            channels.clear();
            for (int pixel : pixels) {
                channels.put(RGB_VALUES[(pixel >> 16) & 0xff]);
                channels.put(RGB_VALUES[(pixel >> 8) & 0xff]);
                channels.put(RGB_VALUES[pixel & 0xff]);
            }
            bytes.rewind();
            return new Input(left, top, width, height);
        }
    }

    @Override
    public void close() {
        if (detector != null) detector.close();
        if (classifier != null) classifier.close();
        if (detectionInput != null) detectionInput.bitmap.recycle();
        if (classificationInput != null) classificationInput.bitmap.recycle();
    }
}
