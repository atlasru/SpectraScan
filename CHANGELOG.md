# Changelog

SpectraScan is under rapid development. This file summarizes major behavior changes rather than every internal commit.

## 0.10.0 — SkyWatch

- Added experimental `SKY` target filter.
- Added high-resolution small-motion analysis profile.
- Added `UNKNOWN` label for unclassified motion targets.
- Added SkyWatch-specific YOLO class filtering for aircraft/birds/kites.
- Reduced detector rate when the sky scene is idle.
- Added fixed-camera-oriented motion behavior.
- Disabled bright-region tracking in SkyWatch to reduce false sky targets.

## 0.9.2 — Lock Engine groundwork

- Added dedicated Lock Engine foundation.
- Added predicted target state and reacquisition planning.
- Prepared target-ID rebinding for lost/reappearing targets.

## 0.9.1 — Tracking recovery

- Made YOLO the authoritative source for semantic labels.
- Prevented flow tracking from creating semantic identities on its own.
- Added hard TTL for unvalidated semantic tracks.
- Added early YOLO wake-up when local tracking confidence falls.
- Tightened association by label, IoU and target distance.
- Reduced template drift and unrealistic box jumps.
- Kept the performance improvements introduced in 0.9.0.

## 0.9.0 — Hybrid tracking / performance

- Added local semantic-flow tracking between YOLO passes.
- Added constant-velocity Kalman prediction.
- Added adaptive YOLO scheduling.
- Reused ONNX input buffers and image preprocessing memory.
- Reduced ONNX CPU worker count for sustained mobile efficiency.

## 0.8.x — Motion tracking

- Added independent motion detection channel.
- Added global camera-motion compensation.
- Added motion targets for small moving objects.
- Separated motion detection from normal semantic filters.
- Added motion toggle and `M-LOCK` behavior.
- Fixed portrait/landscape tracking coordinate synchronization.

## 0.7.x — Interface and zoom

- Moved advanced controls into a side settings panel.
- Added compact bottom quick controls.
- Added two-finger pinch zoom.
- Read zoom limits from CameraX where available.
- Added quick filter access.
- Fixed settings close-button safe-area behavior.

## 0.6.x — Camera processing

- Added camera zoom and exposure compensation.
- Added digital brightness gain.
- Added B/W mode.
- Added low-light warning and adaptive detector throttling.
- Added automatic Night Vision.
- Added Camera2 sharpen/noise-reduction options.
- Added preview stabilization where supported.

## 0.5.x — Motion HUD

- Removed decorative thermal/night/sonar modes.
- Added motion trails.
- Added motion lock.
- Added movement vectors and trail controls.
- Fixed orientation-related CameraX lifecycle crashes.

## 0.4.x — YOLO

- Replaced generic ML Kit object categories with YOLO11n COCO detection.
- Added ONNX Runtime Android.
- Added real semantic classes such as person, car, dog, cat, airplane and cell phone.
- Added NMS and per-class confidence handling.

## 0.3.x — Tracking foundation

- Added detection confirmation across multiple frames.
- Added confidence, geometry and false-positive filtering.
- Added target filters and stable target IDs.
- Added early hybrid tracking foundation.

## Earlier prototypes

Initial versions established CameraX preview, tactical HUD, basic object detection, target lock and Android CI builds.
