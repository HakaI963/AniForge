# AniForge

**Anime-grade video editing as a public API.** AniForge ports the OpenShot `/ openshot-qt`
timeline core (`.osp` JSON, layers, clips with `position/start/end` cuts, OpenShot-style
keyframes, effects, markers) ** onto:

| Piece | Where | What |
|---|---|---|
| **Studio engine + REST API** | `server/` | Python FastAPI. Pure ops: project-in → project-out + events; `ops` for cuts, speed ramps, chroma key, JP text cards, crop presets, "flash frame", markers. SSE `/events` for the live monitor feed. |
| **Android app** (APK) | `app/` | **AniForge Studio** — AI auto editor (Compose): agents plan cuts, fetch music/material, auto-build & export real MP4s. |
| **Agent CLI** | `cli/afctl` | `afctl` — stdlib only, agent-first CLI that maps any "edit like this" intent onto ops. |
| **ImGui Forge Console** | `console/` | Dear ImGui C++ desktop viewer for the same project — timeline canvas, agent feed, FX panel, prompt box. |
| **AniList material catalog** | `server/` (Studio API) | Anime trending titles exposed to editors as anime material. |
| **Examples** | `examples/demo.osp`, `examples/ops.demo.json` | Ready-to-run baseline timeline and an edit ops payload. |
| **CI** | `.github/workflows/` | `server.yml` (pytest), `android.yml` (APK), `console.yml` (ImGui binary), `release.yml` (tag publish). |

Statuses: [![](https://github.com/HakaI963/AniForge/actions/workflows/server.yml/badge.svg)](https://github.com/HakaI963/AniForge/actions) · [![](https://github.com/HakaI963/AniForge/actions/workflows/android.yml/badge.svg)](https://github.com/HakaI963/AniForge/actions) · [![](https://github.com/HakaI963/AniForge/actions/workflows/console.yml/badge.svg)](https://github.com/HakaI963/AniForge/actions) · [latest release](https://github.com/HakaI963/AniForge/releases/latest)

## Quick start

```bash
# 1) Studio API (you need Python 3.10+)
python -m venv .venv && source .venv/bin/activate
pip install -r server/requirements.txt
pytest server/tests -q                # sanity
uvicorn aniforge_server.main:app      # → http://127.0.0.1:8787/docs

# 2) Agent-side / shell control
afctl health
afctl schema
afctl validate --file examples/demo.osp
afctl apply --file examples/demo.osp --ops examples/ops.demo.json --message "first chop" --out out.osp
afctl watch                            # live SSE feed (what the Forge Console draws)

# 3) Forge Console (ImGui, needs a display; GL 3.2 + libcurl + GLFW)
./forge-console                        # or: cmake -S console -B console/build && cmake --build console/build
```

The **agent loop** (give OpenCode a video link/file/prompt and say "edit like this"):
see [`skills/aniforge-edit/SKILL.md`] — OpenCode plans cuts/effects/texts, submits via
`afctl apply`, streaming `clip.add`, `clip.cut`, `clip.speed`, `clip.mess` events on
`afctl watch` / `curl -N localhost:8787/events` — you will literally watch the edit happen.

## Endpoints (all in `server/API.md`)

`/health`, `/schema`, `/project/validate`, `/edit/apply`, `/clip/{add,move,cut,speed,text,crop,render}`,
`/project/render`, `/render/{id}`, `/analyze`, `/events` (SSE), `/events/recent`.

## Android app — AniForge Studio (AI auto editor)

`app/` is an **agent-driven auto editor**: you import your own clips, type what you want
("30s hype amv, vertical, with music"), press **⚡ AUTO EDIT** — and the app does the rest:

- **Agent 1 — the director.** Analyzes every clip (scene cuts, cadence, energy), reads your
  prompt, and builds the entire timeline itself: intro card → cuts on the rhythm → speed waves,
  push-ins, crossfades, grade rotation, SNS crop plan → outro card → music bed with a
  volume envelope. Optional: point the 🧠 tab at any OpenAI-compatible LLM endpoint and it
  becomes a real LLM planner (falls back to the built-in on-device director without one).
- **Agent 2 — the material finder.** Auto-fetches open-license **music** (matching your prompt,
  licensed via Openverse — keyless public API) and **card art** for intro/outro titles.
- **Result screen** — the finished edit, ready to watch and export. Manual trim/crop/keyframe
  tools still exist behind the ⚙ button for people who want them.
- **Export** — real MP4 (H.264 + AAC) rendered fully on-device via MediaCodec at 1080p/720p/480p.
- New adaptive icon, locked landscape, immersive UI, no accounts, nothing uploaded to us.

The server/ImGui/CLI side (Studio REST API, SSE events, `afctl`, OpenCode skill) remains a
separate, fully supported integration path.

## Docs

- [`DESIGN.md`](DESIGN.md) — full engine/format spec (.osp ≅ AsProject)
- [`server/API.md`](server/API.md) — endpoints + ops reference
- [`AGENTS.md`](AGENTS.md) — instructions if you are an AI working inside this repo
- [`opencode/README.md`](opencode/README.md) — how OpenCode plugs into AniForge
- [`skills/aniforge-edit/`](skills/aniforge-edit/SKILL.md) — " dpawn from a video link + prompt → edit like this" recipe

## Contributing / CI

Three workflows build remote-only (no local build dependencies):

| workflow | triggers | what |
|---|---|---|
| `server.yml` | push / PR to main | pytest on FastAPI API contract + engine ops |
| `android.yml` | push / PR to main | `:app:assembleRelease` APK upload |
| `console.yml` | push / PR touching `console/**` | Dear ImGui console binary upload |

## License

MIT. "OpenShot" is a trademark of OpenShot Studios, LLC — not affiliated.
