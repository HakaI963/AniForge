# OpenCode integration

OpenCode is wired into AniForge through three things:

## 1. `AGENTS.md` (repo root)

OpenCode loads this automatically when you open AniForge as a project. It tells the agent
where the engine lives, how to run the Studio API, and which CLI (`afctl`) to use.

## 2. `skills/aniforge-edit/`

The edit skill — copy to your local skills dir (`~/.config/opencode/skills/`) or reference
from `~/.config/opencode/AGENTS.md`.

## 3. Live viewing

After OpenCode (or any agent/shell) runs `afctl apply`, the result is applied to the
running Studio API and the resulting events (`agent.apply`, `clip.cut`, `clip.speed`,
`clip.text`, ...) are pushed to the **live event stream**:

- `GET /events` — SSE
- `GET /events/recent` — snapshot JSON

Consume it with:

- `afctl watch` (terminal)
- `./forge-console` (the **ImGui** console — timeline + event log + prompt box)
- `curl -N http://127.0.0.1:8787/events`

That's "we can see him live edit".
