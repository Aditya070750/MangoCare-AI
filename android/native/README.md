# MangoCare Android app

MangoCare uses the two bundled LiteRT models on the phone. Add up to 12 gallery or camera photos and tap a photo in the batch to see its result. Detection marks individual regions and can analyze the live camera. Classification gives one label per photo, plus precautions and treatment or routine care. The app requires Android 9 or newer and works offline once installed. Use the theme button to switch between day and night, or Reset to clear the workspace and stop the live camera.

The Android care text comes from the website's `guidance.py`. The app bundles a copy in `app/src/main/res/raw/leaf_care.json` so it is available offline.

History keeps the latest 24 completed photo scans on the device, with one small thumbnail and a short caption per scan. Live video frames are excluded. Reset clears the current scanner workspace while preserving History.

## Build

Open `android/native` in Android Studio. This project uses Android SDK 36, SDK Build Tools 36.0.0, JDK 17 and Gradle 9.3.1. Keep the `android/assets` folder next to this project so Gradle can package its models and labels.

To build from the command line with Gradle installed, run the following from `android/native`.

```shell
gradle :app:lintDebug :app:assembleDebug
```

The signed test APKs are written to `app/build/outputs/apk/debug/`. Use the smaller `app-arm64-v8a-debug.apk` on an ARM64 phone and `app-universal-debug.apk` for other Android devices or emulators. Both builds shrink unused code and resources and include the same offline models.
