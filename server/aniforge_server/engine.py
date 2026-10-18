"""AniForge engine — OpenShot-compatible timeline core (AsProject).

Domain concepts and JSON format ported from OpenShot / openshot-qt:
  reader/writer, layers, clips with start/end cuts, keyframes (Point/Coordinate),
  effects, markers. Re-imagined anime-first.
"""
from __future__ import annotations

import copy
import json
import math
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

EPS = 1e-6

PROFILES: Dict[str, Dict[str, Any]] = {
    "shonen":  {"width": 1920, "height": 1080, "fps": {"num": 24, "den": 1}},
    "shojo":   {"width": 1080, "height": 1920, "fps": {"num": 30, "den": 1}},
    "seinen":  {"width": 1920, "height": 1080, "fps": {"num": 24, "den": 1}},
    "movie":   {"width": 3840, "height": 2160, "fps": {"num": 24, "den": 1}},
    "standard":{"width": 1920, "height": 1080, "fps": {"num": 30, "den": 1}},
}

EFFECT_TYPES = {
    "Fade":       {"params": ["in", "out"]},
    "ChromaKey":  {"params": ["background", "tolerance", "softness"]},
    "Shift":      {"params": ["x", "y"]},
    "Crop":       {"params": ["left", "right", "top", "bottom"]},
    "Glow":       {"params": ["intensity", "threshold"]},
    "Speed":      {"params": ["speed"]},
    "SaiGaki":    {"params": ["width", "color", "glow"]},   # thin-line/border glow (anime border)
    "Sharpen":    {"params": ["amount"]},
    "Blur":       {"params": ["amount"]},
    "Negate":     {"params": []},
    "Mirror":     {"params": ["mode"]},
    "Saturation": {"params": ["amount"]},
    "Contrast":   {"params": ["amount"]},
    "Deinterleave": {"params": ["mode"]},
}

TEXT_STYLES = {
    "anime":      {"font": "Noto Sans JP", "bold": True,  "outline": 2},
    "anime-soft": {"font": "Noto Sans JP", "bold": False, "outline": 1},
    "manga":      {"font": "Noto Serif JP", "bold": True,  "outline": 3},
}


# ---------------------------------------------------------------- keyframes
@dataclass
class Point:
    X: float            # frame number
    Y: float            # value
    interpolation: str = "BEZIER"   # LINEAR | BEZIER | CONSTANT

    def to_dict(self) -> Dict[str, Any]:
        return {"co": {"X": self.X, "Y": self.Y}, "interpolation": self.interpolation}


class Keyframe:
    """OpenShot-style keyframe: points list, sample by frame."""

    def __init__(self, points: Optional[List[Point]] = None, default: float = 1.0):
        self.points: List[Point] = points or [Point(0, default)]
        self.default = default

    @staticmethod
    def parse(raw: Any, default: float = 1.0) -> "Keyframe":
        if raw is None:
            return Keyframe(default=default)
        if isinstance(raw, (int, float)):
            return Keyframe(default=float(raw))
        if isinstance(raw, dict):
            pts = raw.get("points") or []
            pts = [Point(p["co"]["X"], p["co"]["Y"], p.get("interpolation", "BEZIER")) for p in pts]
            return Keyframe(pts or [Point(0, default)], default)
        return Keyframe(default=default)

    def add_point(self, pt: Point) -> None:
        self.points.append(pt)
        self.points.sort(key=lambda p: p.X)

    def sample(self, frame: float) -> float:
        pts = sorted(self.points, key=lambda p: p.X)
        if not pts:
            return self.default
        if frame <= pts[0].X:
            return pts[0].Y
        if frame >= pts[-1].X:
            return pts[-1].Y
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            if a.X <= frame <= b.X:
                if b.X - a.X < EPS or a.interpolation == "CONSTANT":
                    return b.Y if a.interpolation == "CONSTANT" else a.Y
                t = (frame - a.X) / (b.X - a.X)
                if a.interpolation == "LINEAR":
                    return a.Y + (b.Y - a.Y) * t
                # bezier-ish smoothstep
                s = t * t * (3 - 2 * t)
                return a.Y + (b.Y - a.Y) * s
        return self.default

    def to_dict(self) -> Dict[str, Any]:
        return {"points": [p.to_dict() for p in sorted(self.points, key=lambda q: q.X)],
                "default": self.default}


# ---------------------------------------------------------------- core model
@dataclass
class Reader:
    path: str
    has_video: bool = True
    has_audio: bool = True
    duration: float = 0.0
    media_type: str = "video"       # video | audio | image

    @staticmethod
    def parse(raw: Dict[str, Any]) -> "Reader":
        return Reader(
            path=str(raw.get("path", "")),
            has_video=bool(raw.get("has_video", True)),
            has_audio=bool(raw.get("has_audio", True)),
            duration=float(raw.get("duration", 0.0)),
            media_type=str(raw.get("media_type", "video")),
        )


@dataclass
class Effect:
    type: str
    params: Dict[str, Any] = field(default_factory=dict)

    @staticmethod
    def parse(raw: Dict[str, Any]) -> "Effect":
        return Effect(type=str(raw.get("type", "Fade")),
                      params=dict(raw.get("params") or raw.get("keys") or {}))


@dataclass
class TextStyle:
    content: str
    style: str = "anime"
    position: str = "center"

    @staticmethod
    def parse(raw: Dict[str, Any]) -> "TextStyle":
        return TextStyle(content=str(raw.get("content", "")),
                         style=str(raw.get("style", "anime")),
                         position=str(raw.get("position", "center")))


@dataclass
class Clip:
    id: str
    layer: int
    reader: Reader
    position: float = 0.0      # timeline placement (seconds)
    start: float = 0.0         # cut-in inside source (OpenShot semantics)
    end: float = 100.0         # cut-out inside source
    speed: float = 1.0
    effects: List[Effect] = field(default_factory=list)
    keyframes: Dict[str, Keyframe] = field(default_factory=dict)
    text: Optional[TextStyle] = None
    crop: Optional[Dict[str, Any]] = None
    mask: Optional[str] = None

    # ---- OpenShot semantics ----
    def duration(self) -> float:
        """Playback length on the timeline: (end - start) / speed."""
        if self.speed <= EPS:
            self.speed = EPS
        return max(0.0, (self.end - self.start) / self.speed)

    def time_at(self, timeline_t: float) -> float:
        """Source time for a timeline time (with speed)."""
        return self.start + (timeline_t - self.position) * self.speed

    def frame_at(self, timeline_t: float, fps: float) -> float:
        return self.time_at(timeline_t) * fps

    def value_at(self, name: str, timeline_t: float, fps: float) -> float:
        kf = self.keyframes.get(name)
        if kf is None:
            return 1.0 if name == "alpha" else 0.0
        return kf.sample(self.frame_at(timeline_t, fps))

    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "id": self.id, "layer": self.layer,
            "reader": {"path": self.reader.path, "has_video": self.reader.has_video,
                       "has_audio": self.reader.has_audio, "duration": self.reader.duration,
                       "media_type": self.reader.media_type},
            "position": self.position, "start": self.start, "end": self.end,
            "speed": self.speed,
        }
        if self.effects:
            d["effects"] = [{"type": e.type, "params": e.params} for e in self.effects]
        if self.keyframes:
            d["keyframes"] = {k: v.to_dict() for k, v in self.keyframes.items()}
        if self.text:
            d["text"] = {"content": self.text.content, "style": self.text.style,
                         "position": self.text.position}
        if self.crop:
            d["crop"] = self.crop
        if self.mask:
            d["mask"] = self.mask
        return d

    @staticmethod
    def parse(raw: Dict[str, Any], clip_id: Optional[str] = None) -> "Clip":
        kf_raw = raw.get("keyframes") or {}
        keyframes = {name: Keyframe.parse(val) for name, val in kf_raw.items()}
        text = TextStyle.parse(raw["text"]) if raw.get("text") else None
        return Clip(
            id=str(raw.get("id") or clip_id or f"c{int(time_now()*1000)%10_000_000}"),
            layer=int(raw.get("layer", 0)),
            reader=Reader.parse(raw.get("reader") or {}),
            position=float(raw.get("position", 0.0)),
            start=float(raw.get("start", 0.0)),
            end=float(raw.get("end", 100.0)),
            speed=float(raw.get("speed", 1.0)) or 1.0,
            effects=[Effect.parse(e) for e in (raw.get("effects") or [])],
            keyframes=keyframes,
            text=text,
            crop=raw.get("crop"),
            mask=raw.get("mask"),
        )


@dataclass
class Marker:
    position: float
    label: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {"position": self.position, "label": self.label}


@dataclass
class AsProject:
    fps_num: int = 24
    fps_den: int = 1
    width: int = 1920
    height: int = 1080
    profile: str = "shonen"
    layers: List[Dict[str, Any]] = field(default_factory=lambda: [{"id": 0, "name": "V1"}])
    clips: List[Clip] = field(default_factory=list)
    markers: List[Marker] = field(default_factory=list)

    # ---------- serialization / parse ----------
    def to_dict(self) -> Dict[str, Any]:
        d: Dict[str, Any] = {
            "version": 1,
            "fps": {"num": self.fps_num, "den": self.fps_den},
            "width": self.width, "height": self.height,
            "profile": self.profile,
            "layers": self.layers,
            "clips": [c.to_dict() for c in self.clips],
        }
        if self.markers:
            d["markers"] = [m.to_dict() for m in self.markers]
        return d

    @staticmethod
    def parse(raw: Dict[str, Any]) -> "AsProject":
        fps = raw.get("fps") or {"num": 24, "den": 1}
        p = AsProject(
            fps_num=int(fps.get("num", 24)), fps_den=int(fps.get("den", 1)),
            width=int(raw.get("width", 1920)), height=int(raw.get("height", 1080)),
            profile=str(raw.get("profile", "shonen")),
            layers=list(raw.get("layers") or [{"id": 0, "name": "V1"}]),
        )
        p.clips = [Clip.parse(c) for c in (raw.get("clips") or [])]
        p.markers = [Marker(float(m["position"]), str(m.get("label", "")))
                     for m in (raw.get("markers") or [])]
        return p

    # ---------- derived ----------
    @property
    def fps(self) -> float:
        return self.fps_num / max(1, self.fps_den)

    def duration(self) -> float:
        if not self.clips:
            return 0.0
        return max(c.position + c.duration() for c in self.clips)

    def normalized_clips(self) -> List[Clip]:
        """Clips sorted by start time (layer asc, position asc) — OpenShot does the same."""
        return sorted(self.clips, key=lambda c: (c.layer, c.position))

    def layer_ids(self) -> List[int]:
        ids = sorted({c.layer for c in self.clips} | {l.get("id", 0) for l in self.layers})
        return ids

    # ---------- mutations (all pure-ish; return events) ----------
    def add_clip(self, clip: Clip) -> Dict[str, Any]:
        if any(c.id == clip.id for c in self.clips):
            raise ValueError(f"duplicate clip id: {clip.id}")
        self.clips.append(clip)
        if not any(l.get("id") == clip.layer for l in self.layers):
            self.layers.append({"id": clip.layer, "name": f"V{clip.layer + 1}"})
        return {"event": "clip.add", "clip_id": clip.id}

    def clamp_all(self) -> None:
        for c in self.clips:
            if c.end <= c.start:
                c.end = c.start + 1.0
            if c.speed <= EPS:
                c.speed = 1.0
            if c.position < 0:
                c.position = 0.0

    def validate(self) -> List[str]:
        issues: List[str] = []
        seen: set = set()
        for c in self.clips:
            if c.id in seen:
                issues.append(f"duplicate clip id: {c.id}")
            seen.add(c.id)
            if c.end <= c.start:
                issues.append(f"clip {c.id}: end <= start (degenerate cut)")
            if c.speed <= 0:
                issues.append(f"clip {c.id}: speed <= 0")
            if c.reader.path.startswith(("http://", "https://")) is False and not c.reader.path:
                issues.append(f"clip {c.id}: empty reader path")
        for m in self.markers:
            if m.position < 0:
                issues.append(f"marker at {m.position}: negative position")
        ov = self._overlaps()
        for a, b in ov:
            issues.append(f"clips {a.id} and {b.id} overlap on layer {a.layer} "
                          f"({a.position:.2f}..{a.position + a.duration():.2f})")
        return issues

    def _overlaps(self) -> List[Tuple[Clip, Clip]]:
        out: List[Tuple[Clip, Clip]] = []
        by_layer: Dict[int, List[Clip]] = {}
        for c in self.clips:
            by_layer.setdefault(c.layer, []).append(c)
        for _layer, group in by_layer.items():
            group.sort(key=lambda c: c.position)
            for i in range(len(group) - 1):
                a, b = group[i], group[i + 1]
                if a.position + a.duration() > b.position + 1e-6:
                    out.append((a, b))
        return out


def time_now() -> float:
    import time
    return time.time()


# ---------------------------------------------------------------- operations
class Ops:
    """Pure timeline operations — each returns (project, events)."""

    @staticmethod
    def cut(p: AsProject, clip_id: str, at: float) -> Tuple[AsProject, List[Dict[str, Any]]]:
        """Split a clip at timeline position `at` (OpenShot's cut/razor)."""
        src = next(c for c in p.clips if c.id == clip_id)
        if not (src.position < at < src.position + src.duration()):
            raise ValueError(f"cut point {at} outside clip {clip_id} ({src.position}..{src.position + src.duration()})")
        src_end = src.end
        mid_src = src.time_at(at)
        left, right = copy.deepcopy(src), copy.deepcopy(src)
        left.id, right.id = f"{src.id}-a", f"{src.id}-b"
        left.end = mid_src
        right.start = mid_src
        right.end = src_end
        right.position = at
        p.clips.remove(src)
        p.clips.extend([left, right])
        return p, [{"event": "clip.cut", "clip_id": clip_id, "at": at,
                    "left": left.id, "right": right.id}]

    @staticmethod
    def speed(p: AsProject, clip_id: str, speed: float,
              ramp: Optional[List[Tuple[float, float]]] = None,
              ) -> Tuple[AsProject, List[Dict[str, Any]]]:
        """Constant speed, or a ramp: [(t0, s0), (t1, s1), ...] timeline-seconds."""
        src = next(c for c in p.clips if c.id == clip_id)
        if ramp:
            pts = [Point(src.frame_at(t, p.fps), s) for (t, s) in ramp]
            src.keyframes["speed"] = Keyframe(pts)
            src.speed = 1.0
        else:
            src.speed = float(speed)
        return p, [{"event": "clip.speed", "clip_id": clip_id, "speed": speed if not ramp else "ramp"}]

    @staticmethod
    def add_effect(p: AsProject, clip_id: str, effect: Effect) -> Tuple[AsProject, List[Dict[str, Any]]]:
        src = next(c for c in p.clips if c.id == clip_id)
        src.effects.append(effect)
        return p, [{"event": "clip.effect.add", "clip_id": clip_id, "effect": effect.type}]

    @staticmethod
    def move(p: AsProject, clip_id: str, position: float, layer: Optional[int] = None,
             ) -> Tuple[AsProject, List[Dict[str, Any]]]:
        src = next(c for c in p.clips if c.id == clip_id)
        old = (src.position, src.layer)
        src.position = float(position)
        if layer is not None:
            src.layer = int(layer)
            if not any(l.get("id") == src.layer for l in p.layers):
                p.layers.append({"id": src.layer, "name": f"V{src.layer + 1}"})
        return p, [{"event": "clip.move", "clip_id": clip_id, "from": old,
                    "to": (src.position, src.layer)}]

    @staticmethod
    def set_text(p: AsProject, clip_id: str, content: str, style: str = "anime",
                 position: str = "center") -> Tuple[AsProject, List[Dict[str, Any]]]:
        src = next(c for c in p.clips if c.id == clip_id)
        src.text = TextStyle(content, style, position)
        return p, [{"event": "clip.text", "clip_id": clip_id, "content": content}]

    @staticmethod
    def crop(p: AsProject, clip_id: str, ratio: str) -> Tuple[AsProject, List[Dict[str, Any]]]:
        valid = {"1:1", "4:5", "9:16", "16:9", "21:9"}
        if ratio not in valid:
            raise ValueError(f"ratio must be one of {valid}")
        src = next(c for c in p.clips if c.id == clip_id)
        src.crop = {"ratio": ratio}
        return p, [{"event": "clip.crop", "clip_id": clip_id, "ratio": ratio}]

    @staticmethod
    def keyframe(p: AsProject, clip_id: str, prop: str, frame: float, value: float,
                 interpolation: str = "BEZIER") -> Tuple[AsProject, List[Dict[str, Any]]]:
        src = next(c for c in p.clips if c.id == clip_id)
        kf = src.keyframes.get(prop) or Keyframe(default=1.0)
        kf.add_point(Point(frame, value, interpolation))
        src.keyframes[prop] = kf
        return p, [{"event": "clip.keyframe", "clip_id": clip_id, "prop": prop,
                    "frame": frame, "value": value}]

    @staticmethod
    def marker(p: AsProject, position: float, label: str) -> Tuple[AsProject, List[Dict[str, Any]]]:
        p.markers.append(Marker(position, label))
        return p, [{"event": "marker.add", "position": position, "label": label}]


# ---------------------------------------------------------------- presets
PRESETS: Dict[str, Dict[str, Any]] = {
    "shonen-flash": {
        "description": "High-energy shonen cut: whip pans, speed ramps, flash frames",
        "steps": [
            {"op": "cut_every", "interval": 0.8},
            {"op": "speed_ramp", "curve": [(0, 0.6), (0.5, 1.9), (1.0, 0.7)]},
            {"op": "flash_frame", "at": "every_cut", "duration": 0.05},
            {"op": "glow", "intensity": 0.7},
        ],
    },
    "shojo-sparkle": {
        "description": "Soft shojo look: slow crossfades, bloom, pastel glow",
        "steps": [
            {"op": "fade_in_out", "dur": 0.4},
            {"op": "glow", "intensity": 0.45},
            {"op": "saturation", "amount": 1.12},
        ],
    },
    "seinen-grit": {
        "description": "Dark seinen feel: desaturation, hard cuts, film grain",
        "steps": [
            {"op": "desaturate", "amount": 0.35},
            {"op": "contrast", "amount": 1.15},
            {"op": "cut_every", "interval": 2.2},
        ],
    },
    "anime-studio-standard": {
        "description": "Clean standard anime edit: fades, subtle sharpen, OP/ED text safe",
        "steps": [
            {"op": "fade_in_out", "dur": 0.25},
            {"op": "sharpen", "amount": 0.3},
        ],
    },
    "sns-4x5": {
        "description": "4:5 social publish: crop center + fade",
        "steps": [
            {"op": "crop", "ratio": "4:5"},
            {"op": "fade_in_out", "dur": 0.2},
        ],
    },
}

__all__ = ["AsProject", "Clip", "Reader", "Effect", "Keyframe", "Point", "Marker",
           "Ops", "PRESETS", "PROFILES", "EFFECT_TYPES", "TEXT_STYLES", "TextStyle"]
