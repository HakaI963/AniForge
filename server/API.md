# AniForge Studio API

All endpoints are JSON in / JSON out (SSE for events). Default port `8787`.

Base URL: `http://127.0.0.1:8787`

| Endpoint         | Type | Description |
|------------------|------|-------------|
| `/health`        | GET  | service + presets list |
| `/schema`        | GET  | profiles, presets, effects, op_types, text_styles |
| `/project/validate` | POST | `{project}` → `{ok, issues, duration, clips}` |
| `/clip/add`      | POST | `{project, clip}` → `{project, events}` |
| `/edit/apply`    | POST | `{project, ops[], message}` → `{ok, project, events, issues}` — the agent entry-point |
| `/clip/move`     | POST | `{project, clip_id, position, layer}` |
| `/clip/cut`      | POST | `{project, clip_id, at}` → split into `id-a`, `id-b` |
| `/clip/speed`    | POST | `{project, clip_id, speed}` or `ramp:[[t,s],...]` |
| `/clip/effects`  | POST | `{project, clip_id, effect}` |
| `/clip/text`     | POST | `{project, clip_id, text:{content,style,position}}` |
| `/clip/crop`     | POST | `{project, clip_id, ratio}` 1:1 / 4:5 / 9:16 / 16:9 / 21:9 |
| `/project/render`| POST | `{project, preset}` → `{job_id}` |
| `/render/{id}`   | GET  | render job status |
| `/analyze`       | POST | scene-detection stub |
| `/events`        | GET  | SSE live event feed (agent edits appear here) |
| `/events/recent` | GET  | last 100 events |

## Apply ops reference

`ops` is a list of op dicts (see `afctl examples`):

- `{"op":"cut","clip_id":"c1","at":2.5}`
- `{"op":"speed","clip_id":"c1","speed":0.5}` or `{"op":"speed","clip_id":"c1","ramp":[[0,0.6],[0.5,2.0],[1.2,1.0]]}`
- `{"op":"add_effect","clip_id":"c1","effect":{"type":"ChromaKey","params":{"background":"#00FF00","tolerance":0.18}}}`
- `{"op":"set_text","clip_id":"c2","text":{"content":"第0夜 序章","style":"anime"}}`
- `{"op":"crop","clip_id":"c1","ratio":"4:5"}`
- `{"op":"keyframe","clip_id":"c1","prop":"alpha","frame":96,"value":0.5}`
- `{"op":"marker","position":7.2,"label":"ドン！"}`
- `{"op":"flash_frame","at":5.0,"duration":0.05}`
- `{"op":"crawl","clip_id":"c1"}`
- `{"op":"add_clip","clip":{...}}`

## Render presets

`shonen-flash` · `shojo-sparkle` · `seinen-grit` · `anime-studio-standard` · `sns-4x5`

## Example

```bash
curl -s localhost:8787/health | jq
curl -s localhost:8787/edit/apply -H 'content-type: application/json' \
  -d @- <<EOF
{"project": {...project.osp json...},
 "ops":[{"op":"cut","clip_id":"c1","at":7.4},
        {"op":"speed","clip_id":"c1","speed":0.5}],
 "message":"slow-mo at the impact"}
EOF
```

Response includes `project` (updated) and `events` — forward these to the user or the
Forge Console via `afctl watch` / `GET /events`.
