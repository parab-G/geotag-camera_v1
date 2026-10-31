# GeoTag Worksite Camera

Native Android app for worksite photo capture with GPS and timestamp metadata. The app is camera-first and local-first: projects and sites are optional, and a photo can be captured and saved without GPS.

## Cloud APK build
Pushes to `main` trigger the GitHub Actions workflow **Build Android APK**. Open the repository's **Actions** tab, select the latest successful run, and download the `geotag-worksite-camera-debug-apk` artifact. Extract the ZIP and install the APK on your Android phone.

## Current milestone
This initial commit establishes the Android project, CameraX preview/capture, location capture, EXIF GPS/time writing, local gallery records, and cloud APK build. Additional gallery, assignment, and PDF report features will be added incrementally. Validate on a physical Android device before relying on it for official site documentation.

## Privacy
Photos and metadata remain on-device. No photo upload or background location tracking is implemented. Location is requested only while the app is in use.
