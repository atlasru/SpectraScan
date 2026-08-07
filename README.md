# SpectraScan

> Experimental real-time computer-vision scanner and target tracker for Android.

SpectraScan turns an Android phone into a local, offline visual tracking system. It combines YOLO object detection, motion analysis, lightweight semantic tracking, Kalman prediction and a tactical-style HUD to detect and follow objects directly from the camera feed.

The project is currently an **experimental prototype**. It is intended for computer-vision experiments, sky observation, wildlife/traffic observation and repurposing spare Android devices as standalone camera nodes.

## Highlights

- **YOLO11n / COCO detection** running locally with ONNX Runtime
- Real-time tracking with stable target IDs
- Kalman-based motion prediction
- Lightweight semantic flow between YOLO passes
- Motion trail and motion lock
- Center lock and target zoom panel
- Automatic reacquisition after temporary target loss
- Independent motion detection channel
- Target filters: people, animals, objects, screens and sky
- Pinch-to-zoom using the camera's reported zoom range
- Exposure compensation and digital gain
- B/W mode and automatic Night Vision
- Low-light detection and adaptive detector throttling
- Camera sharpen, noise reduction and stabilization where supported
- Portrait and landscape support
- Offline operation after installation

## SkyWatch

SpectraScan 0.10 introduces an experimental **SKY** profile aimed at fixed-camera observation.

In this mode the application combines a high-resolution motion grid with YOLO classification. A moving object that cannot be classified by the current COCO model is displayed as:

```text
UNKNOWN
```

`UNKNOWN` means **unclassified motion target**. It does not imply that the object is unidentified in any extraordinary sense.

The current SkyWatch implementation works best when the phone is mounted or otherwise kept still. Handheld micro-movements can create transient motion candidates; camera-stability filtering and separate handheld/stationary profiles are planned.

## How it works

```text
CameraX
   │
   ├── Luma / Motion analysis ───────► MOTION / UNKNOWN
   │
   ├── YOLO11n (adaptive rate) ──────► semantic detections
   │                                      │
   └──────────────────────────────────────┤
                                          ▼
                                  Hybrid Tracker
                                 /       |        \
                            Kalman   Flow memory   TTL
                                 \       |        /
                                          ▼
                                  Tracking HUD
```

YOLO is intentionally not run at the maximum camera frame rate. Between neural-network passes, SpectraScan uses lightweight tracking and prediction. If local tracking becomes uncertain, YOLO is scheduled earlier to validate or reacquire the target.

This reduces sustained CPU usage compared with running full-frame inference continuously.

## Tracking states

| State | Meaning |
| --- | --- |
| `TRACKING` | Target is currently confirmed |
| `PREDICTED` | Target position is temporarily predicted between confirmations |
| `LOST` | Target has not been confirmed within the allowed time |
| `UNKNOWN` | Motion target detected in SkyWatch but not classified by YOLO |

Flow/Kalman prediction is never treated as permanent semantic truth. Classes such as `PERSON`, `CAR` or `DOG` must periodically be validated by YOLO.

## Camera controls

SpectraScan currently supports:

- two-finger pinch zoom;
- camera-reported zoom range where exposed by CameraX;
- exposure compensation;
- digital brightness gain;
- monochrome mode;
- automatic low-light / Night Vision mode;
- hardware sharpen and noise reduction where available;
- preview stabilization where supported by the device.

> The zoom range exposed to third-party CameraX applications may differ from the range shown by the manufacturer's stock camera. Extreme zoom values in OEM camera apps may combine multiple physical lenses and proprietary processing.

## Installation

There is currently no Play Store release.

### GitHub Actions APK

1. Open the repository's **Actions** tab.
2. Select **Build Android APK**.
3. Open the latest successful run.
4. Download the APK artifact.
5. Extract the artifact ZIP and install the APK on Android.

The CI debug builds use a persistent signing key, so newer builds should install over previous SpectraScan builds as normal updates as long as the signing configuration is unchanged.

### Build from source

Requirements:

- Android Studio / Android SDK
- JDK 17
- Android API 36 toolchain

The app uses Kotlin, Jetpack Compose, CameraX and ONNX Runtime for Android.

The CI workflow downloads the YOLO11n ONNX model during the build and packages it into the APK.

## Main technology

| Component | Technology |
| --- | --- |
| UI | Jetpack Compose |
| Camera | CameraX / Camera2 interop |
| Object detection | YOLO11n, COCO-80 |
| Inference | ONNX Runtime Android |
| Tracking | custom HybridTracker + Kalman prediction |
| Local visual tracking | luma template / semantic flow |
| Motion detection | frame differencing with camera-motion compensation |
| Build | Gradle + GitHub Actions |

## Current limitations

SpectraScan is still under active development.

- Detection quality depends heavily on lighting, camera quality and object size.
- YOLO11n uses COCO classes and therefore cannot identify arbitrary object types.
- Small distant aircraft, drones and birds may appear only as motion targets.
- SkyWatch currently assumes a mostly stationary camera and can be sensitive to handheld shake.
- Heavy rain, fog, reflections, foliage and low contrast can significantly reduce tracking quality.
- Digital zoom cannot recover detail that the camera sensor did not capture.
- Object classification confidence is not proof of identity.
- `UNKNOWN` is simply a motion target without a successful supported classification.

## Planned work

The short-term roadmap includes:

- SkyWatch handheld/stationary profiles;
- camera stability gate and calibration period;
- temporal confirmation for `UNKNOWN` targets;
- event-based video recording with pre/post detection buffer;
- richer Lock Engine and local reacquisition;
- performance profiles (`ECO`, `BALANCED`, `PERFORMANCE`);
- observation presets such as Sky, Road, Wildlife and Security;
- optional camera-node / remote-viewer architecture.

See [ROADMAP.md](ROADMAP.md) for more detail.

## Privacy

SpectraScan performs detection locally on the device. The current tracking pipeline does not require uploading camera frames to a remote AI service.

Future network or remote-viewer features, if added, should remain optional and clearly separated from local processing.

## Disclaimer

SpectraScan is an experimental computer-vision project, not a safety, aviation, security, surveillance, navigation or scientific identification system. Detection and tracking results can be wrong. Do not rely on the application for safety-critical decisions or identification claims.

Use cameras responsibly and comply with local privacy and recording laws.

## Project status

Current development line: **0.10.x — SkyWatch**

The project evolves quickly and development APKs may contain regressions. Stable behavior is prioritized before major new tracking features are merged.

## Русский

SpectraScan — экспериментальный Android-проект для локального распознавания и сопровождения объектов с камеры. Все основные вычисления выполняются на устройстве. Текущий режим SkyWatch предназначен в первую очередь для наблюдения с закреплённого телефона; `UNKNOWN` означает только движущийся объект, который модель не смогла классифицировать.

Подробности по будущим версиям находятся в [ROADMAP.md](ROADMAP.md).

## License

A project license has not been selected yet. Until a license file is added, the source code remains subject to the repository author's default copyright rights.
