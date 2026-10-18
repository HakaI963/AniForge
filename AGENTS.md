# AniForge — agent instructions

AniForge is an OpenShot timeline engine ported to a public REST API
("Studio API") + an Android app + an ImGui console (Forge Console — `afctl`
style commands, the same `.osp` JSON format).

- Engine: `server/aniforge_server/engine.py` — pure Kotlin mirror in `app/src/main/kotlin/dev/aniforge/domain/`
- Studio API routes: `server/aniforge_server/main.py`
- CLI: `cli/afctl` (python, stdlib only)
- Docs: `DESIGN.md`, `server/API.md`, `opencode/README.md`

## When you edit

- Never modify `server/aniforge_server/engine.py` without also reflecting the change in the
  Kotlin domain model (`AsProject` schema) and running `pytest server/tests`.
- Prefer pure operations: project in → project out + events.
- Keep `.osp` compatibility in mind: reader/clips/`start`/`end`/`position`/`speed`/`effects`/
  `keyframes` must stay OpenShot-compatible.

## Testing

```bash
pip install -r server/requirements.txt
pytest server/tests -q
```

## Rendering

The default render backend is `stub` (safe, no ffmpeg). Set `ANIFORGE_RENDER=ffmpeg` when
you have ffmpeg in PATH — render.py auto-upgrades. Do not add heavy deps.

## Decorum

- Focus on server, CLI, engine; the Android app is style-guide driven in `app/`.
- Use `openapi` (auto docs at /docs) docstrings where possible.
- Do not commit secrets.
