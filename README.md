# AI Motion

**Your body is the controller.**

AI Motion is a local-first Android experiment that turns a phone camera into a body-controlled game system and renders games on an external TV over USB-C/HDMI.

## V0.2 — Real Body Tracking

V0.2 replaces the placeholder pose adapter with on-device ML Kit Pose Detection.

Current capabilities:

- Android native / Kotlin
- CameraX rear-camera capture
- bundled local pose model
- 33 body landmarks
- live skeleton overlay
- automatic body calibration
- recalibration button
- jump / crouch / lean / punch / kick events
- live FPS and inference latency diagnostics
- external display detection
- separate phone diagnostics and TV game renderer
- Goalkeeper reacts to the player's tracked body envelope
- GitHub Actions debug APK build

No cloud backend is required for pose inference.

## Runtime

CameraX -> ML Kit Pose -> 33 landmarks -> BodyPose -> MotionEngine -> Goalkeeper -> USB-C/HDMI TV

## How to test

1. Install the latest Actions artifact.
2. Put the phone in landscape.
3. Stand far enough away to show shoulders, hips, knees and ankles.
4. Wait for Calibration: READY.
5. Connect USB-C/HDMI.
6. Move left and right. The goalkeeper skeleton should follow you.
7. Try reaching toward incoming balls.
8. Use RECALIBRATE BODY if the neutral standing position changes.

## V0.3 target

- person segmentation and body cutout
- stronger collision geometry
- camera/FOV calibration
- game menu
- Shadow Boxer
- Body Ninja
