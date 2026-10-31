# GeoTag Worksite Camera — V1 test build

Native Android field documentation app. This project is being tested on physical devices before any public release.

## Current V1 test scope
- CameraX photo and video capture.
- Photo EXIF date/time and GPS tags when a fix is available.
- Video MP4 location metadata when a GPS fix is available; time, timezone, accuracy, caption and project are stored in the app's local record.
- Photo/video gallery, assigned/unassigned filtering and optional projects.
- Photo detail, video playback, share, map and delete.
- Photo watermark export as a separate copy; the original file is unchanged.
- Watermark options and developer credits.
- Map thumbnails from OpenStreetMap when online, with a fallback when offline.
- A4 PDF reports, portrait/landscape, 1–10 media per page, preview, save and share.
- No ads, no mandatory login and no automatic photo uploads.

## Known V1 test limitations
- A visible watermark is currently exported for photos only. Video watermark burn-in is not included in this test build.
- Video location is written into MP4 location metadata when the platform muxer can process the recording. The app also maintains a local sidecar record. Verify GPS metadata using a media metadata inspector on the target phone.
- Camera zoom uses CameraX's device-reported zoom range. A logical multi-camera phone may change optical lenses automatically; the app cannot force an independent telephoto lens on every device.
- Map tiles require internet access. When unavailable, a placeholder remains and coordinates are preserved.
- PDF output needs device testing with real mixed photo/video selections and large collections.
- This is a debug test APK. Android may show sideload/unknown-source warnings; do not distribute it as a production release.

## Build and install
A push to main starts the Build Android APK workflow in GitHub Actions. Download the geotag-worksite-camera-v1-test-apk artifact from the latest successful run, extract the ZIP and install the APK on your Android device.

## Privacy
No background location service or cloud media upload is implemented. Photos and videos are stored on-device. Location is requested only while the app is in use. Map thumbnails use OpenStreetMap tiles when online and display attribution.
