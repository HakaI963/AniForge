# afctl — agent-side CLI

`afctl` is the shell-facing control panel for the AniForge Studio API.

```bash
export ANIFORGE_STUDIO=http://127.0.0.1:8787
afctl health
afctl schema
afctl validate --file project.osp
afctl apply --file project.osp --ops ops.json --message "cut to the beat"
afctl render --file project.osp --preset shonen-flash --wait
afctl watch        # live SSE event stream — the agent sees everything
afctl examples
```

All output is JSON — agents parse it directly.

## For OpenCode

OpenCode uses `afctl` as a tool. Root file `AGENTS.md` documents the tool;
`skills/aniforge-edit/SKILL.md` teaches anime-flavoured edits.
