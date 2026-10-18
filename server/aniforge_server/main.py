"""FastAPI Studio API — public, stateless, pure timeline transforms.

Run:  uvicorn aniforge_server.main:app --host 0.0.0.0 --port 8787
"""
from __future__ import annotations

import json
import os
import time
import uuid
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .engine import (AsProject, Clip, Effect, Keyframe, Point, Reader, Marker,
                     Ops, PRESETS, PROFILES, EFFECT_TYPES, TEXT_STYLES)

app = FastAPI(title="AniForge Studio API", version="1.0.0",
              description="Anime-grade OpenShot-compatible timeline API. "
                          "Project in → project out + events. OpenCode friendly.",
              docs_url="/docs", openapi_url="/openapi.json")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)

# --------------------------------------------------------------- live events
import threading as _threading
_EVENTS: List[Dict[str, Any]] = []
_EVENTS_LOCK = _threading.Lock()

def push_event(ev: Dict[str, Any]) -> None:
    ev = {"ts": time.time(), **ev}
    with _EVENTS_LOCK:
        _EVENTS.append(ev)
        if len(_EVENTS) > 5000:
            del _EVENTS[:2500]

def log(tag: str, **kw: Any) -> None:
    push_event({"event": "server.log", "tag": tag, **kw})


# --------------------------------------------------------------- models
class ProjectIn(BaseModel):
    project: Dict[str, Any] = Field(..., description="AsProject JSON (.osp compatible)")

class PatchIn(BaseModel):
    project: Dict[str, Any]
    message: Optional[str] = Field(None, description="human/agent intent note")
    ops: List[Dict[str, Any]] = Field(default_factory=list,
        description='[{"op":"cut","clip_id":"c1","at":2.0}, ...]')

class RenderIn(BaseModel):
    project: Dict[str, Any]
    preset: Optional[str] = Field(None, description="shonen-flash | shojo-sparkle | einen-grit | anime-studio-standard | sns-4x5")
    out_name: Optional[str] = None

class ClipAddIn(BaseModel):
    project: Dict[str, Any]
    clip: Dict[str, Any]

class ClipOpIn(BaseModel):
    project: Dict[str, Any]
    clip_id: str
    position: Optional[float] = None
    layer: Optional[int] = None
    at: Optional[float] = None          # for cut
    speed: Optional[float] = None
    ramp: Optional[List[List[float]]] = None  # [[t0,s0],[t1,s1],...]
    effect: Optional[Dict[str, Any]] = None
    text: Optional[Dict[str, Any]] = None
    ratio: Optional[str] = None
    prop: Optional[str] = None; frame: Optional[float] = None; value: Optional[float] = None
    label: Optional[str] = None

# --------------------------------------------------------------- SSE
async def event_stream():
    """Polling-based SSE — robust to FastAPI thread pools and simple to reason about."""
    import asyncio
    idx = 0
    yield f"data: {json.dumps({'event': 'hello', 'ts': time.time()})}\n\n"
    while True:
        await asyncio.sleep(0.4)
        with _EVENTS_LOCK:
            batch = _EVENTS[idx:]
            idx = len(_EVENTS)
        for ev in batch:
            yield f"data: {json.dumps(ev)}\n\n"

# --------------------------------------------------------------- routes
@app.get("/health")
def health() -> Dict[str, Any]:
    return {"status": "ok", "service": "aniforge-studio", "version": "1.0.0",
            "profiles": list(PROFILES), "presets": list(PRESETS)}

@app.get("/schema")
def schema() -> Dict[str, Any]:
    return {
        "profiles": PROFILES,
        "presets": PRESETS,
        "effects": EFFECT_TYPES,
        "text_styles": TEXT_STYLES,
        "op_types": sorted({"cut", "speed", "add_effect", "move", "set_text", "crop",
                            "keyframe", "marker", "add_clip", "flash_frame", "crawl"},
                           ),
        "format_doc": "https://github.com/HakaI963/AniForge#format",
    }

# ---- project
@app.post("/project/validate")
def project_validate(body: ProjectIn) -> Dict[str, Any]:
    p = AsProject.parse(body.project)
    p.clamp_all()
    issues = p.validate()
    return {"ok": len(issues) == 0, "issues": issues,
            "duration": p.duration(), "clips": len(p.clips)}

@app.post("/clip/add")
def clip_add(body: ClipAddIn) -> Dict[str, Any]:
    p = AsProject.parse(body.project)
    clip = Clip.parse(body.clip)
    evs = [p.add_clip(clip)]
    push_event({"event": "api.clip.add", "clip_id": clip.id})
    return {"project": p.to_dict(), "events": evs}

def _apply_ops(p: AsProject, ops: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    for step in ops:
        op = step.get("op")
        try:
            if op == "add_clip":
                events.append(p.add_clip(Clip.parse(step["clip"])))
            elif op == "cut":
                _, ev = Ops.cut(p, step["clip_id"], float(step["at"])); events += ev
            elif op == "speed":
                ramp = [(float(t), float(s)) for t, s in step["ramp"]] if step.get("ramp") else None
                _, ev = Ops.speed(p, step["clip_id"], float(step.get("speed", 1.0)), ramp); events += ev
            elif op == "add_effect":
                _, ev = Ops.add_effect(p, step["clip_id"], Effect.parse(step["effect"])); events += ev
            elif op == "move":
                _, ev = Ops.move(p, step["clip_id"], float(step["position"]), step.get("layer")); events += ev
            elif op == "set_text":
                t = step.get("text") or {}
                _, ev = Ops.set_text(p, step["clip_id"], t.get("content", ""),
                                     t.get("style", "anime"), t.get("position", "center")); events += ev
            elif op == "crop":
                _, ev = Ops.crop(p, step["clip_id"], step.get("ratio", "4:5")); events += ev
            elif op == "keyframe":
                _, ev = Ops.keyframe(p, step["clip_id"], step["prop"],
                                     float(step["frame"]), float(step["value"])); events += ev
            elif op == "marker":
                _, ev = Ops.marker(p, float(step["position"]), str(step.get("label", ""))); events += ev
            elif op == "flash_frame":
                # white full-opacity clip of tiny duration at every cut boundary
                at = float(step.get("at", 0.0)); dur = float(step.get("duration", 0.05))
                white = Clip(id=f"flash-{uuid.uuid4().hex[:6]}", layer=99,
                             reader=Reader(path="generated://white"))
                white.position = at; white.start = 0.0; white.end = dur
                white.keyframes["alpha"] = Keyframe([Point(0, 1.0), Point(max(1, int(dur*24)), 0.0)])
                events.append(p.add_clip(white))
            elif op == "crawl":
                # rolling credits / text crawl
                src = next(c for c in p.clips if c.id == step["clip_id"])
                src.keyframes["crop_y"] = Keyframe([Point(0, 0.0), Point(p.duration()*p.fps, -0.2)])
                events.append({"event": "clip.crawl", "clip_id": src.id})
            else:
                events.append({"event": "error", "message": f"unknown op: {op}"})
        except Exception as e:
            events.append({"event": "error", "op": op, "message": str(e)})
    return events

@app.post("/edit/apply")
def edit_apply(body: PatchIn) -> Dict[str, Any]:
    """The agent path: project + ops[] -> project' + events. OpenCode uses this."""
    p = AsProject.parse(body.project)
    events = _apply_ops(p, body.ops or [])
    p.clamp_all(); issues = p.validate()
    push_event({"event": "agent.apply", "message": body.message or "",
                "ops": len(body.ops or []), "issues": len(issues)})
    for ev in events:
        push_event(ev)
    return {"ok": not any(e.get("event") == "error" for e in events),
            "project": p.to_dict(), "events": events, "issues": issues}

# ---- generic material endpoints (thin wrappers around ops)
@app.post("/clip/move")
def _move(body: ClipOpIn):
    p = AsProject.parse(body.project)
    _, ev = Ops.move(p, body.clip_id, body.position or 0.0, body.layer)
    push_event(ev[0])
    return {"project": p.to_dict(), "events": ev}

@app.post("/clip/cut")
def _cut(body: ClipOpIn):
    p = AsProject.parse(body.project)
    _, ev = Ops.cut(p, body.clip_id, body.at or 0.0)
    push_event(ev[0])
    return {"project": p.to_dict(), "events": ev}

@app.post("/clip/speed")
def _speed(body: ClipOpIn):
    p = AsProject.parse(body.project)
    ramp = [(t, s) for t, s in body.ramp] if body.ramp else None
    _, ev = Ops.speed(p, body.clip_id, body.speed or 1.0, ramp)
    push_event(ev[0])
    return {"project": p.to_dict(), "events": ev}

@app.post("/clip/effects")
def _effects(body: ClipOpIn):
    p = AsProject.parse(body.project)
    _, ev = Ops.add_effect(p, body.clip_id, Effect.parse(body.effect or {}))
    push_event(ev[0])
    return {"project": p.to_dict(), "events": ev}

@app.post("/clip/text")
def _text(body: ClipOpIn):
    p = AsProject.parse(body.project)
    t = body.text or {}
    _, ev = Ops.set_text(p, body.clip_id, t.get("content", ""), t.get("style", "anime"))
    push_event(ev[0])
    return {"project": p.to_dict(), "events": ev}

@app.post("/clip/crop")
def _crop(body: ClipOpIn):
    p = AsProject.parse(body.project)
    _, ev = Ops.crop(p, body.clip_id, body.ratio or "4:5")
    push_event(ev[0])
    return {"project": p.to_dict(), "events": ev}

# ---- render
_JOB_BACKEND = os.environ.get("ANIFORGE_RENDER", "stub")  # 'ffmpeg' in future, default stub

@app.post("/project/render")
def project_render(body: RenderIn) -> Dict[str, Any]:
    p = AsProject.parse(body.project)
    preset = body.preset
    if preset and preset in PRESETS:
        preset_ops = PRESETS[preset].get("steps", [])
        _apply_ops(p, preset_ops)  # apply preset as ops, post-render isn't needed here
    job_id = f"job_{uuid.uuid4().hex[:10]}"
    push_event({"event": "render.queued", "job_id": job_id, "preset": preset,
                "duration": p.duration()})
    return {"job_id": job_id, "status": "queued",
            "message": f"render accepted (backend={_JOB_BACKEND})",
            "project_after_preset": p.to_dict()}

@app.get("/render/{job_id}")
def render_status(job_id: str) -> Dict[str, Any]:
    # stub renderer completes immediately; real backend via ANIFORGE_RENDER=ffmpeg
    return {"job_id": job_id, "status": "done",
            "output": f"s3://aniforge-out/{job_id}.mp4",
            "note": "stub backend; wire ffmpeg backend via ANIFORGE_RENDER env"}

# ---- live events (the 'watch the agent edit' feed)
@app.get("/events")
def events() -> StreamingResponse:
    return StreamingResponse(event_stream(), media_type="text/event-stream")

@app.get("/events/recent")
def events_recent() -> Dict[str, Any]:
    return {"events": _EVENTS[-100:]}

# ---- analyze (stub for v1; scene detection via ffmpeg in CI)
@app.post("/analyze")
def analyze(body: ProjectIn) -> Dict[str, Any]:
    p = AsProject.parse(body.project)
    dur = p.duration()
    scenes = [{"start": i * 2.5, "end": (i + 1) * 2.5} for i in range(int(dur // 2.5))]
    push_event({"event": "analyze.done", "scenes": len(scenes)})
    return {"duration": dur, "fps": p.fps, "scenes_hint": scenes}
