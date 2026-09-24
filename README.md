# AI Motion

**Your body is the controller.**

AI Motion is a local-first Android experiment that turns the phone camera into a body-controlled game system.

## V0.3 — Fast Test Modes

This release is focused on validating the experience without requiring a TV.

### Mirror Test
- front camera by default
- live mirrored preview
- 33-point skeleton overlay
- FPS, inference latency and calibration status

### Avatar Test
- camera hidden
- tracked body drives a stylized 2.5D robot
- ML Kit Z landmarks influence limb scale and depth
- works with front or rear camera

### Play Mode
- rear camera automatically selected
- Goalkeeper fills the phone screen
- tracked body controls the in-game body envelope

### Camera switching
A dedicated button switches FRONT / REAR at runtime.

## Runtime

CameraX -> ML Kit Pose -> 33 landmarks -> MotionEngine -> Mirror / Avatar / Game

Everything in the tracking path runs locally on the Android device.

## Test order

1. Open Mirror.
2. Confirm 25-33 tracked points.
3. Move arms, crouch and lean.
4. Open Avatar.
5. Confirm the robot follows shoulders, elbows, wrists, hips, knees and ankles.
6. Switch FRONT / REAR and compare stability.
7. Open Play and test Goalkeeper on the phone.

## Next target

- temporal landmark smoothing
- stronger body collision geometry
- body segmentation / camera cutout
- Body Ninja gameplay
