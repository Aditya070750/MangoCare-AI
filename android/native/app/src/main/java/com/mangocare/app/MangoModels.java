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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MangoModels implements AutoCloseable {
    private static final int DETECTION_SIZE = 640;
    private static final int CLASSIFICATION_SIZE = 224;
    private static final int ANCHORS = 8400;
    private final AssetManager assets;
    private final List<String> detectionLabels;
    private final List<String> classificationLabels;
    private Interpreter detector;
    private Interpreter classifier;

    record Detection(String label, float confidence, RectF box) {}
    record Score(String label, float confidence) {}
    private record Input(ByteBuffer values, int left, int top, int width, int height) {}

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
            return new Interpreter(model, new Interpreter.Options().setNumThreads(2));
        }
    }

    List<Score> classify(Bitmap photo) throws IOException {
        if (classifier == null) classifier = open("mangocare_classifier_float32.tflite");
        float[][] probabilities = new float[1][classificationLabels.size()];
        classifier.run(prepare(photo, CLASSIFICATION_SIZE, true).values, probabilities);
        List<Score> scores = new ArrayList<>();
        for (int index = 0; index < classificationLabels.size(); index++) {
            scores.add(new Score(classificationLabels.get(index), probabilities[0][index]));
        }
        scores.sort(Comparator.comparingDouble(Score::confidence).reversed());
        return scores;
    }

    List<Detection> detect(Bitmap photo, float threshold) throws IOException {
        if (detector == null) detector = open("mangocare_yolo11n_float32.tflite");
        Input input = prepare(photo, DETECTION_SIZE, false);
        float[][][] prediction = new float[1][4 + detectionLabels.size()][ANCHORS];
        detector.run(input.values, prediction);
        float[][] values = prediction[0];
        List<Detection> candidates = new ArrayList<>();
        for (int index = 0; index < ANCHORS; index++) {
            int best = 0;
            for (int label = 1; label < detectionLabels.size(); label++) {
                if (values[4 + label][index] > values[4 + best][index]) best = label;
            }
            float score = values[4 + best][index];
            if (!(score > threshold)) continue;
            float halfWidth = values[2][index] * DETECTION_SIZE / 2;
            float halfHeight = values[3][index] * DETECTION_SIZE / 2;
            if (!(halfWidth > 0 && halfHeight > 0)) continue;
            float x = values[0][index] * DETECTION_SIZE;
            float y = values[1][index] * DETECTION_SIZE;
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

    private static float overlap(RectF a, RectF b) {
        float width = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float height = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float intersection = width * height;
        return intersection / (a.width() * a.height() + b.width() * b.height() - intersection);
    }

    private static Input prepare(Bitmap photo, int size, boolean crop) {
        int reference = crop ? Math.min(photo.getWidth(), photo.getHeight()) : Math.max(photo.getWidth(), photo.getHeight());
        float scale = (float) size / reference;
        int width = crop ? (int) (photo.getWidth() * scale) : Math.round(photo.getWidth() * scale);
        int height = crop ? (int) (photo.getHeight() * scale) : Math.round(photo.getHeight() * scale);
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (crop) {
            width = Math.max(size, width);
            height = Math.max(size, height);
        }
        int left = crop ? -(int) Math.rint((width - size) / 2.0) : (size - width) / 2;
        int top = crop ? -(int) Math.rint((height - size) / 2.0) : (size - height) / 2;
        Bitmap input = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(input);
        canvas.drawColor(0xff727272);
        canvas.drawBitmap(photo, null, new RectF(left, top, left + width, top + height), new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pixels = new int[size * size];
        input.getPixels(pixels, 0, size, 0, 0, size, size);
        input.recycle();
        ByteBuffer bytes = ByteBuffer.allocateDirect(pixels.length * 3 * Float.BYTES).order(ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            bytes.putFloat(((pixel >> 16) & 0xff) / 255f);
            bytes.putFloat(((pixel >> 8) & 0xff) / 255f);
            bytes.putFloat((pixel & 0xff) / 255f);
        }
        bytes.rewind();
        return new Input(bytes, left, top, width, height);
    }

    @Override
    public void close() {
        if (detector != null) detector.close();
        if (classifier != null) classifier.close();
    }
}
