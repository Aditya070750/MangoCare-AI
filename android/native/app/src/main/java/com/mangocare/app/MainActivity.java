package com.mangocare.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final int CAMERA = 2;
    private static final int BACKGROUND = 0xffeff5ea;
    private static final int INK = 0xff183527;
    private static final int MUTED = 0xff466052;
    private static final int GREEN = 0xff1d5b3d;
    private static final int CARD = 0xfff9fcf7;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MangoModels models;
    private Uri photoUri;
    private File cameraFile;
    private File selectedCameraFile;
    private Bitmap photo;
    private Preview preview;
    private TextView placeholder;
    private TextView status;
    private TextView thresholdLabel;
    private LinearLayout results;
    private Button gallery;
    private Button camera;
    private Button detect;
    private Button classify;
    private SeekBar threshold;
    private boolean busy;
    private boolean destroyed;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        showScreen();
        if (state != null) {
            String pending = state.getString("pendingCamera");
            if (pending != null) cameraFile = new File(pending);
            String selected = state.getString("selectedCamera");
            if (selected != null) selectedCameraFile = new File(selected);
            threshold.setProgress(state.getInt("threshold", 24));
            String uri = state.getString("photo");
            if (uri != null) loadPhoto(Uri.parse(uri), selectedCameraFile);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (cameraFile != null) state.putString("pendingCamera", cameraFile.getAbsolutePath());
        if (selectedCameraFile != null) state.putString("selectedCamera", selectedCameraFile.getAbsolutePath());
        if (photoUri != null) state.putString("photo", photoUri.toString());
        state.putInt("threshold", threshold.getProgress());
    }

    private void showScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(content);
        TextView title = text("MangoCare AI", 34, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);
        content.addView(text("Check a mango leaf from a photo.", 16, MUTED));

        FrameLayout image = new FrameLayout(this);
        image.setBackground(rounded(0xffdeeadc, 22));
        image.setClipToOutline(true);
        preview = new Preview();
        image.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        placeholder = text("Choose or take a photo", 17, MUTED);
        placeholder.setGravity(Gravity.CENTER);
        image.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, dp(258));
        imageParams.topMargin = dp(20);
        content.addView(image, imageParams);

        gallery = button("Choose photo", false);
        camera = button("Take photo", false);
        gallery.setOnClickListener(view -> openGallery());
        camera.setOnClickListener(view -> openCamera());
        addPair(content, gallery, camera);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(15), dp(13), dp(15), dp(15));
        tools.setBackground(rounded(CARD, 18));
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(-1, -2);
        toolsParams.topMargin = dp(14);
        content.addView(tools, toolsParams);
        thresholdLabel = text("Detection confidence threshold: 25%", 14, MUTED);
        tools.addView(thresholdLabel);
        threshold = new SeekBar(this);
        threshold.setContentDescription("Detection confidence threshold");
        threshold.setMax(98);
        threshold.setProgress(24);
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                thresholdLabel.setText("Detection confidence threshold: " + (value + 1) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        tools.addView(threshold);
        detect = button("Detect regions", true);
        classify = button("Classify photo", true);
        detect.setOnClickListener(view -> scan(true));
        classify.setOnClickListener(view -> scan(false));
        addPair(tools, detect, classify);

        status = text("Detection draws boxes. Classification labels the whole photo.", 14, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(14), 0, dp(8));
        content.addView(status);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results);
        setContentView(scroll);
        updateControls();
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK);
        } catch (RuntimeException error) {
            status.setText("No photo picker is available on this device.");
        }
    }

    private void openCamera() {
        try {
            File directory = new File(getCacheDir(), "camera");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
            cameraFile = File.createTempFile("leaf-", ".jpg", directory);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".photos", cameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.setClipData(ClipData.newRawUri("Camera photo", uri));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, CAMERA);
        } catch (IOException | RuntimeException error) {
            delete(cameraFile);
            cameraFile = null;
            status.setText("Could not open the camera. You can choose a saved photo.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            loadPhoto(uri, null);
        }
        if (request == CAMERA && cameraFile != null) {
            File captured = cameraFile;
            cameraFile = null;
            if (result == RESULT_OK) {
                loadPhoto(FileProvider.getUriForFile(this, getPackageName() + ".photos", captured), captured);
            } else {
                delete(captured);
            }
        }
    }

    private void loadPhoto(Uri uri, File newCameraFile) {
        status.setText("Opening photo…");
        busy = true;
        updateControls();
        worker.execute(() -> {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float scale = Math.min(1f, 1600f / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                });
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (selectedCameraFile != null && !selectedCameraFile.equals(newCameraFile)) delete(selectedCameraFile);
                    selectedCameraFile = newCameraFile;
                    photoUri = uri;
                    photo = bitmap;
                    preview.setImageBitmap(bitmap);
                    preview.setBoxes(Collections.emptyList());
                    placeholder.setVisibility(View.GONE);
                    results.removeAllViews();
                    finishWork("Photo ready. Choose a scan.");
                });
            } catch (IOException | RuntimeException error) {
                if (newCameraFile != null && !newCameraFile.equals(selectedCameraFile)) delete(newCameraFile);
                runOnUiThread(() -> finishWork("Could not open this image. Choose another photo."));
            }
        });
    }

    private void scan(boolean detection) {
        if (photo == null || busy) return;
        Bitmap input = photo;
        float confidence = (threshold.getProgress() + 1) / 100f;
        busy = true;
        results.removeAllViews();
        preview.setBoxes(Collections.emptyList());
        status.setText(detection ? "Looking for regions…" : "Classifying photo…");
        updateControls();
        worker.execute(() -> {
            try {
                if (models == null) models = new MangoModels(getAssets());
                if (detection) {
                    List<MangoModels.Detection> found = models.detect(input, confidence);
                    runOnUiThread(() -> showDetections(found));
                } else {
                    List<MangoModels.Score> scores = models.classify(input);
                    runOnUiThread(() -> showScores(scores));
                }
            } catch (IOException | RuntimeException | LinkageError error) {
                android.util.Log.e("MangoCare", "Model inference failed", error);
                runOnUiThread(() -> finishWork("The scan could not finish. Try again with a different photo."));
            }
        });
    }

    private void showDetections(List<MangoModels.Detection> found) {
        if (destroyed) return;
        preview.setBoxes(found);
        if (found.isEmpty()) {
            finishWork("No matches at this threshold. Try a clearer photo or lower the threshold.");
            return;
        }
        finishWork(found.size() + (found.size() == 1 ? " region detected." : " regions detected."));
        for (MangoModels.Detection item : found) addResult(item.label(), item.confidence());
    }

    private void showScores(List<MangoModels.Score> scores) {
        if (destroyed) return;
        finishWork("Prediction: " + scores.get(0).label() + ". Use one leaf per photo.");
        for (MangoModels.Score score : scores) addResult(score.label(), score.confidence());
    }

    private void addResult(String label, float confidence) {
        TextView row = text(label + "   " + percent(confidence), 16, INK);
        row.setBackground(rounded(CARD, 12));
        row.setPadding(dp(13), dp(10), dp(13), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(5);
        results.addView(row, params);
    }

    private void finishWork(String message) {
        if (destroyed) return;
        busy = false;
        status.setText(message);
        updateControls();
    }

    private void updateControls() {
        gallery.setEnabled(!busy);
        camera.setEnabled(!busy);
        threshold.setEnabled(!busy);
        boolean canScan = !busy && photo != null;
        detect.setEnabled(canScan);
        classify.setEnabled(canScan);
        detect.setAlpha(canScan ? 1f : 0.45f);
        classify.setAlpha(canScan ? 1f : 0.45f);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.execute(() -> { if (models != null) models.close(); });
        worker.shutdown();
        if (isFinishing()) {
            delete(cameraFile);
            delete(selectedCameraFile);
        }
        super.onDestroy();
    }

    private void addPair(LinearLayout content, Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, -2, 1);
        firstParams.setMarginEnd(dp(8));
        row.addView(first, firstParams);
        row.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(9);
        content.addView(row, rowParams);
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(primary ? 0x33ffffff : 0x221d5b3d),
                rounded(primary ? GREEN : 0xffe1eede, 14), null));
        button.setMinHeight(dp(48));
        button.setPadding(dp(7), dp(6), dp(7), dp(6));
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String percent(float confidence) {
        return String.format(Locale.getDefault(), "%.1f%%", confidence * 100);
    }

    private static void delete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            android.util.Log.w("MangoCare", "Could not remove temporary camera photo.");
        }
    }

    private final class Preview extends ImageView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<MangoModels.Detection> boxes = Collections.emptyList();

        Preview() {
            super(MainActivity.this);
            setContentDescription("Selected mango leaf photo");
            setScaleType(ScaleType.FIT_CENTER);
        }

        void setBoxes(List<MangoModels.Detection> boxes) {
            this.boxes = boxes;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getDrawable() == null || boxes.isEmpty()) return;
            RectF bounds = new RectF(getDrawable().getBounds());
            getImageMatrix().mapRect(bounds);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(11));
            paint.setStrokeWidth(dp(2));
            for (MangoModels.Detection item : boxes) {
                RectF box = item.box();
                float left = bounds.left + bounds.width() * box.left;
                float top = bounds.top + bounds.height() * box.top;
                float right = bounds.left + bounds.width() * box.right;
                float bottom = bounds.top + bounds.height() * box.bottom;
                String label = item.label() + " " + percent(item.confidence());
                float tagWidth = paint.measureText(label) + dp(8);
                float tagX = Math.max(0, Math.min(left, getWidth() - tagWidth));
                float tagY = Math.max(top - dp(18), bounds.top);
                int color = switch (item.label()) {
                    case "Anthracnose" -> 0xffffd371;
                    case "Powdery Mildew" -> 0xffd9baff;
                    default -> 0xff9affb5;
                };
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(left, top, right, bottom, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(tagX, tagY, tagX + tagWidth, tagY + dp(18), paint);
                paint.setColor(INK);
                canvas.drawText(label, tagX + dp(4), tagY + dp(13), paint);
            }
        }
    }
}
