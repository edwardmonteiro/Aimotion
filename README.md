# AI Motion

## V0.5.2 — Body viewport fix

This hotfix corrects body placement in Body Ninja.

### Root cause
The game view still applied a fixed horizontal mirror transform even when the rear camera was active. Rendering and collision coordinates could therefore disagree with the actual camera orientation.

### Fix
- front camera mirrors X
- rear camera preserves X
- first reliable torso center becomes a viewport anchor
- avatar starts centered without eliminating later lateral movement
- rendering and collision physics use the same coordinate mapper
- wrist trails reset when a new game starts

V0.5.1 hit-crash safeguards remain in place.
