# SpectraScan Roadmap

This roadmap describes the current development direction. Version numbers and scope may change as testing reveals new tracking or camera constraints.

## 0.10.x — SkyWatch stabilization

Goal: make small-motion sky observation usable with both fixed and handheld devices.

Planned:

- separate `SKY HANDHELD` and `SKY STATIONARY` profiles;
- camera stability gate using global frame motion;
- `CALIBRATING` period for stationary observation;
- temporal confirmation before displaying `UNKNOWN`;
- stricter suppression of foliage, noise and micro-shake;
- better persistence rules for small distant targets;
- SkyWatch-specific motion sensitivity controls.

## 0.11.x — Event Recorder

Goal: record useful detection events instead of continuous multi-hour footage.

Planned:

- manual recording;
- automatic event recording;
- pre-event video buffer;
- configurable post-event recording time;
- save target trajectory and metadata alongside video;
- option to record clean camera feed or HUD overlay;
- event browser with timestamp, duration and detected class;
- storage limits and automatic cleanup controls.

Target concept:

```text
rolling buffer → detection → save pre-roll → track event → post-roll → event file
```

## 0.12.x — Observation profiles

Goal: adapt the same tracking core to different scenes instead of using one universal threshold set.

Candidate presets:

- `SKY` — aircraft, birds and small moving targets;
- `ROAD` — cars, motorcycles, buses and pedestrians;
- `WILDLIFE` — animals with longer reacquire windows;
- `SECURITY` — people/vehicles with stationary-camera filtering;
- `NIGHT` — low-light motion + bright-object detection.

Each profile may control YOLO frequency, accepted classes, motion sensitivity, confirmation time, track TTL and low-light processing.

## 0.13.x — Performance and sustained operation

Goal: make SpectraScan suitable for long-running camera-node use.

Planned:

- `ECO / BALANCED / PERFORMANCE` profiles;
- thermal-aware inference scheduling;
- battery-aware detector throttling;
- additional allocation reduction in camera preprocessing;
- optional NNAPI/GPU inference experiments where stable;
- long-session memory/leak testing;
- background/foreground lifecycle hardening.

## Later — Camera Node / Viewer

Possible architecture for spare phones or Android cameras:

```text
Camera Node
  CameraX + detection + tracking
          │
          ├── local event storage
          │
          └── optional network stream
                    │
                    ▼
                 Viewer
```

Possible features:

- remote live preview;
- remote target list and lock control;
- event notifications;
- multiple camera nodes;
- local-network-only mode;
- optional authenticated remote access.

This is intentionally a later-stage feature. Local standalone tracking should remain fully usable without a network connection.

## General priorities

1. Avoid false persistent semantic tracks.
2. Keep YOLO as the semantic source of truth.
3. Prefer lightweight prediction between expensive inference passes.
4. Preserve offline/local operation by default.
5. Make uncertainty visible rather than presenting predictions as facts.
6. Optimize for sustained mobile use, not benchmark-only peak FPS.
