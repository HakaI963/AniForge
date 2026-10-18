# AniForge design doc

> Status: **v1.0 — 2026-10-18** · Owner: HakaI963/AniForge

## 1. Why

OpenShot-qt is a great desktop editor. We are making an **anime-grade** version for Android,
built by an AI agent, run in CI, consumed through a public API and a CLI so **OpenCode** can
edit video like a director.

The engine, file format and特效 domain concepts come from **OpenShot** (`.osp` JSON timeline:
reader/writer, layers, clips with `start`/`end` cuts, keyframes, effects, markers).
AniForge re-implements that core in Kotlin (app) and Python (server) sharing the same
**AsProject** JSON shape.

## 2. Format (AsProject ≅ .osp)

```json
{
  "version": 1,
  "fps": { "num": 24, "den": 1 },
  "duration": 30.0,
  "width": 1920, "height": 1080,
  "profile": "shonen",
  "layers": [ { "id": 0, "name": "V1" } ],
  "clips": [
    {
      "id": "c1", "layer": 0, "reader": { "path": "https://.../clip.mp4", "has_audio": true, "has_video": true },
      "position": 0.0, "start": 0.5, "end": 5.0,  // start/end = OpenShot cuts
      "speed": 1.0,
      "effects": [{ "type": "ChromaKey", "keys": {"background": "#00FF00"} }],
      "keyframes": { "alpha": { "points": [{"co": {"X": 0, "Y": 1.0}}] } },
      "text": { "content": "第1話", "style": "anime" },
      "crop": { "ratio": "4:5" }, "mask": { "path": "..." }
    }
  ],
  "markers": [ { "position": 3.2, "label": "ドン！" } ]
}
```

- **positions** are seconds (float). `start`/`end` are **cuts** inside the source clip.
- **keyframes** follow OpenShot semantics (`points[].co.X` frame, `.Y` value).
- `profile`: `shonen` / `shojo` / `seinen` / `movie` / custom.

## 3. Components

### Android app (Kotlin + Jetpack Compose)
- Timeline UI: layers (tracks), clips with cut handles, drag, zoom; keyframe editor.
- Material 3 (M3). Japanese fonts (Noto Sans JP) for text.
- Domain: `AsProject`, `Clip`, `Reader`, `Effect`, `Keyframe` — mirrors server models.
- Talks to Studio API (server) for heavy ops (render, analyze, transcode) — http://10.0.2.2:8787 or an actual server instance.
- Can edit fully offline too (local timeline persistence as JSON).

### Studio API (Ktor — Python FastAPI in `server/`)
Stateless **timeline service** over AsProject JSON: it never holds state, it transforms
timelines, spins on-demand ffmpeg to render. Fast, forkable, public.

- `GET /health`, `GET /schema`
- `POST /project/validate`    		 — parse + lint (fps, overlaps, effects)
- `POST /project/render`       		 — enqueue render, returns job id
- `GET  /render/{id}`           		 — status / result URL
- `POST /clip/add`, `/clip/move`, `/clip/cut`, `/clip/speed`, `/clip/effects`, `/clip/text`, `/clip/crop`
- `POST /edit/cut`          			 — frame-accurate cut at t (adds split, "cut film" )
- `POST /edit/apply`        			 — generic: message + JSON patch (OpenCode path)
- `GET  /events`               			 — SSE stream (live editing feed; the agent's actions appear here)
- `POST /analyze` / `POST /transcribe` 	 — rough client of mp4/AAC scene detection (stub in v1)

All ops are **pure**: project-in → project-out + `events` list. The app and the agent consume
the same API (one source of truth).

### OpenCode CLI (`cli/afctl`)
agent-first CLI, subcommands mirror the REST API plus:
- `afctl health` `afctl schema` `afctl validate --project project.osp`
- `afctl edit apply --file timeline.osp --patch p.json --message "whip pan"` → prints API-style events
- `afctl edit render --file timeline.osp --preset <name>` — presets: `shonen-flash`, `shojo-sparkle`, `seinen-grit`, `anime-studio-standard`
- `afctl link <url>` — media ingest (probe, then propose cut plan JSON)
- `afctl watch --sse <url>` — follow the live event stream (what ImGui console draws)

### ImGui console ("Forge Console")
Dear ImGui (C++20, imgui v1.91.x): docks:
- **Agent log** (JSON events from `afctl watch`)
- **Timeline preview** (canvas: draw clips as colored bars, markers, keyframes)
- **Patch inspector** (last applied JSON patch)
- **Send prompt** box → builds `afctl edit apply` command and runs it.

### Agent loop (opencode)
`opencode/aniforge.agents.md`, permissions, plus `skills/aniforge-edit/SKILL.md`.
The agent:
1. Ingests media (link/path), `afctl link` returns probe + suggested cut plan.
2. Plans: map the prompt → timeline ops (cuts at beat times, effects, texts).
3. Applies via API; prints events; streams them (SSE) — the user **sees** it live.
4. Validates + renders preset → returns render job id / APK plays result.
