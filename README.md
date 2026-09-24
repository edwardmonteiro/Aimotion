# AI Motion

**Your body is the controller.**

## V0.3.1 — Play tracking hotfix

This patch fixes a Play-mode tracking freeze seen after switching from the front-camera test modes.

### Changes

- Mirror mode binds Preview + ImageAnalysis.
- Avatar and Play bind ImageAnalysis only.
- Camera sessions are fully unbound before switching lenses or modes.
- Old pose analyzers finish safely before closing.
- Late callbacks from an old camera session are ignored.
- Tracking state is cleared on each mode/lens transition.
- Game and avatar reject stale poses instead of freezing the previous body position.
- Diagnostic panel now shows pose age and active pipeline.

CameraX supports individual use cases independently, so Play does not need a preview surface.

### Test

1. Start in MIRROR.
2. Confirm live skeleton.
3. Tap AVATAR and move continuously.
4. Tap PLAY.
5. Keep moving arms and torso.
6. Pose age should remain under roughly 450 ms while tracking.
7. If tracking is lost, the game should display TRACKING LOST instead of freezing an old avatar.
