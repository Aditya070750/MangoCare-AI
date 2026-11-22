# MangoCare Android app

MangoCare uses the two bundled LiteRT models on the phone. Choose an image from the gallery or take a picture with the device camera. Detection marks individual regions and classification gives one label for the photo. The app requires Android 9 or newer and works offline once installed.

## Build

Open `android/native` in Android Studio. This project uses Android SDK 36, SDK Build Tools 36.0.0, JDK 17 and Gradle 9.3.1. Keep the `android/assets` folder next to this project so Gradle can package its models and labels.

To build from the command line with Gradle installed, run the following from `android/native`.

```shell
gradle :app:lintDebug :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The MangoCare Android APK workflow publishes the signed debug build as a GitHub Actions artifact for device testing.
