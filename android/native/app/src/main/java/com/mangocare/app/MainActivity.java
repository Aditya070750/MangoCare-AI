package com.mangocare.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends ComponentActivity {
    private static final int LIMIT = 12;
    private static final String TAG = "MangoCare";
    private record Photo(Uri uri, File cache, String name) {}
    private record Scan(List<MangoModels.Detection> boxes, List<MangoModels.Score> scores, String error) {}
    private record Palette(int background, int surface, int soft, int ink, int muted, int green, int onGreen) {}
    private static final Palette DAY = new Palette(0xffeff5ea, 0xfffbfdf8, 0xffdcebd9, 0xff163525,
            0xff456252, 0xff1d5b3d, Color.WHITE);
    private static final Palette NIGHT = new Palette(0xff101b15, 0xff1a2a21, 0xff263e30, 0xffecf5e8,
            0xffadc5b3, 0xffb2e6a9, 0xff123122);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Photo> photos = new ArrayList<>();
    private final List<Scan> scans = new ArrayList<>();
    private MangoModels models;
    private LiveCamera liveCamera;
    private ScanHistory scanHistory;
    private JSONObject careContent;
    private Palette palette;
    private boolean night;
    private boolean detection = true;
    private boolean live;
    private boolean busy;
    private boolean destroyed;
    private boolean historyOpen;
    private volatile int generation;
    private volatile int confidence = 25;
    private int selected = -1;
    private volatile int previewVersion;
    private File pendingPhoto;
    private Bitmap photoBitmap;
    private String message;

    private LinearLayout queue;
    private HorizontalScrollView queueScroll;
    private LinearLayout result;
    private LinearLayout historyList;
    private View placeholder;
    private BoxPreview preview;
    private TextView empty;
    private TextView count;
    private TextView status;
    private TextView thresholdText;
    private SeekBar threshold;
    private Button gallery;
    private Button camera;
    private Button detectMode;
    private Button classifyMode;
    private Button liveButton;
    private Button run;
    private Button reset;
    private Button historyButton;
    private final OnBackPressedCallback historyBack = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            closeHistory();
        }
    };

    private final androidx.activity.result.ActivityResultLauncher<Intent> galleryPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), reply -> {
                if (reply.getResultCode() != RESULT_OK || reply.getData() == null) return;
                Intent data = reply.getData();
                ClipData selection = data.getClipData();
                if (selection != null) {
                    for (int index = 0; index < selection.getItemCount() && photos.size() < LIMIT; index++) {
                        addPhoto(selection.getItemAt(index).getUri(), null);
                    }
                } else if (photos.size() < LIMIT) {
                    addPhoto(data.getData(), null);
                }
                refreshPhotos();
                if (photos.size() == LIMIT) setStatus("Photo limit reached. Reset to start a new batch.");
            });

    private final androidx.activity.result.ActivityResultLauncher<Intent> photoCapture =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), reply -> {
                File file = pendingPhoto;
                pendingPhoto = null;
                if (file == null) return;
                if (reply.getResultCode() == RESULT_OK && photos.size() < LIMIT) {
                    addPhoto(photoUri(file), file);
                    refreshPhotos();
                    selectPhoto(photos.size() - 1, false);
                } else {
                    delete(file);
                }
            });

    private final androidx.activity.result.ActivityResultLauncher<String> cameraPermissionForLive =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && detection && !busy) startLive();
                else if (!granted) setStatus("Allow camera access to use live detection.");
            });

    private final androidx.activity.result.ActivityResultLauncher<String> cameraPermissionForPhoto =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && !busy && !live) takePhoto();
                else if (!granted) setStatus("Allow camera access to take a photo, or choose one from the gallery.");
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean systemNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        night = getPreferences(MODE_PRIVATE).getBoolean("night", systemNight);
        liveCamera = new LiveCamera(this, worker);
        scanHistory = new ScanHistory(this);
        getOnBackPressedDispatcher().addCallback(this, historyBack);
        careContent = readCare();
        if (state != null) {
            detection = state.getBoolean("detection", true);
            confidence = state.getInt("confidence", 25);
            String pending = state.getString("pendingPhoto");
            if (pending != null) pendingPhoto = new File(pending);
            ArrayList<String> uris = state.getStringArrayList("photos");
            ArrayList<String> files = state.getStringArrayList("files");
            if (uris != null && files != null && uris.size() == files.size()) {
                for (int i = 0; i < uris.size(); i++) {
                    addPhoto(Uri.parse(uris.get(i)), files.get(i).isEmpty() ? null : new File(files.get(i)));
                }
            }
            selected = Math.min(state.getInt("selected", -1), photos.size() - 1);
        }
        showScreen();
        if (!photos.isEmpty()) selectPhoto(Math.max(0, selected), false);
        if (state != null && state.getBoolean("historyOpen")) openHistory();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("detection", detection);
        state.putInt("confidence", confidence);
        state.putInt("selected", selected);
        state.putBoolean("historyOpen", historyOpen);
        if (pendingPhoto != null) state.putString("pendingPhoto", pendingPhoto.getAbsolutePath());
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> files = new ArrayList<>();
        for (Photo photo : photos) {
            uris.add(photo.uri().toString());
            files.add(photo.cache() == null ? "" : photo.cache().getAbsolutePath());
        }
        state.putStringArrayList("photos", uris);
        state.putStringArrayList("files", files);
    }

    private void showScreen() {
        palette = night ? NIGHT : DAY;
        tintLegacySystemBars();
        var systemBars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        systemBars.setAppearanceLightStatusBars(!night);
        systemBars.setAppearanceLightNavigationBars(!night);
        ScrollView scroll = screenScroll();
        LinearLayout content = column();
        content.setPadding(dp(18), dp(14), dp(18), dp(22));
        scroll.addView(content);
        LinearLayout top = row();
        LinearLayout title = column();
        title.addView(heading("MangoCare AI", 26));
        title.addView(text("Mango leaf scanner", 14, palette.muted));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        historyButton = button("History", false);
        historyButton.setOnClickListener(view -> openHistory());
        top.addView(historyButton);
        Button theme = button(night ? "☀" : "☾", false);
        String themeLabel = night ? "Day mode" : "Night mode";
        theme.setContentDescription(themeLabel);
        theme.setTooltipText(themeLabel);
        theme.setMinimumWidth(0);
        theme.setMinWidth(0);
        theme.setTextSize(21);
        theme.setOnClickListener(view -> {
            night = !night;
            getPreferences(MODE_PRIVATE).edit().putBoolean("night", night).apply();
            showScreen();
        });
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(dp(46), -2);
        themeParams.setMarginStart(dp(7));
        top.addView(theme, themeParams);
        content.addView(top);

        LinearLayout mode = row();
        detectMode = button("Detection", detection);
        classifyMode = button("Classification", !detection);
        detectMode.setSelected(detection);
        classifyMode.setSelected(!detection);
        detectMode.setOnClickListener(view -> changeMode(true));
        classifyMode.setOnClickListener(view -> changeMode(false));
        addPair(mode, detectMode, classifyMode);
        addBlock(content, mode, 14);

        FrameLayout image = new FrameLayout(this);
        image.setBackground(rounded(palette.soft, 22));
        image.setClipToOutline(true);
        preview = new BoxPreview();
        image.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout prompt = column();
        prompt.setGravity(Gravity.CENTER);
        MangoLeafView leaf = new MangoLeafView(this);
        prompt.addView(leaf, new LinearLayout.LayoutParams(dp(116), dp(116)));
        empty = text("Choose photos or take a picture", 15, palette.ink);
        empty.setGravity(Gravity.CENTER);
        prompt.addView(empty);
        image.addView(prompt, new FrameLayout.LayoutParams(-1, -1));
        placeholder = prompt;
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, dp(250));
        imageParams.topMargin = dp(12);
        content.addView(image, imageParams);

        LinearLayout photoActions = row();
        gallery = button("Add photos", false);
        camera = button("Take photo", false);
        gallery.setOnClickListener(view -> openGallery());
        camera.setOnClickListener(view -> requestCamera(false));
        addPair(photoActions, gallery, camera);
        addBlock(content, photoActions, 9);
        count = text("", 13, palette.muted);
        addBlock(content, count, 8);
        queueScroll = new HorizontalScrollView(this);
        queueScroll.setHorizontalScrollBarEnabled(false);
        queue = row();
        queueScroll.addView(queue);
        addBlock(content, queueScroll, 4);

        LinearLayout controls = column();
        controls.setPadding(dp(13), dp(10), dp(13), dp(12));
        controls.setBackground(rounded(palette.surface, 18));
        thresholdText = text("Minimum confidence · " + confidence + "%", 14, palette.muted);
        controls.addView(thresholdText);
        threshold = new SeekBar(this);
        threshold.setContentDescription("Minimum detection confidence");
        threshold.setMax(98);
        threshold.setProgress(confidence - 1);
        threshold.setProgressTintList(android.content.res.ColorStateList.valueOf(palette.green));
        threshold.setThumbTintList(android.content.res.ColorStateList.valueOf(palette.green));
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                confidence = value + 1;
                thresholdText.setText("Minimum confidence · " + confidence + "%");
                if (reset != null) enable(reset, busy || live || !photos.isEmpty() || confidence != 25);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        controls.addView(threshold);
        liveButton = button(live ? "Stop live detection" : "Start live detection", false);
        liveButton.setOnClickListener(view -> {
            if (live) stopLive(true);
            else requestCamera(true);
        });
        controls.addView(liveButton);
        LinearLayout runRow = row();
        run = button(detection ? "Run detection" : "Classify photos", true);
        run.setOnClickListener(view -> scanPhotos());
        reset = button("Reset", false);
        reset.setOnClickListener(view -> reset());
        addPair(runRow, run, reset);
        addBlock(controls, runRow, 7);
        addBlock(content, controls, 9);
        status = text("", 14, palette.muted);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addBlock(content, status, 11);
        result = column();
        addBlock(content, result, 8);
        setContentView(scroll);
        ViewCompat.requestApplyInsets(scroll);
        renderPhoto();
        refreshPhotos();
        showResult(false);
        setStatus(message != null ? message : modeHint());
    }

    private ScrollView screenScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(palette.background);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        return scroll;
    }

    private void openHistory() {
        if (busy || historyOpen) return;
        if (live) stopLive(false);
        historyOpen = true;
        historyBack.setEnabled(true);
        ScrollView scroll = screenScroll();
        LinearLayout content = column();
        content.setPadding(dp(18), dp(14), dp(18), dp(22));
        scroll.addView(content);
        LinearLayout header = row();
        header.addView(heading("Scan history", 26), new LinearLayout.LayoutParams(0, -2, 1));
        Button back = button("Back to scanner", false);
        back.setOnClickListener(view -> closeHistory());
        header.addView(back);
        content.addView(header);
        LinearLayout list = column();
        historyList = list;
        addBlock(content, list, 14);
        list.addView(text("Loading history…", 15, palette.muted));
        setContentView(scroll);
        ViewCompat.requestApplyInsets(scroll);

        worker.execute(() -> {
            try {
                List<ScanHistory.Entry> entries = scanHistory.entries();
                runOnUiThread(() -> {
                    if (destroyed || historyList != list) return;
                    list.removeAllViews();
                    if (entries.isEmpty()) list.addView(text("No scans yet.", 15, palette.muted));
                    for (ScanHistory.Entry entry : entries) addHistoryEntry(list, entry);
                });
            } catch (IOException | JSONException error) {
                Log.e(TAG, "Could not read scan history", error);
                runOnUiThread(() -> {
                    if (destroyed || historyList != list) return;
                    list.removeAllViews();
                    list.addView(text("Could not open scan history.", 15, palette.muted));
                });
            }
        });
    }

    private void addHistoryEntry(LinearLayout list, ScanHistory.Entry entry) {
        LinearLayout card = row();
        card.setPadding(dp(9), dp(9), dp(12), dp(9));
        card.setBackground(rounded(palette.surface, 16));
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setImageBitmap(entry.thumbnail());
        photo.setBackground(rounded(palette.soft, 10));
        photo.setClipToOutline(true);
        photo.setContentDescription("Scanned photo");
        LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(dp(62), dp(62));
        picture.setMarginEnd(dp(12));
        card.addView(photo, picture);
        card.addView(text(entry.caption(), 16, palette.ink), new LinearLayout.LayoutParams(0, -2, 1));
        addBlock(list, card, 8);
    }

    private void closeHistory() {
        historyOpen = false;
        historyList = null;
        historyBack.setEnabled(false);
        showScreen();
        if (selected >= 0 && photoBitmap == null) selectPhoto(selected, false);
    }

    private String modeHint() {
        return detection ? "Detection marks possible disease and healthy regions." : "For classification, use one leaf per photo.";
    }

    private void changeMode(boolean newDetection) {
        if (detection == newDetection) return;
        if (live) stopLive(false);
        detection = newDetection;
        clearScans();
        message = modeHint();
        showScreen();
        if (selected >= 0 && photoBitmap == null) selectPhoto(selected, false);
    }

    private void openGallery() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            galleryPicker.launch(picker);
        } catch (RuntimeException error) {
            setStatus("No photo picker is available on this device.");
        }
    }

    private void requestCamera(boolean forLive) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (forLive) startLive();
            else takePhoto();
        } else if (forLive) {
            cameraPermissionForLive.launch(Manifest.permission.CAMERA);
        } else {
            cameraPermissionForPhoto.launch(Manifest.permission.CAMERA);
        }
    }

    private void takePhoto() {
        try {
            File directory = new File(getCacheDir(), "camera");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
            pendingPhoto = File.createTempFile("leaf-", ".jpg", directory);
            Uri uri = photoUri(pendingPhoto);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.setClipData(ClipData.newRawUri("Camera photo", uri));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            photoCapture.launch(intent);
        } catch (IOException | RuntimeException error) {
            delete(pendingPhoto);
            pendingPhoto = null;
            setStatus("Could not open the camera. Choose a saved photo instead.");
        }
    }

    private Uri photoUri(File photo) {
        return FileProvider.getUriForFile(this, getPackageName() + ".photos", photo);
    }

    private void addPhoto(Uri uri, File cache) {
        if (uri == null || photos.stream().anyMatch(photo -> photo.uri().equals(uri))) return;
        photos.add(new Photo(uri, cache, cache == null ? "Photo " + (photos.size() + 1) : "Camera " + (photos.size() + 1)));
        scans.add(null);
    }

    private void refreshPhotos() {
        if (selected < 0 && !photos.isEmpty()) {
            selectPhoto(0, false);
            return;
        }
        queue.removeAllViews();
        count.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        queueScroll.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        count.setText(photos.size() + (photos.size() == 1 ? " photo selected" : " photos selected") + " · limit " + LIMIT);
        for (int i = 0; i < photos.size(); i++) {
            int index = i;
            Button item = button(photos.get(i).name(), i == selected);
            item.setSelected(i == selected);
            item.setOnClickListener(view -> selectPhoto(index, true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginEnd(dp(6));
            queue.addView(item, params);
        }
        updateControls();
    }

    private void selectPhoto(int index, boolean userSelected) {
        if (index < 0 || index >= photos.size() || live) return;
        if (selected == index && photoBitmap != null) return;
        selected = index;
        photoBitmap = null;
        int version = ++previewVersion;
        Uri uri = photos.get(index).uri();
        renderPhoto();
        showResult(false);
        refreshPhotos();
        worker.execute(() -> {
            if (version != previewVersion) return;
            Bitmap bitmap = null;
            try {
                bitmap = decode(uri);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Photo preview failed", error);
            }
            Bitmap loaded = bitmap;
            runOnUiThread(() -> {
                if (destroyed || version != previewVersion || live) {
                    if (loaded != null) loaded.recycle();
                    return;
                }
                photoBitmap = loaded;
                renderPhoto();
                if (loaded == null && !busy) setStatus("Could not open this photo.");
                else if (userSelected && !busy) setStatus("Showing " + photos.get(index).name() + ".");
            });
        });
    }

    private Bitmap decode(Uri uri) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, ignored) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            float scale = Math.min(1f, 1600f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
        });
    }

    private MangoModels models() throws IOException {
        if (models == null) models = new MangoModels(getAssets());
        return models;
    }

    private void scanPhotos() {
        if (photos.isEmpty() || busy || live) return;
        busy = true;
        int token = ++generation;
        boolean detect = detection;
        float cutoff = confidence / 100f;
        List<Photo> batch = new ArrayList<>(photos);
        int previewIndex = selected;
        Bitmap previewBitmap = photoBitmap;
        clearScans();
        renderPhoto();
        showResult(false);
        setStatus("Scanning " + batch.size() + (batch.size() == 1 ? " photo…" : " photos…"));
        updateControls();
        worker.execute(() -> {
            for (int i = 0; i < batch.size() && token == generation; i++) {
                Scan outcome;
                Bitmap bitmap = null;
                try {
                    bitmap = i == previewIndex && previewBitmap != null ? previewBitmap : decode(batch.get(i).uri());
                    outcome = detect ? new Scan(models().detect(bitmap, cutoff), Collections.emptyList(), null)
                            : new Scan(Collections.emptyList(), models().classify(bitmap), null);
                    if (token == generation) saveHistory(detect, outcome, bitmap);
                } catch (IOException | RuntimeException | LinkageError error) {
                    Log.e(TAG, "Photo scan failed", error);
                    outcome = new Scan(Collections.emptyList(), Collections.emptyList(), "Could not scan this photo.");
                } finally {
                    if (bitmap != null && bitmap != previewBitmap) bitmap.recycle();
                }
                int index = i;
                Scan found = outcome;
                runOnUiThread(() -> finishScan(token, index, batch.size(), found));
            }
        });
    }

    private void saveHistory(boolean detect, Scan scan, Bitmap photo) {
        String caption = "Classification";
        String label;
        if (detect) {
            caption = "Detection";
            label = scan.boxes().isEmpty() ? "No matches" : scan.boxes().get(0).label();
        } else {
            label = scan.scores().get(0).label();
        }
        try {
            scanHistory.add(caption + " · " + label, photo);
        } catch (IOException | JSONException | RuntimeException error) {
            Log.e(TAG, "Could not save scan history", error);
        }
    }

    private void finishScan(int token, int index, int size, Scan scan) {
        if (destroyed || token != generation) return;
        scans.set(index, scan);
        if (index == selected) {
            renderPhoto();
            showResult(true);
        }
        if (index + 1 == size) {
            busy = false;
            setStatus(size == 1 ? "Scan complete." : "Finished " + size + " scans. Select a photo to see its result.");
            updateControls();
        } else {
            setStatus("Scanned " + (index + 1) + " of " + size + " photos.");
        }
    }

    private void startLive() {
        if (live || busy || !detection) return;
        int token = ++generation;
        previewVersion++;
        live = true;
        photoBitmap = null;
        renderPhoto();
        showResult(false);
        refreshPhotos();
        setStatus("Starting live camera…");
        int rotation = preview.getDisplay() == null ? android.view.Surface.ROTATION_0 : preview.getDisplay().getRotation();
        AtomicBoolean pendingPreview = new AtomicBoolean();
        liveCamera.start(rotation, new LiveCamera.Listener() {
            @Override public void onFrame(Bitmap image) {
                if (!pendingPreview.compareAndSet(false, true)) {
                    image.recycle();
                    return;
                }
                try {
                    List<MangoModels.Detection> boxes = models().detect(image, confidence / 100f);
                    runOnUiThread(() -> {
                        try {
                            if (destroyed || token != generation || !live) {
                                image.recycle();
                                return;
                            }
                            preview.setImageBitmap(image);
                            preview.setBoxes(boxes);
                            placeholder.setVisibility(View.GONE);
                            String label = boxes.isEmpty() ? "No matches" : boxes.get(0).label();
                            String summary = "Live · " + label + (boxes.size() > 1 ? " · " + boxes.size() + " regions" : "");
                            if (!summary.contentEquals(status.getText())) status.setText(summary);
                        } finally {
                            pendingPreview.set(false);
                        }
                    });
                } catch (IOException | RuntimeException | LinkageError error) {
                    pendingPreview.set(false);
                    image.recycle();
                    Log.e(TAG, "Live scan failed", error);
                    runOnUiThread(this::onError);
                }
            }

            @Override public void onError() {
                if (!destroyed && token == generation && live) {
                    stopLive(true);
                    setStatus("Live detection could not start. Try a photo scan instead.");
                }
            }
        });
    }

    private void stopLive(boolean restore) {
        if (!live) return;
        generation++;
        live = false;
        liveCamera.stop();
        renderPhoto();
        if (restore) {
            setStatus("Live detection stopped.");
            if (selected >= 0) selectPhoto(selected, false);
            else refreshPhotos();
        }
    }

    private void reset() {
        stopLive(false);
        generation++;
        previewVersion++;
        busy = false;
        selected = -1;
        photoBitmap = null;
        for (Photo photo : photos) delete(photo.cache());
        photos.clear();
        scans.clear();
        threshold.setProgress(24);
        renderPhoto();
        showResult(false);
        refreshPhotos();
        setStatus(modeHint());
    }

    private void clearScans() {
        for (int i = 0; i < scans.size(); i++) scans.set(i, null);
    }

    private void renderPhoto() {
        preview.setImageBitmap(photoBitmap);
        placeholder.setVisibility(photoBitmap == null ? View.VISIBLE : View.GONE);
        empty.setText(live ? "Starting live camera…" : selected >= 0 ? "Opening photo…" : "Choose photos or take a picture");
        Scan scan = selected >= 0 && !live ? scans.get(selected) : null;
        preview.setBoxes(scan == null ? Collections.emptyList() : scan.boxes());
    }

    private void showResult(boolean animate) {
        result.removeAllViews();
        if (live || selected < 0 || scans.get(selected) == null) return;
        Scan scan = scans.get(selected);
        LinearLayout card = column();
        card.setBackground(rounded(palette.surface, 18));
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        result.addView(card);
        if (scan.error() != null) {
            card.addView(text(scan.error(), 15, palette.ink));
        } else if (detection) {
            int size = scan.boxes().size();
            card.addView(heading(size == 0 ? "No matches" : size + (size == 1 ? " region detected" : " regions detected"), 18));
            if (size == 0) card.addView(text("Try a clearer photo or lower the confidence threshold.", 14, palette.muted));
            for (MangoModels.Detection item : scan.boxes()) addScore(card, item.label(), item.confidence());
        } else {
            String label = scan.scores().get(0).label();
            card.addView(heading("Prediction · " + label, 18));
            for (MangoModels.Score score : scan.scores()) addScore(card, score.label(), score.confidence());
            addCare(card, label);
        }
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            card.setCameraDistance(dp(1000));
            card.setRotationX(-9);
            card.setAlpha(0.7f);
            card.animate().rotationX(0).alpha(1).setDuration(230).start();
        }
    }

    private void addScore(LinearLayout card, String label, float score) {
        TextView row = text(label + "   " + percent(score), 15, palette.ink);
        row.setPadding(0, dp(6), 0, 0);
        card.addView(row);
    }

    private JSONObject readCare() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getResources().openRawResource(R.raw.leaf_care), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            return new JSONObject(json.toString());
        } catch (Exception error) {
            Log.e(TAG, "Could not load care guidance", error);
            return new JSONObject();
        }
    }

    private void addCare(LinearLayout card, String label) {
        JSONObject care = careContent.optJSONObject("care");
        JSONObject guidance = care == null ? null : care.optJSONObject(label);
        if (guidance == null) return;
        addBlock(card, text(guidance.optString("description"), 14, palette.muted), 9);
        displayTips(card, guidance, "Precautions", "Precautions");
        boolean healthy = label.equals("Healthy");
        displayTips(card, guidance, healthy ? "Care" : "Treatment", healthy ? "Routine care" : "Treatment");
        addBlock(card, text(careContent.optString("note"), 13, palette.muted), 10);
        TextView source = text("Read the UF/IFAS mango care guide", 14, palette.green);
        source.setPadding(0, dp(8), 0, dp(8));
        source.setPaintFlags(source.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        source.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(careContent.optString("source"))));
            } catch (RuntimeException error) {
                setStatus("No browser is available to open the care guide.");
            }
        });
        card.addView(source);
    }

    private void displayTips(LinearLayout card, JSONObject guidance, String key, String title) {
        JSONArray items = guidance.optJSONArray(key);
        if (items == null) return;
        addBlock(card, heading(title, 15), 10);
        for (int i = 0; i < items.length(); i++) addBlock(card, text("• " + items.optString(i), 14, palette.ink), 3);
    }

    private void updateControls() {
        enable(gallery, !busy && !live && photos.size() < LIMIT);
        enable(camera, !busy && !live && photos.size() < LIMIT);
        enable(detectMode, !busy);
        enable(classifyMode, !busy);
        enable(historyButton, !busy);
        enable(run, !busy && !live && !photos.isEmpty());
        enable(reset, busy || live || !photos.isEmpty() || confidence != 25);
        for (int i = 0; i < queue.getChildCount(); i++) enable((Button) queue.getChildAt(i), !live && !busy);
        threshold.setVisibility(detection ? View.VISIBLE : View.GONE);
        thresholdText.setVisibility(detection ? View.VISIBLE : View.GONE);
        threshold.setEnabled(!busy);
        liveButton.setVisibility(detection ? View.VISIBLE : View.GONE);
        liveButton.setText(live ? "Stop live detection" : "Start live detection");
        enable(liveButton, !busy);
    }

    private void setStatus(String value) {
        message = value;
        status.setText(value);
    }

    @SuppressWarnings("deprecation")
    private void tintLegacySystemBars() {
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(palette.background);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (live) stopLive(true);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        generation++;
        previewVersion++;
        liveCamera.stop();
        worker.execute(() -> { if (models != null) models.close(); });
        worker.shutdown();
        if (isFinishing()) {
            delete(pendingPhoto);
            for (Photo photo : photos) delete(photo.cache());
        }
        super.onDestroy();
    }

    private void enable(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private Button button(String label, boolean active) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(active ? palette.onGreen : palette.ink);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(active ? 0x33ffffff : 0x271d5b3d),
                rounded(active ? palette.green : palette.soft, 14), null));
        view.setMinHeight(dp(46));
        view.setPadding(dp(9), dp(6), dp(9), dp(6));
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void addPair(LinearLayout row, View first, View second) {
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMarginEnd(dp(8));
        row.addView(first, left);
        row.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void addBlock(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private TextView heading(String value, int size) {
        TextView view = text(value, size, palette.ink);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
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
        if (file != null && file.exists() && !file.delete()) Log.w(TAG, "Could not remove cached photo.");
    }

    private final class BoxPreview extends ImageView {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private List<MangoModels.Detection> boxes = Collections.emptyList();

        BoxPreview() {
            super(MainActivity.this);
            setContentDescription("Mango leaf preview");
            setScaleType(ScaleType.FIT_CENTER);
        }

        void setBoxes(List<MangoModels.Detection> found) {
            boxes = found;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getDrawable() == null || boxes.isEmpty()) return;
            bounds.set(getDrawable().getBounds());
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
                paint.setColor(0xff163525);
                canvas.drawText(label, tagX + dp(4), tagY + dp(13), paint);
            }
        }
    }
}
