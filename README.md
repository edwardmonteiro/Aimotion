# AI Motion

**Your body is the controller.**

AI Motion is a local-first Android experiment that turns a phone camera into a body-controlled game system and renders games on an external TV over USB-C/HDMI.

## V0.1 — Motion Foundation

- Android native / Kotlin
- CameraX back-camera preview
- frame analysis + FPS diagnostics
- external-display detection with Android Presentation
- separate phone control screen and TV game screen
- normalized body-pose data model
- reusable MotionEngine events
- Goalkeeper prototype renderer
- GitHub Actions debug APK build

No cloud backend is required.

## Architecture

CameraX -> FrameAnalyzer -> PoseEstimator -> BodyPose -> MotionEngine -> Game API -> External TV Presentation

## Current milestone

The camera and HDMI/display pipeline are wired now. Pose inference is isolated behind PoseEstimator so MediaPipe can be added without coupling game code to the vision stack.

## Build

Recommended:

- JDK 17
- Android SDK 37
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0

Open in Android Studio, or run:

```bash
gradle :app:assembleDebug
```

GitHub Actions builds a debug APK on every push.

## Next milestone

1. MediaPipe Pose Landmarker on-device.
2. 33 body landmarks.
3. Player calibration.
4. Gesture thresholds.
5. Body silhouette segmentation.
6. Real Goalkeeper collisions.
7. Shadow Boxer.
8. Body Ninja.

## Privacy

The design target is local processing. Camera frames should not leave the device.
