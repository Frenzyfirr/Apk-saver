# APK Saver

An offline Android app that saves and shares the APK files of apps installed on your phone.

- **Fully offline**: no internet permission and no third-party libraries. It uses only the Android framework.
- **Lists every installed app** with its icon, version and size. You can search, sort by name, size or last update, and choose whether to show system apps.
- **Save to Downloads**: one tap saves to `Download/APK Saver/`.
- **Save as…**: lets you pick the location and name with the system file picker.
- **Save many**: long-press to select several apps, then save them to Downloads or to any folder you choose.
- **Share**: sends APKs through Bluetooth, Quick Share/Nearby Share, a file manager, email and so on. For single APKs the installed file is shared directly, so nothing is copied.
- **Split APKs (App Bundles)**: these are exported as a `.apks` archive that holds `base.apk` and every `split_*.apk`. Install it with a split-APK installer such as SAI. Normal apps export as a plain `.apk`.
- Works on Android 5.0 (API 21) through Android 15+, follows the system light/dark theme (Android 10+), and lets you cancel a save while it's running.

## Build

**Option A: GitHub (no local setup)**
1. Push this folder to a GitHub repository.
2. The included workflow (`.github/workflows/build.yml`) builds the APK automatically.
3. Open the **Actions** tab, select the latest run, and download the **ApkSaver** artifact.

**Option B: Android Studio**
1. In Android Studio, choose File → Open and select this folder.
2. Choose Build → Build APK(s), or press Run with your phone connected.

**Option C: command line** (needs the Android SDK and `ANDROID_HOME` set)
```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs without extra steps. If you plan to distribute the app, add your own `signingConfig` in `app/build.gradle`.

## Notes
- The app uses `QUERY_ALL_PACKAGES` so it can see every app on Android 11+. That's fine for sideloading, but Google Play limits which apps may use this permission.
- `WRITE_EXTERNAL_STORAGE` is only requested on Android 6–9, when you save to Downloads. Newer versions don't need any storage permission.
- Some system apps live in protected partitions or are split. Most can still be read, but a few OEM apps may fail. Any failures are listed after a batch save.

## Code map
| File | Purpose |
|---|---|
| `MainActivity.java` | App list, search/sort, selection, save/share flows, progress dialog |
| `ApkExporter.java` | Finds an app's APK file(s), names the output, writes `.apk` or `.apks` |
| `ShareProvider.java` | Small read-only content provider that serves files for sharing |
