# Android Setup

This project now includes an Android module at `androidApp/`. Build currently requires a local Android SDK.

## 1) Install Android SDK (recommended on macOS)

### Option A: Android Studio
- Install Android Studio.
- Open it and install:
  - Android SDK
  - Android SDK Platform Tools
  - Android 34 SDK Platform (or newer)

### Option B: Command line
- Install via Homebrew:
  - `brew install --cask android-commandlinetools`
- Use `sdkmanager` to install the matching packages for your target (example shown below).

## 2) Point the project at SDK

Run one of these:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./scripts/setup-android-env.sh
```

or create `local.properties` manually:

```text
sdk.dir=/Users/<you>/Library/Android/sdk
```

## 3) Build Android app

```bash
./gradlew :androidApp:assembleDebug
```

If you see dependency errors from Gradle, run from project root so the Android Gradle Plugin can resolve `local.properties`.

## 4) Verify shared core still compiles

```bash
./gradlew :shared-core:compileKotlinJvm :shared-core:compileCommonMainKotlinMetadata
```
