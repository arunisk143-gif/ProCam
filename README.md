# ProCam - professional Android camera (Kotlin, Camera2)

## What it does
- Live preview with colour filters (Original, Vivid, Warm, Cool, Fade, Sepia, Mono, Noir) and a strength slider.
- Manual mode: ISO and shutter speed sliders.
- Every shot is saved to Pictures/ProCam with no lossy compression:
  - `PROCAM_<time>.dng` - RAW sensor data (needs a device with the RAW capability)
  - `PROCAM_<time>_original.png` - lossless PNG, converted straight from YUV (no JPEG step)
  - `PROCAM_<time>_<filter>.png` - lossless PNG with the chosen filter (only if a filter is selected)
- Filters are non-destructive: the DNG and the original PNG are never modified.

## Build
1. Open this folder in Android Studio (Koala or newer).
2. Let Gradle sync, connect an Android phone (Android 10+), press Run.
3. Test on a real device - the emulator has no RAW support.

## Notes / next steps
- PNG files from 12-50 MP sensors are large and take a few seconds to save.
- Tap-to-focus, histogram, grid lines and exposure-compensation are not included yet.
