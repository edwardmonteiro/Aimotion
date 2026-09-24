# AI Motion

**Your body is the controller.**

## V0.5.1 — Hit crash hotfix

V0.5.1 fixes a runtime crash triggered by the first in-game impact.

### Root cause
V0.5 introduced haptic feedback but the Android manifest did not declare the VIBRATE permission.

### Fixes
- declares android.permission.VIBRATE
- haptic feedback is guarded and cannot crash gameplay
- ToneGenerator feedback is guarded and optional
- the game remains playable even if sound or vibration fails on a device

V0.5 features remain unchanged:
- adaptive pose smoothing
- short prediction
- capsule collisions
- velocity-based impacts
- perfect hits
- progressive levels
- accuracy and reaction metrics
- physics debug overlay
