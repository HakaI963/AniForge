"""Engine unit tests — .osp semantics, keyframes, cuts, overlaps."""
from __future__ import annotations

import json

import pytest

from aniforge_server.engine import AsProject, Clip, Keyframe, Ops, Effect, PRESETS


def project_yaml() -> dict:  # json only, name kept for readability
    return {
        "version": 1,
        "fps": {"num": 24, "den": 1},
        "width": 1920, "height": 1080,
        "layers": [{"id": 0, "name": "V1"}],
        "clips": [
            {
                "id": "c1", "layer": 0,
                "reader": {"path": "/music/beat_drop.mp4", "duration": 11.5},
                "position": 0.0, "start": 0.5, "end": 8.0,
                "speed": 1.0,
            },
            {
                "id": "c2", "layer": 0,
                "reader": {"path": "/audio/kiai.mp4", "duration": 9.9},
                "position": 7.5, "start": 0.0, "end": 6.0,
                "effects": [{"type": "Fade", "params": {"in": 0.4, "out": 0.4}}],
            },
        ],
    }


def test_osp_roundtrip() -> None:
    p = AsProject.parse(project_yaml())
    d = json.loads(json.dumps(p.to_dict()))
    assert d["version"] == 1
    assert p.duration() > 11.0


def test_cut_splits_at_timeline_time() -> None:
    p = AsProject.parse(project_yaml())
    p2, evs = Ops.cut(p, "c1", 4.6)
    ids = [c.id for c in p2.clips]
    assert evs[0]["event"] == "clip.cut"
    assert "c1-a" in ids and "c1-b" in ids
    left = next(c for c in p2.clips if c.id == "c1-a")
    right = next(c for c in p2.clips if c.id == "c1-b")
    # boundary continuity: left ends at source time that right begins
    src_left_end = left.end
    assert abs(right.start - src_left_end) < 1e-4
    assert abs(right.position - 4.6) < 1e-4


def test_cut_outside_clip_fails() -> None:
    p = AsProject.parse(project_yaml())
    with pytest.raises(ValueError):
        Ops.cut(p, "c1", 99.0)


def test_speed_keyframe_ramp() -> None:
    p = AsProject.parse(project_yaml())
    c = next(c for c in p.clips if c.id == "c1")
    _, _ = Ops.speed(p, "c1", 1.0, ramp=[(1.0, 4.0), (2.0, 0.5)])
    assert "speed" in c.keyframes
    kf = c.keyframes["speed"]
    v0, v1 = kf.sample(24), kf.sample(48)
    assert v0 > v1  # decelerating ramp


def test_overlap_detection() -> None:
    p = AsProject.parse(project_yaml())
    p.clips[1].position = 0.0
    issues = p.validate()
    assert any("overlap" in i for i in issues)


def test_episode_speed_changes_duration() -> None:
    p = AsProject.parse(project_yaml())
    c = p.clips[0]
    before = c.duration()
    c.speed = 2.0
    assert abs(c.duration() - before / 2) < 1e-9


def test_presets_defined() -> None:
    for name in ("shonen-flash", "shojo-sparkle", "seinen-grit"):
        assert name in PRESETS
