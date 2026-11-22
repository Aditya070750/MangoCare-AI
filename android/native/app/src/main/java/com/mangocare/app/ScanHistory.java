package com.mangocare.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Call from the scan worker so saves and reads run in order. */
final class ScanHistory {
    private static final int MAX_ENTRIES = 24;
    private static final int THUMBNAIL_SIZE = 180;
    private static final Object FILE_LOCK = new Object();
    private final AtomicFile file;

    record Entry(String caption, Bitmap thumbnail) {}

    ScanHistory(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "scan-history.json"));
    }

    void add(String caption, Bitmap photo) throws IOException, JSONException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        float ratio = Math.min(1f, (float) THUMBNAIL_SIZE / Math.max(photo.getWidth(), photo.getHeight()));
        Bitmap thumbnail = Bitmap.createScaledBitmap(photo, Math.max(1, Math.round(photo.getWidth() * ratio)),
                Math.max(1, Math.round(photo.getHeight() * ratio)), true);
        try {
            if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 76, jpeg)) {
                throw new IOException("Could not encode thumbnail.");
            }
        } finally {
            if (thumbnail != photo) thumbnail.recycle();
        }
        JSONObject newest = new JSONObject()
                .put("caption", caption)
                .put("thumbnail", Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP));
        synchronized (FILE_LOCK) {
            JSONArray previous = read();
            JSONArray entries = new JSONArray().put(newest);
            for (int i = 0; i < previous.length() && entries.length() < MAX_ENTRIES; i++) {
                JSONObject entry = previous.optJSONObject(i);
                if (entry != null) entries.put(entry);
            }
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(entries.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(stream);
            } catch (IOException | RuntimeException error) {
                file.failWrite(stream);
                throw error;
            }
        }
    }

    List<Entry> entries() throws IOException, JSONException {
        JSONArray records;
        synchronized (FILE_LOCK) {
            records = read();
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < records.length() && entries.size() < MAX_ENTRIES; i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            String caption = record.optString("caption");
            if (caption.isEmpty()) continue;
            try {
                byte[] encoded = Base64.decode(record.optString("thumbnail"), Base64.NO_WRAP);
                Bitmap thumbnail = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
                if (thumbnail != null) entries.add(new Entry(caption, thumbnail));
            } catch (IllegalArgumentException ignored) {
                // Skip an unreadable thumbnail and keep the rest of the history.
            }
        }
        return entries;
    }

    private JSONArray read() throws IOException, JSONException {
        try {
            return new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException missing) {
            return new JSONArray();
        }
    }
}
