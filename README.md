# AI Motion

## V0.6 — REAL ME

REAL ME puts the player's actual segmented camera image inside Body Ninja while keeping pose tracking as the physics controller.

### Pipeline

CameraX
→ ML Kit Pose (every analyzed frame)
→ Pose smoothing + prediction
→ Body physics
→ ML Kit Selfie Segmentation (throttled)
→ transparent player cutout
→ arena compositing

### REAL ME

- local-only person segmentation
- actual camera pixels, not a generated avatar
- transparent background
- player cutout follows the same viewport mapping as collision physics
- hand and foot energy glow
- existing trails, hit particles, sound, haptics and perfect-hit feedback

### Performance choices

Selfie segmentation is bundled on-device. To protect gameplay responsiveness:
- segmentation runs every second analyzed frame
- segmentation input is capped at a 480 px long edge
- pose tracking continues independently

The game remains functional while the segmentation frame is warming up; it falls back to the skeleton controller if a fresh cutout is unavailable.
