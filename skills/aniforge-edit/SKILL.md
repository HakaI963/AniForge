---
name: aniforge-edit
description: Edit anime-grade videos using AniForge Studio API (OpenShot timeline format via afctl) — cuts, speed ramps, chroma key, Japanese text cards, presets like shonen-flash.
---

# AniForge edit skill

Directive: you are a director-level video editor. You will be given a **video link or file**
(user may provide a path, a URL, or nothing), a **prompt** (say what they want, e.g.
*"make it anime-like: 3 frame-perfect cuts on the chorus, add the char's name as a JP text card,
speed ramp the last 10% "*) and often a **reference project** (`project.osp`).

## Shared tooling

- `afctl` CLI (see invocation pattern). The endpoint base is `ANIFORGE_STUDIO` environment
  variable (default `http://127.0.0.1:8787`).
- Project format: `.osp` JSON (OpenShot timeline port = "AsProject").

## Workflow

1. **Preconditions**
   - `afctl health` — API must answer `{"status":"ok",...}`.
   - `afctl schema` — lists: `profiles`, `presets`, `effects`, `op_types`.

2. **Project**
   - If the user supplied a `.osp` project (file), read it (`cat project.osp`).
   - Else construct a minimal project from the target video link/file:

```json
{
  "version": 1,
  "fps": {"num": 24, "den": 1},
  "width": 1920, "height": 1080,
  "profile": "shonen",
  "layers": [{"id": 0, "name": "V1"}],
  "clips": [
    {"id": "c1", "layer": 0,
     "reader": {"path": "https://.../clip.mp4", "has_audio": true},
     "position": 0.0, "start": 0.0, "end": 0.0, "speed": 1.0}
  ]
}
```

   **If you only have a link and not the project, ask for duration** or use `duration: 0`
   and set `start`/`end` after you know the length. Writes `start`/`end` are *cuts* in seconds —
   in anime editing you often cut 2–6 frame slivers, cut hard on beats.

3. **Plan an edit**

   An anime edit is a sequence of **ops** on `clips[].id` with their `position/start/end/speed/effects/keyframes/text/errors`
   Restore your thinking in a plan: which clips will be cut at which timeline seconds, which
   effects, speeds, texts, and crops. Times are **timeline seconds, not clip seconds** — use
   `position + (t - start) ~ speed` when you know speeds change.

4. **Apply**

```bash
afctl apply --file project.osp --ops ops.json --message "3 cuts on the chorus" --out project.osp
```

   Return "project+events". Read the returned `events` and **print them** — the user (and the
   user's Forge Console) live-hacks on this stream.
   The `AFForge Studio API` writes the updated `project.osp` back for you. You iterate: apply,
   review, add/remove, apply again. Do not be a pushover: if an op fails (invalid time
   etc.) decide whether to fix it or reject.

5. **Validate & render** (optional)

```bash
afctl validate --file project.osp
afctl render  --file project.osp --preset shonen-flash --wait
```

6. **Summary**: post a short note including cut count, effects added, validation result, and
   render URL if applied to a render job.

## Ops cheat-sheet

- `cut`         — {"op":"cut","clip_id":"c1","at":2.5}  (timeline seconds)
- `speed`       — constant: {"op":"speed","clip_id":"c1","speed":0.5}
                  keyframed: {"op":"speed","clip_id":"c1","ramp":[[0,0.6],[0.5,2.5],[1.2,1.0]]}
- `add_effect`  — {"op":"add_effect","clip_id":"c1",
                   "effect":{"type":"ChromaKey","params":{"background":"#00FF00","tolerance":0.18}}}
                  Valid types: Fade, ChromaKey, Shift, Crop, Glow, Speed, SaiGaki, Sharpen,
                  Blur, Negate, Mirror, Saturation, Contrast, Deinterleave.
- `set_text`    — {"op":"set_text","clip_id":"c2","text":{"content":"第2話:疾風","style":"anime"}}
                  Styles: anime, anime-soft, manga.
- `crop`        — {"op":"crop","clip_id":"c1","ratio":"4:5"}   (1:1, 4:5, 9:16, 16:9, 21:9)
- `keyframe`    — {"op":"keyframe","clip_id":"c1","prop":"alpha","frame":96,"value":0.5}
- `marker`      — {"op":"marker","position":7.2,"label":"ドン！"}
- `flash_frame` — {"op":"flash_frame","at":5.0,"duration":0.05}  (adds a white flash at t)
- `crawl`       — rolling text; {"op":"crawl","clip_id":"c1"}

## Presets (via render/apply)

- `shonen-flash`     — hard cuts + speed ramps + flash frames + glow.
- `shojo-sparkle`    — fade in/out, bloom, saturation up.
- `seinen-grit`      — desaturate, contrast down, longer cuts.
- `anime-studio-standard` — clean fade + slight sharpen.
- `sns-4x5`          — 4:5 crop for social publish.

## Rules

- Op payloads are JSON inside `--ops file`; no bare CLI strings (stuff escapes badly otherwise).
- Validate after a heavier apply. If `ok` is false, show the user the exact returned event/error.
- Push the user's intent, not your own taste without asking. Prefer shonen-flash style when
  the user says just "edit like this" without a specific vibe? No — ask once, don't guess twice.
- Negative `positions` are not allowed. Cut points must lie inside a clip's position..duration.
