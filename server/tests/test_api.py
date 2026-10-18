"""Contract tests — every endpoint + custom constraints.

Run: pytest server/tests -q
"""
from __future__ import annotations

import json
from typing import Any, Dict

import pytest
from fastapi.testclient import TestClient

from aniforge_server.main import app, push_event

client = TestClient(app)


def base_project() -> Dict[str, Any]:
    return {
        "version": 1,
        "fps": {"num": 24, "den": 1},
        "width": 1920, "height": 1080,
        "profile": "shonen",
        "layers": [{"id": 0, "name": "V1"}],
        "markers": [],
        "clips": [
            {
                "id": "c1", "layer": 0,
                "reader": {"path": "https://example.com/a.mp4", "has_audio": True, "duration": 12.4},
                "position": 0.0, "start": 0.5, "end": 8.0,
                "speed": 1.0, "effects": [], "keyframes": {},
            },
            {
                "id": "c2", "layer": 0,
                "reader": {"path": "https://example.com/b.mp4", "has_audio": True, "duration": 9.9},
                "position": 7.5, "start": 0.0, "end": 6.0,
                "speed": 1.0, "effects": [], "keyframes": {},
            },
        ],
    }


# ---------------------------------------------------------------- contract
def test_health() -> None:
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert "shonen-flash" in body["presets"]


def test_schema() -> None:
    r = client.get("/schema")
    assert r.status_code == 200
    sch = r.json()
    assert "ChromaKey" in sch["effects"]
    assert "cut" in sch["op_types"]
    assert sch["profiles"]["shonen"]["fps"]["num"] == 24


def test_validate_ok() -> None:
    r = client.post("/project/validate", json={"project": base_project()}).json()
    assert r["ok"] is True
    assert r["clips"] == 2
    assert r["duration"] > 10


def test_validate_overlap_flagged() -> None:
    p = base_project()
    p["clips"][1]["position"] = 0.0  # overlaps c1
    r = client.post("/project/validate", json={"project": p}).json()
    assert r["ok"] is False
    assert any("overlap" in i for i in r["issues"])


def test_edit_apply_cut_and_speed() -> None:
    ops = [
        {"op": "cut", "clip_id": "c1", "at": 3.0},
        {"op": "speed", "clip_id": "c1-b", "speed": 2.0},
    ]
    r = client.post("/edit/apply", json={"project": base_project(), "ops": ops,
                                         "message": "test cut"}).json()
    assert r["ok"] is True, r
    clip_ids = [c["id"] for c in r["project"]["clips"]]
    assert "c1-a" in clip_ids and "c1-b" in clip_ids
    c1b = next(c for c in r["project"]["clips"] if c["id"] == "c1-b")
    assert c1b["speed"] == 2.0


def test_edit_apply_chroma_and_text() -> None:
    ops = [
        {"op": "add_effect", "clip_id": "c1",
         "effect": {"type": "ChromaKey", "params": {"background": "#00FF00", "tolerance": 0.18}}},
        {"op": "set_text", "clip_id": "c2", "text": {"content": "第2話:疾風", "style": "anime"}},
    ]
    r = client.post("/edit/apply", json={"project": base_project(), "ops": ops}).json()
    assert r["ok"] is True
    c1 = next(c for c in r["project"]["clips"] if c["id"] == "c1")
    assert any(e["type"] == "ChromaKey" for e in c1["effects"])
    c2 = next(c for c in r["project"]["clips"] if c["id"] == "c2")
    assert c2["text"]["content"] == "第2話:疾風"


def test_edit_apply_unknown_op_is_reported() -> None:
    r = client.post("/edit/apply", json={"project": base_project(),
                                          "ops": [{"op": "hologram", "clip_id": "c1"}]}).json()
    assert r["ok"] is False
    assert any(e["event"] == "error" for e in r["events"])


def test_generic_clip_endpoints() -> None:
    p = base_project()
    r = client.post("/clip/speed", json={"project": p, "clip_id": "c1", "speed": 0.5}).json()
    c1 = next(c for c in r["project"]["clips"] if c["id"] == "c1")
    assert c1["speed"] == 0.5
    r = client.post("/clip/cut", json={"project": p, "clip_id": "c1", "at": 4.0}).json()
    ids = [c["id"] for c in r["project"]["clips"]]
    assert "c1-a" in ids and "c1-b" in ids  # speed engine cut naming logic


def test_render_job_stub() -> None:
    r = client.post("/project/render", json={"project": base_project(), "preset": "shonen-flash"}).json()
    job_id = r["job_id"]
    assert r["status"] == "queued"
    s = client.get(f"/render/{job_id}").json()
    assert s["status"] == "done"
    assert s["output"].endswith(".mp4")


def test_analyze() -> None:
    r = client.post("/analyze", json={"project": base_project()}).json()
    assert "scenes_hint" in r and len(r["scenes_hint"]) > 0


def test_events_recent_has_data() -> None:
    push_event({"event": "unit-test"})
    r = client.get("/events/recent").json()
    assert any(e.get("event") == "unit-test" for e in r["events"])


def test_events_sse_streams_hello() -> None:
    with client.stream("GET", "/events") as resp:
        assert resp.status_code == 200
        first = next(iter(resp.iter_lines()))
        assert first.startswith("data: ")
        payload = json.loads(first.split("data: ", 1)[1])
        assert payload["event"] == "hello"
