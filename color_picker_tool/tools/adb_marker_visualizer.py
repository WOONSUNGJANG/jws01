"""
오토클릭짱(ATX_PIC) 로그 기반 클릭/스와이프 시각화 도구.

- USB 디버깅(ADB) 상태에서 `adb logcat` 출력(또는 stdin)을 읽어
  클릭/스와이프 좌표를 PC 화면(캔버스) 위에 색으로 표시합니다.
- 화면 해상도/가로세로 전환도 로그에서 자동 인식합니다.

필수: Python 3.9+ (tkinter 포함)
"""

from __future__ import annotations

import argparse
import queue
import re
import subprocess
import sys
import threading
import time
import bisect
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Tuple, List, Dict

try:
    import tkinter as tk
    from tkinter import filedialog
except Exception as e:
    raise SystemExit(f"tkinter를 불러올 수 없습니다. Python 기본 설치를 확인하세요. err={e}")


@dataclass
class ScreenSize:
    w: int
    h: int

    @property
    def is_landscape(self) -> bool:
        return self.w >= self.h

    def clamp(self) -> "ScreenSize":
        w = int(self.w) if int(self.w) > 0 else 1
        h = int(self.h) if int(self.h) > 0 else 1
        return ScreenSize(w=w, h=h)


@dataclass
class Event:
    ts: float
    kind: str  # tap|swipe
    p0: Tuple[int, int]
    p1: Optional[Tuple[int, int]] = None
    color: str = "#ef4444"
    label: str = ""
    cat: int = 1  # 1..7 (오토클릭짱 로그 카테고리)


RE_START_PROJ = re.compile(r"startProjection\s+screen=(\d+)x(\d+)")
RE_RESIZED = re.compile(r"Captured content resized to\s+(\d+)x(\d+)")
RE_SIZE_CHANGED = re.compile(r"Screen size changed\s+\d+\s*x\s*\d+\s*->\s*(\d+)\s*x\s*(\d+)")

# 클릭
RE_TAP1 = re.compile(r"\btap\((\d+),(\d+)\)")
RE_CLICK1 = re.compile(r"\bclick\((\d+),(\d+)\)")

# 스와이프(서비스 실행 로그)
RE_SWIPE_FROM_TO = re.compile(r"\bswipe\b.*from=\((\d+),(\d+)\)\s+to=\((\d+),(\d+)\)")
RE_SWIPE_CALL = re.compile(r"\bswipe\((\d+),(\d+)->(\d+),(\d+)\)")

# Point 문자열 케이스(혹시 출력이 Point(...) 형태인 경우)
RE_POINT = re.compile(r"Point\((?:x=)?(\d+),\s*(?:y=)?(\d+)\)")
RE_FROM_POINT_TO_POINT = re.compile(
    r"\bfrom=(Point\([^)]+\)|\(\d+,\d+\))\s+to=(Point\([^)]+\)|\(\d+,\d+\))"
)

# adb logcat -v time 라인 앞부분 시간(월-일 시:분:초.밀리초)
RE_LOGCAT_TIME = re.compile(r"\b(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\b")

# ATX_STREAM (ScreenCaptureService 로그에 포함되는 스트림 라인)
RE_ATX_STREAM_MARKER = re.compile(r"\bATX_STREAM\s+MARKER\b")
RE_ATX_KV = re.compile(r"(\b[a-zA-Z_][a-zA-Z0-9_]*\b)=([^\s]+)")


def pick_color(line: str) -> str:
    s = line
    if "swipe" in s:
        return "#06b6d4"  # cyan
    if "color_module" in s:
        return "#f97316"  # orange
    if "image_module" in s:
        return "#a855f7"  # purple
    if "soloVerify" in s or "solo_verify" in s:
        return "#22c55e"  # green
    return "#ef4444"  # red


def classify_cat(line: str) -> int:
    """
    로그 라인 텍스트를 기반으로 1..7 카테고리를 추정.
    - 1.순번 2.독립 3.스와이프 4.단독 5.방향모듈 6.색상모듈 7.이미지모듈
    """
    s = line
    # 스트림 로그에 cat=숫자 가 있으면 우선 사용
    m = re.search(r"\bcat=(\d)\b", s)
    if m:
        try:
            v = int(m.group(1))
            if 1 <= v <= 7:
                return v
        except Exception:
            pass
    # 우선순위: 구체적인 모듈 키워드가 먼저
    if "image_module" in s:
        return 7
    if "color_module" in s:
        return 6
    # 방향모듈은 로그에 "module idx=" 형태가 많음 (swipe/module dir 포함)
    if re.search(r"\bmodule\b", s) and "color_module" not in s and "image_module" not in s:
        return 5
    if "soloVerify" in s or "solo_verify" in s or "solo_main" in s or "solo_item" in s:
        return 4
    if "swipe" in s:
        return 3
    if "independent" in s or "독립" in s:
        return 2
    return 1


def cat_color(cat: int) -> str:
    # 범례/표시용 고정 컬러(가독성)
    return {
        1: "#ef4444",  # red
        2: "#f59e0b",  # amber
        3: "#06b6d4",  # cyan
        4: "#22c55e",  # green
        5: "#3b82f6",  # blue
        6: "#f97316",  # orange
        7: "#a855f7",  # purple
    }.get(int(cat), "#ef4444")


def cat_name(cat: int) -> str:
    # 요청: "1.순번 ... 7.이미지모듈"
    return {
        1: "1.순번",
        2: "2.독립",
        3: "3.스와이프",
        4: "4.단독",
        5: "5.방향모듈",
        6: "6.색상모듈",
        7: "7.이미지모듈",
    }.get(int(cat), str(cat))


def parse_size_from_line(line: str) -> Optional[ScreenSize]:
    for rx in (RE_START_PROJ, RE_SIZE_CHANGED, RE_RESIZED):
        m = rx.search(line)
        if m:
            w = int(m.group(1))
            h = int(m.group(2))
            return ScreenSize(w=w, h=h).clamp()
    return None


def parse_point_token(tok: str) -> Optional[Tuple[int, int]]:
    tok = tok.strip()
    if tok.startswith("(") and tok.endswith(")"):
        inner = tok[1:-1]
        parts = inner.split(",")
        if len(parts) == 2:
            try:
                return int(parts[0].strip()), int(parts[1].strip())
            except Exception:
                return None
    m = RE_POINT.search(tok)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None


def parse_event_from_line(line: str) -> Optional[Event]:
    cat = classify_cat(line)
    col = cat_color(cat)
    # swipe(from,to)
    m = RE_SWIPE_FROM_TO.search(line)
    if m:
        fx, fy, tx, ty = map(int, m.groups())
        return Event(ts=time.time(), kind="swipe", p0=(fx, fy), p1=(tx, ty), color=col, label="swipe", cat=cat)

    m = RE_SWIPE_CALL.search(line)
    if m:
        fx, fy, tx, ty = map(int, m.groups())
        return Event(ts=time.time(), kind="swipe", p0=(fx, fy), p1=(tx, ty), color=col, label="swipe", cat=cat)

    # swipe(chain) from=Point(...) to=Point(...)
    m = RE_FROM_POINT_TO_POINT.search(line)
    if m:
        p0 = parse_point_token(m.group(1))
        p1 = parse_point_token(m.group(2))
        if p0 and p1:
            return Event(ts=time.time(), kind="swipe", p0=p0, p1=p1, color=col, label="swipe", cat=cat)

    # tap/click
    m = RE_TAP1.search(line) or RE_CLICK1.search(line)
    if m:
        x, y = int(m.group(1)), int(m.group(2))
        return Event(ts=time.time(), kind="tap", p0=(x, y), p1=None, color=col, label="tap", cat=cat)

    return None


def try_get_device_size(serial: Optional[str]) -> Optional[ScreenSize]:
    # `adb shell wm size` 예: "Physical size: 1080x2400"
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += ["shell", "wm", "size"]
    try:
        out = subprocess.check_output(cmd, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="ignore")
    except Exception:
        return None
    m = re.search(r"Physical size:\s*(\d+)\s*x\s*(\d+)", out)
    if not m:
        m = re.search(r"Override size:\s*(\d+)\s*x\s*(\d+)", out)
    if m:
        return ScreenSize(w=int(m.group(1)), h=int(m.group(2))).clamp()
    return None


def _parse_logcat_time_seconds(line: str) -> Optional[float]:
    """
    adb logcat -v time 포맷의 시간을 '하루 내 초'로 변환(연/월 경계는 고려하지 않음).
    재생용 상대시간 계산에만 사용.
    """
    m = RE_LOGCAT_TIME.search(line)
    if not m:
        return None
    # month/day는 무시하고, HH:MM:SS.mmm만 사용(동일 로그 파일 내 상대시간에는 충분)
    hh = int(m.group(3))
    mm = int(m.group(4))
    ss = int(m.group(5))
    ms = int(m.group(6))
    return hh * 3600.0 + mm * 60.0 + ss + (ms / 1000.0)


def load_replay_lines(path: str) -> List[Tuple[float, str]]:
    """
    저장된 로그 파일을 읽어서 (상대초, 라인) 리스트로 만든다.
    - '# ...' 헤더 라인은 무시
    - logcat 시간 파싱 실패 라인은 고정 간격(기본 30ms)으로 증가시켜 "재생"이 보이게 한다.
    """
    p = Path(path)
    if not p.exists():
        return []
    raw_lines = p.read_text(encoding="utf-8", errors="ignore").splitlines()
    out: List[Tuple[float, str]] = []
    t0_abs: Optional[float] = None
    last_rel = 0.0
    had_any_ts = False
    fallback_step = 0.030  # 30ms (시간 없는 라인용)
    max_gap = 0.25  # (중요) 타임스탬프가 있어도 긴 공백은 압축해서 "재생이 안 되는 느낌" 방지
    for ln in raw_lines:
        if not ln:
            continue
        if ln.startswith("#"):
            continue
        t_abs = _parse_logcat_time_seconds(ln)
        if t_abs is None:
            # 시간 없는 라인은 조금씩 증가(안 보이는 "즉시 끝" 방지)
            last_rel = last_rel + fallback_step
            out.append((last_rel, ln))
            continue
        had_any_ts = True
        if t0_abs is None:
            t0_abs = t_abs
        rel_raw = max(0.0, t_abs - (t0_abs or t_abs))
        # (압축) 이전 rel 대비 너무 큰 점프는 제한
        if rel_raw > last_rel + max_gap:
            rel = last_rel + max_gap
        else:
            rel = rel_raw
        last_rel = rel
        out.append((rel, ln))
    # 전부 fallback만인 경우, 너무 길게 느려지지 않게 약간 압축(라인 수가 매우 많으면 step 축소)
    if not had_any_ts and len(out) > 8000:
        # 8천줄 이상이면 step을 줄여 적당히 진행되게
        step2 = 0.010
        cur = 0.0
        out2: List[Tuple[float, str]] = []
        for _t, line in out:
            cur += step2
            out2.append((cur, line))
        out = out2
    return out


@dataclass
class MarkerState:
    idx: int
    kind: str
    cat: int
    x: int
    y: int
    ts: float


class VisualizerApp:
    def __init__(self, q: "queue.Queue[str]", initial_size: Optional[ScreenSize], save_fp=None, save_path: Optional[str] = None):
        self.q = q
        self.root = tk.Tk()
        self.root.title("ATX 마커 클릭/스와이프 시각화")

        self.screen = initial_size or ScreenSize(1080, 2400)
        self.events: List[Event] = []
        self.max_events = 600
        self.keep_seconds = 12.0

        # (추가) 수신 로그 저장
        self.save_fp = save_fp
        self.save_path = save_path
        self._save_last_flush = time.time()
        self._save_lines_since_flush = 0

        # (추가) "현시점까지" 스냅샷 저장용 버퍼(원본 라인)
        self._buf_lines: List[str] = []
        self._buf_keep_max = 250_000  # 너무 길어질 때 메모리 보호(초과분은 앞에서 버림)
        self._buf_dropped = 0

        # (추가) 실시간 마커 표시(ATX_STREAM MARKER)
        self.markers: Dict[int, MarkerState] = {}
        self.show_markers = True
        # (요청) 마커는 표시 후 300ms 뒤 자동 삭제
        self.marker_ttl_sec = 0.300
        # (요청) 일부 이벤트(스와이프/단독/방향모듈/색상/이미지)도 300ms 후 삭제
        self.event_ttl_short_sec = 0.300
        self.event_ttl_short_cats = {1, 2, 3, 4, 5, 6, 7}

        self._need_redraw = True
        self._last_canvas_size = (0, 0)

        self.top = tk.Frame(self.root)
        self.top.pack(side=tk.TOP, fill=tk.X)

        self.lbl = tk.Label(self.top, text="", anchor="w", justify="left")
        self.lbl.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=8, pady=6)

        self.btn_clear = tk.Button(self.top, text="지우기", command=self.clear)
        self.btn_clear.pack(side=tk.RIGHT, padx=8, pady=6)

        self.btn_clear_markers = tk.Button(self.top, text="마커지우기", command=self.clear_markers)
        self.btn_clear_markers.pack(side=tk.RIGHT, padx=6, pady=6)

        # (추가) 스냅샷 저장 버튼: 지금까지 받은 원본 로그 라인을 별도 파일로 저장
        self.btn_save_now = tk.Button(self.top, text="저장", command=self.save_snapshot_dialog)
        self.btn_save_now.pack(side=tk.RIGHT, padx=6, pady=6)

        # --- 재생/배율 UI ---
        self.btn_open = tk.Button(self.top, text="로그불러오기", command=self.open_replay_dialog)
        self.btn_open.pack(side=tk.RIGHT, padx=6, pady=6)

        self.btn_replay = tk.Button(self.top, text="재생", command=self.toggle_replay)
        self.btn_replay.pack(side=tk.RIGHT, padx=6, pady=6)

        self.btn_restart = tk.Button(self.top, text="처음", command=self.restart_replay)
        self.btn_restart.pack(side=tk.RIGHT, padx=6, pady=6)

        self.speed_var = tk.StringVar(value="1x")
        # (변경) 배속은 "느리게" 의미. 1~10x(1씩)
        self.speed_menu = tk.OptionMenu(
            self.top,
            self.speed_var,
            "1x",
            "2x",
            "3x",
            "4x",
            "5x",
            "6x",
            "7x",
            "8x",
            "9x",
            "10x",
            command=self.on_speed_change,
        )
        self.speed_menu.pack(side=tk.RIGHT, padx=6, pady=6)

        # 줌(배율) UI
        self.zoom_var = tk.DoubleVar(value=1.0)
        self.zoom_scale = tk.Scale(
            self.top,
            from_=0.6,
            to=3.0,
            resolution=0.05,
            orient=tk.HORIZONTAL,
            variable=self.zoom_var,
            length=140,
            label="줌",
            command=lambda _v: self._mark_redraw(),
        )
        self.zoom_scale.pack(side=tk.RIGHT, padx=6, pady=2)

        self.btn_fit = tk.Button(self.top, text="FIT", command=self.reset_view)
        self.btn_fit.pack(side=tk.RIGHT, padx=6, pady=6)

        self.canvas = tk.Canvas(self.root, bg="#0b1220", highlightthickness=0)
        self.canvas.pack(side=tk.TOP, fill=tk.BOTH, expand=True)

        # (추가) 재생 진행 슬라이더(하단)
        self.bottom = tk.Frame(self.root)
        self.bottom.pack(side=tk.BOTTOM, fill=tk.X)
        self.replay_progress_lbl = tk.Label(self.bottom, text="", anchor="w", justify="left")
        self.replay_progress_lbl.pack(side=tk.LEFT, padx=8, pady=6)
        self.replay_seek_var = tk.DoubleVar(value=0.0)
        self._seek_suppress = False
        self._seek_dragging = False
        self.replay_seek = tk.Scale(
            self.bottom,
            from_=0.0,
            to=1.0,
            resolution=0.01,
            orient=tk.HORIZONTAL,
            showvalue=False,
            variable=self.replay_seek_var,
            length=420,
            command=self.on_seek_change,
        )
        # (변경) 진행 표시 + 사용자가 드래그로 시간 이동 가능
        try:
            self.replay_seek.bind("<ButtonPress-1>", self.on_seek_press)
            self.replay_seek.bind("<ButtonRelease-1>", self.on_seek_release)
        except Exception:
            pass
        self.replay_seek.pack(side=tk.RIGHT, fill=tk.X, expand=True, padx=8, pady=6)

        self.root.bind("<Configure>", self.on_resize)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.after(30, self.tick)

        # 팬(드래그로 이동)
        self._pan_dx = 0.0
        self._pan_dy = 0.0
        self._pan_down = None  # (x,y) in canvas
        self.canvas.bind("<ButtonPress-1>", self.on_pan_down)
        self.canvas.bind("<B1-Motion>", self.on_pan_move)
        self.canvas.bind("<ButtonRelease-1>", self.on_pan_up)

        # 재생 상태
        self.replay_path: Optional[str] = None
        self.replay_lines: List[Tuple[float, str]] = []
        self.replay_pos: int = 0
        self.replay_running: bool = False
        # (변경) 배속은 "느리게" 의미로 사용: 2x = 2배 느리게(시간을 2배로 늘림)
        self.replay_slow: float = 1.0
        self.replay_start_real: float = 0.0
        self.replay_start_sim: float = 0.0
        self.replay_total_sim: float = 0.0
        # seek/rebuild 상태(슬라이더로 시간 점프 시)
        self._rebuild_active = False
        self._rebuild_target_pos = 0
        self._rebuild_pos = 0
        self._rebuild_target_sec = 0.0
        self._rebuild_resume_after = False

    def clear(self):
        self.events.clear()
        self._need_redraw = True

    def clear_markers(self):
        self.markers.clear()
        self._need_redraw = True

    def _prune_markers(self):
        if not self.markers:
            return
        ttl = float(self.marker_ttl_sec)
        now = time.time()
        try:
            dead = [k for (k, v) in self.markers.items() if (now - v.ts) > ttl]
            for k in dead:
                try:
                    del self.markers[k]
                except Exception:
                    pass
            if dead:
                self._need_redraw = True
        except Exception:
            return

    def on_resize(self, _ev=None):
        self._need_redraw = True

    def _mark_redraw(self):
        self._need_redraw = True

    def reset_view(self):
        self.zoom_var.set(1.0)
        self._pan_dx = 0.0
        self._pan_dy = 0.0
        self._need_redraw = True

    def on_pan_down(self, ev):
        self._pan_down = (float(ev.x), float(ev.y))

    def on_pan_move(self, ev):
        if not self._pan_down:
            return
        x0, y0 = self._pan_down
        dx = float(ev.x) - x0
        dy = float(ev.y) - y0
        self._pan_dx += dx
        self._pan_dy += dy
        self._pan_down = (float(ev.x), float(ev.y))
        self._need_redraw = True

    def on_pan_up(self, _ev):
        self._pan_down = None

    def _calc_transform(self) -> Tuple[float, float, float, float]:
        cw = max(1, int(self.canvas.winfo_width()))
        ch = max(1, int(self.canvas.winfo_height()))
        margin = 18
        aw = max(1, cw - margin * 2)
        ah = max(1, ch - margin * 2)
        s_fit = min(aw / self.screen.w, ah / self.screen.h)
        zoom = float(self.zoom_var.get() if self.zoom_var else 1.0)
        s = s_fit * zoom
        ox = (cw - self.screen.w * s) / 2.0 + self._pan_dx
        oy = (ch - self.screen.h * s) / 2.0 + self._pan_dy
        return s, ox, oy, float(margin)

    def _to_canvas(self, x: int, y: int) -> Tuple[float, float]:
        s, ox, oy, _ = self._calc_transform()
        return ox + x * s, oy + y * s

    def _prune(self):
        now = time.time()
        # (요청) 스와이프/단독/색상/이미지 이벤트는 300ms TTL, 그 외는 keep_seconds 유지
        keep_default = float(self.keep_seconds)
        keep_short = float(self.event_ttl_short_sec)
        short_cats = self.event_ttl_short_cats
        self.events = [
            e
            for e in self.events
            if (now - e.ts) <= (keep_short if (getattr(e, "cat", 1) in short_cats) else keep_default)
        ]
        if len(self.events) > self.max_events:
            self.events = self.events[-self.max_events :]

    def _update_label(self):
        o = "가로" if self.screen.is_landscape else "세로"
        save_txt = f"   save={self.save_path}" if self.save_fp and self.save_path else ""
        replay_txt = ""
        if self.replay_path:
            st = "RUN" if self.replay_running else "PAUSE"
            try:
                now = time.perf_counter()
                sim = self._replay_sim_time(now) if self.replay_running else self.replay_start_sim
            except Exception:
                sim = 0.0
            total = len(self.replay_lines) if self.replay_lines else 0
            replay_txt = f"   replay={Path(self.replay_path).name} {st} slow={self.speed_var.get()} pos={self.replay_pos}/{total} t={sim:.1f}s"
        self.lbl.config(
            text=f"screen={self.screen.w}x{self.screen.h} ({o})   events={len(self.events)}   keep={self.keep_seconds:.0f}s{save_txt}{replay_txt}"
        )

        # 하단 재생 진행 표시
        try:
            if self.replay_path and self.replay_lines:
                total = max(0.0, float(self.replay_total_sim))
                now = time.perf_counter()
                cur = self._replay_sim_time(now) if self.replay_running else float(self.replay_start_sim)
                cur = max(0.0, min(total, cur))

                def fmt(sec: float) -> str:
                    s = int(max(0.0, sec))
                    return f"{s//60:02d}:{s%60:02d}"

                self.replay_progress_lbl.config(text=f"재생 {fmt(cur)} / {fmt(total)}   (slow {self.speed_var.get()})")

                try:
                    self._seek_suppress = True
                    self.replay_seek.configure(to=max(1.0, total))
                    if not self._seek_dragging:
                        self.replay_seek_var.set(cur)
                finally:
                    self._seek_suppress = False
            else:
                self.replay_progress_lbl.config(text="")
                try:
                    self._seek_suppress = True
                    self.replay_seek.configure(to=1.0)
                    self.replay_seek_var.set(0.0)
                finally:
                    self._seek_suppress = False
        except Exception:
            pass

    def on_seek_press(self, _ev=None):
        if not (self.replay_path and self.replay_lines):
            return
        self._seek_dragging = True

    def on_seek_release(self, _ev=None):
        if not (self.replay_path and self.replay_lines):
            self._seek_dragging = False
            return
        self._seek_dragging = False
        try:
            t = float(self.replay_seek_var.get())
        except Exception:
            t = 0.0
        self.seek_to_time(t)

    def on_seek_change(self, _v=None):
        # 드래그 중에는 라벨에 미리보기만 반영(실제 점프는 release에서)
        if self._seek_suppress:
            return
        if not (self.replay_path and self.replay_lines):
            return
        if not self._seek_dragging:
            return
        try:
            total = float(self.replay_total_sim)
            cur = float(self.replay_seek_var.get())
            cur = max(0.0, min(total, cur))
            def fmt(sec: float) -> str:
                s = int(max(0.0, sec))
                return f"{s//60:02d}:{s%60:02d}"
            self.replay_progress_lbl.config(text=f"이동 {fmt(cur)} / {fmt(total)}   (release하면 적용)")
        except Exception:
            pass

    def seek_to_time(self, target_sec: float):
        if not (self.replay_path and self.replay_lines):
            return
        total = float(self.replay_total_sim)
        t = max(0.0, min(total, float(target_sec)))
        # 재생 중이었다면, 점프 후 계속 재생
        was_running = bool(self.replay_running)
        self.replay_running = False
        try:
            self.btn_replay.config(text="재생")
        except Exception:
            pass
        self._start_rebuild(t, resume_after=was_running)

    def _start_rebuild(self, target_sec: float, resume_after: bool):
        # 큰 로그에서도 UI가 멈추지 않게 tick에서 조금씩 처리한다.
        self._rebuild_active = True
        self._rebuild_target_sec = float(target_sec)
        self._rebuild_resume_after = bool(resume_after)
        times = [t for (t, _line) in self.replay_lines]
        self._rebuild_target_pos = bisect.bisect_right(times, self._rebuild_target_sec)
        self._rebuild_pos = 0
        # 상태 초기화
        self.events.clear()
        self.markers.clear()
        self._need_redraw = True

    def _process_line_impl(self, line: str, do_io: bool):
        if do_io:
            self._save_line(line)
            self._buffer_line(line)

        # ATX_STREAM MARKER 파싱 → 마커 원 표시
        if RE_ATX_STREAM_MARKER.search(line):
            try:
                kv = {m.group(1): m.group(2) for m in RE_ATX_KV.finditer(line)}
                idx = int(kv.get("idx", "0"))
                kind = kv.get("kind", "")
                cat = int(kv.get("cat", "0"))
                x = int(kv.get("xPx", "-1"))
                y = int(kv.get("yPx", "-1"))
                if idx != 0 and x >= 0 and y >= 0:
                    if cat <= 0:
                        cat = classify_cat(line)
                    self.markers[idx] = MarkerState(idx=idx, kind=kind, cat=cat, x=x, y=y, ts=time.time())
                    self._need_redraw = True
            except Exception:
                pass

        sz = parse_size_from_line(line)
        if sz and (sz.w != self.screen.w or sz.h != self.screen.h):
            self.screen = sz
            self._need_redraw = True

        ev = parse_event_from_line(line)
        if ev:
            self.events.append(ev)
            self._prune()
            self._need_redraw = True

    def _save_line(self, line: str):
        fp = self.save_fp
        if not fp:
            return
        try:
            fp.write(line + "\n")
            self._save_lines_since_flush += 1
            now = time.time()
            # 너무 자주 flush하지 않게 디바운스(종료/크래시 대비 최소한의 안전)
            if self._save_lines_since_flush >= 200 or (now - self._save_last_flush) >= 0.5:
                try:
                    fp.flush()
                except Exception:
                    pass
                self._save_last_flush = now
                self._save_lines_since_flush = 0
        except Exception:
            # 저장 중 예외가 나면 UI는 계속 동작
            return

    def _buffer_line(self, line: str):
        # 스냅샷 저장용으로 "원본 라인"을 누적
        try:
            self._buf_lines.append(line)
            if len(self._buf_lines) > self._buf_keep_max:
                over = len(self._buf_lines) - self._buf_keep_max
                if over > 0:
                    del self._buf_lines[0:over]
                    self._buf_dropped += over
        except Exception:
            return

    def save_snapshot_dialog(self):
        # 현재까지 받은 로그를 원하는 파일로 저장
        try:
            logs_dir = Path(__file__).resolve().parent / "logs"
            logs_dir.mkdir(parents=True, exist_ok=True)
            stamp = time.strftime("%Y%m%d_%H%M%S")
            default_name = f"atx_snapshot_{stamp}.txt"
            path = filedialog.asksaveasfilename(
                title="현재까지 로그 저장",
                defaultextension=".txt",
                initialdir=str(logs_dir),
                initialfile=default_name,
                filetypes=[("Text log", "*.txt"), ("All files", "*.*")],
            )
        except Exception:
            path = ""
        if not path:
            return
        self.save_snapshot(path)

    def save_snapshot(self, path: str):
        # 헤더 + 버퍼 전체를 덤프
        try:
            p = Path(path)
            if p.parent:
                try:
                    p.parent.mkdir(parents=True, exist_ok=True)
                except Exception:
                    pass
            with open(str(p), "w", encoding="utf-8", errors="ignore") as f:
                f.write(f"# snapshot_saved_at={time.strftime('%Y-%m-%d %H:%M:%S')}\n")
                f.write(f"# screen={self.screen.w}x{self.screen.h}\n")
                f.write(f"# zoom={self.zoom_var.get() if self.zoom_var else 1.0}\n")
                f.write(f"# mode={'replay' if self.replay_path else 'live'}\n")
                if self.replay_path:
                    f.write(f"# replay_file={self.replay_path}\n")
                    f.write(f"# speed={self.speed_var.get()}\n")
                    f.write(f"# replay_pos={self.replay_pos}/{len(self.replay_lines)}\n")
                if self.save_path:
                    f.write(f"# auto_save_path={self.save_path}\n")
                if self._buf_dropped > 0:
                    f.write(f"# NOTE: buffer_dropped_lines={self._buf_dropped} (kept_last={self._buf_keep_max})\n")
                f.write("\n")
                for ln in self._buf_lines:
                    f.write(ln + "\n")
        except Exception:
            # UI는 계속 동작
            return

    def redraw(self):
        self.canvas.delete("all")
        cw = max(1, int(self.canvas.winfo_width()))
        ch = max(1, int(self.canvas.winfo_height()))
        self._last_canvas_size = (cw, ch)

        s, ox, oy, _ = self._calc_transform()
        x0, y0 = ox, oy
        x1, y1 = ox + self.screen.w * s, oy + self.screen.h * s

        # 폰 영역
        self.canvas.create_rectangle(x0, y0, x1, y1, outline="#334155", width=2)

        # 실시간 마커 표시(원)
        if self.show_markers and self.markers:
            try:
                now2 = time.time()
                for ms in list(self.markers.values()):
                    # (요청) 300ms TTL
                    if (now2 - ms.ts) > float(self.marker_ttl_sec):
                        continue
                    cx, cy = self._to_canvas(ms.x, ms.y)
                    col = cat_color(ms.cat)
                    r = 8
                    self.canvas.create_oval(cx - r, cy - r, cx + r, cy + r, outline=col, width=2)
                    # 카테고리 번호를 원 안에
                    self.canvas.create_text(cx, cy, text=str(ms.cat), fill=col, font=("Segoe UI", 9, "bold"))
                    # idx는 옆에 작게
                    self.canvas.create_text(cx + r + 4, cy, text=str(ms.idx), fill="#cbd5e1", anchor="w", font=("Segoe UI", 9))
            except Exception:
                pass

        # 범례(요청: 1~7 번호 원 + 이름)
        try:
            lx = 12
            ly = 12
            r = 10
            gap_y = 24
            for i in range(1, 8):
                cy = ly + (i - 1) * gap_y
                cx = lx + r
                col = cat_color(i)
                # 원
                self.canvas.create_oval(cx - r, cy - r, cx + r, cy + r, outline=col, width=2, fill="")
                # 숫자
                self.canvas.create_text(cx, cy, text=str(i), fill=col, font=("Segoe UI", 10, "bold"))
                # 라벨
                self.canvas.create_text(cx + r + 6, cy, text=cat_name(i), fill="#e5e7eb", anchor="w", font=("Segoe UI", 10))
        except Exception:
            pass

        # 이벤트
        now = time.time()
        for e in self.events:
            age = now - e.ts
            ttl = float(self.event_ttl_short_sec) if (getattr(e, "cat", 1) in self.event_ttl_short_cats) else float(self.keep_seconds)
            ttl = max(0.05, ttl)
            alpha = max(0.15, 1.0 - (age / ttl))
            width = 2 if e.kind == "swipe" else 1
            r = 6
            cx, cy = self._to_canvas(*e.p0)
            col = e.color

            if e.kind == "tap":
                rr = r + int(6 * (1.0 - alpha))
                self.canvas.create_oval(cx - rr, cy - rr, cx + rr, cy + rr, outline=col, width=2)
                self.canvas.create_oval(cx - 2, cy - 2, cx + 2, cy + 2, fill=col, outline=col)
            else:
                if e.p1 is None:
                    continue
                ex, ey = self._to_canvas(*e.p1)
                self.canvas.create_line(cx, cy, ex, ey, fill=col, width=width + 1, arrow=tk.LAST)
                self.canvas.create_oval(cx - 3, cy - 3, cx + 3, cy + 3, fill=col, outline=col)

        self._update_label()
        self._need_redraw = False

    def on_speed_change(self, _v=None):
        m = re.match(r"^\s*([0-9]+(?:\.[0-9]+)?)x\s*$", self.speed_var.get().strip())
        sp = 1.0
        if m:
            try:
                sp = float(m.group(1))
            except Exception:
                sp = 1.0
        # (요청) 1~10x 느리게
        sp = max(1.0, min(10.0, sp))
        # 런타임 변경 시에도 자연스럽게 이어지도록 기준을 재설정
        if self.replay_path and self.replay_lines:
            now = time.perf_counter()
            sim_now = self._replay_sim_time(now)
            self.replay_start_real = now
            self.replay_start_sim = sim_now
        self.replay_slow = sp
        self._need_redraw = True

    def _replay_sim_time(self, now_real: float) -> float:
        if not self.replay_running:
            return self.replay_start_sim
        # 느리게: 실제 시간 / slow_factor 만큼만 시뮬 시간 진행
        return self.replay_start_sim + (now_real - self.replay_start_real) / max(1e-6, self.replay_slow)

    def toggle_replay(self):
        if not self.replay_path or not self.replay_lines:
            return
        now = time.perf_counter()
        if self.replay_running:
            # pause
            self.replay_start_sim = self._replay_sim_time(now)
            self.replay_running = False
            self.btn_replay.config(text="재생")
        else:
            # play
            self.replay_start_real = now
            self.replay_running = True
            self.btn_replay.config(text="일시정지")
        self._need_redraw = True

    def restart_replay(self):
        if not self.replay_path or not self.replay_lines:
            return
        self.replay_pos = 0
        self.replay_start_sim = 0.0
        self.replay_start_real = time.perf_counter()
        self.replay_running = True
        self.btn_replay.config(text="일시정지")
        self._need_redraw = True

    def open_replay_dialog(self):
        try:
            path = filedialog.askopenfilename(
                title="저장된 로그 파일 선택",
                filetypes=[("Text log", "*.txt"), ("All files", "*.*")],
            )
        except Exception:
            path = ""
        if not path:
            return
        self.load_replay(path)

    def load_replay(self, path: str):
        lines = load_replay_lines(path)
        self.replay_path = path
        self.replay_lines = lines
        self.replay_pos = 0
        self.replay_start_sim = 0.0
        self.replay_start_real = time.perf_counter()
        self.replay_total_sim = float(lines[-1][0]) if lines else 0.0
        # 자동 재생 시작
        self.replay_running = True if lines else False
        self.btn_replay.config(text="일시정지" if self.replay_running else "재생")
        self._need_redraw = True

    def _feed_replay(self):
        if not self.replay_running or not self.replay_lines:
            return
        now = time.perf_counter()
        sim = self._replay_sim_time(now)
        # sim 시점까지의 라인을 한 번에 처리
        while self.replay_pos < len(self.replay_lines) and self.replay_lines[self.replay_pos][0] <= sim:
            _t, line = self.replay_lines[self.replay_pos]
            self.replay_pos += 1
            # 큐에 넣지 않고, 동일 처리 파이프를 직접 사용
            self._process_line_impl(line, do_io=False)
        if self.replay_pos >= len(self.replay_lines):
            # 끝나면 자동 pause
            self.replay_running = False
            self.btn_replay.config(text="재생")
            self._need_redraw = True

    def _process_line(self, line: str):
        self._process_line_impl(line, do_io=True)

    def tick(self):
        # (seek) rebuild 처리(슬라이더로 시간 이동)
        if self._rebuild_active and self.replay_lines:
            try:
                # chunk 단위로 처리해서 UI 멈춤 방지
                chunk = 4000
                end = min(self._rebuild_target_pos, self._rebuild_pos + chunk)
                while self._rebuild_pos < end:
                    _t, line = self.replay_lines[self._rebuild_pos]
                    self._rebuild_pos += 1
                    self._process_line_impl(line, do_io=False)
                if self._rebuild_pos >= self._rebuild_target_pos:
                    # 완료: 재생 기준 재설정
                    self.replay_pos = self._rebuild_target_pos
                    self.replay_start_sim = float(self._rebuild_target_sec)
                    self.replay_start_real = time.perf_counter()
                    self._rebuild_active = False
                    if self._rebuild_resume_after:
                        self.replay_running = True
                        try:
                            self.btn_replay.config(text="일시정지")
                        except Exception:
                            pass
                    self._need_redraw = True
            except Exception:
                # rebuild 실패 시 중단
                self._rebuild_active = False

        # (재생) 먼저 재생 라인 공급
        self._feed_replay()

        # (요청) 마커 TTL 정리(300ms)
        self._prune_markers()

        # 입력 처리
        while True:
            try:
                line = self.q.get_nowait()
            except queue.Empty:
                break

            self._process_line(line)

        # 캔버스 크기 변화
        cw = max(1, int(self.canvas.winfo_width()))
        ch = max(1, int(self.canvas.winfo_height()))
        if (cw, ch) != self._last_canvas_size:
            self._need_redraw = True

        if self._need_redraw:
            self.redraw()

        self.root.after(30, self.tick)

    def on_close(self):
        # 파일 flush/close 후 종료
        try:
            if self.save_fp:
                try:
                    self.save_fp.flush()
                except Exception:
                    pass
                try:
                    self.save_fp.close()
                except Exception:
                    pass
        except Exception:
            pass
        self.root.destroy()

    def run(self):
        self.redraw()
        self.root.mainloop()


def reader_from_stdin(q: "queue.Queue[str]"):
    for line in sys.stdin:
        q.put(line.rstrip("\n"))


def reader_from_adb(q: "queue.Queue[str]", serial: Optional[str], tags: List[str]):
    # 기본: ScreenCaptureService/AutoClickAccessibilityService만 INFO 이상
    # 예) adb logcat -v time ScreenCaptureService:I AutoClickAccessibilityService:I *:S
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += ["logcat", "-v", "time"]
    if tags:
        cmd += tags
    else:
        cmd += ["ScreenCaptureService:I", "AutoClickAccessibilityService:I", "*:S"]

    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="ignore")
    assert p.stdout is not None
    for line in p.stdout:
        q.put(line.rstrip("\n"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stdin", action="store_true", help="stdin에서 로그를 읽습니다(파이프 입력).")
    ap.add_argument("--adb", action="store_true", help="adb logcat을 직접 실행해서 로그를 읽습니다.")
    ap.add_argument("--serial", default=None, help="adb 디바이스 시리얼(여러 대 연결 시).")
    ap.add_argument(
        "--save-log",
        default="auto",
        help="수신한 원본 로그 라인을 파일로 저장합니다. (기본: auto) 예: .\\logs\\atx.txt 또는 auto",
    )
    # (호환) 예전 옵션명 유지
    ap.add_argument(
        "--save",
        default=None,
        help="(호환) --save-log 와 동일. 지정하면 --save-log 값을 덮어씁니다.",
    )
    ap.add_argument(
        "--no-save",
        action="store_true",
        help="로그 저장을 끕니다(기본은 auto로 저장).",
    )
    ap.add_argument(
        "--replay",
        default="",
        help="저장된 로그 파일을 불러와 재생합니다. (예: .\\tools\\logs\\atx_log_*.txt)",
    )
    ap.add_argument(
        "--speed",
        default="1x",
        help="재생 속도(느리게 배속). 1x~10x (예: 2x=2배 느리게, 기본 1x)",
    )
    ap.add_argument(
        "--tags",
        default="ScreenCaptureService:I AutoClickAccessibilityService:I *:S",
        help="adb logcat 태그 필터(공백으로 구분). 예: \"ScreenCaptureService:I *:S\"",
    )
    args = ap.parse_args()

    q: "queue.Queue[str]" = queue.Queue()

    initial = try_get_device_size(args.serial)

    if not args.stdin and not args.adb:
        # 기본은 adb 모드
        args.adb = True

    # replay 모드면 입력 스레드를 돌리지 않는다(파일 재생만).
    if not (isinstance(args.replay, str) and args.replay.strip()):
        if args.stdin:
            t = threading.Thread(target=reader_from_stdin, args=(q,), daemon=True)
            t.start()
        else:
            tags = args.tags.split()
            t = threading.Thread(target=reader_from_adb, args=(q, args.serial, tags), daemon=True)
            t.start()

    # 저장 옵션 결정
    save_opt = None
    if args.no_save:
        save_opt = ""
    else:
        # --save 가 지정되면 우선
        if isinstance(args.save, str) and args.save.strip():
            save_opt = args.save.strip()
        else:
            save_opt = (args.save_log or "").strip() if isinstance(args.save_log, str) else "auto"

    save_fp = None
    save_path = None
    if isinstance(save_opt, str) and save_opt.strip():
        s = save_opt.strip()
        if s.lower() == "auto":
            logs_dir = Path(__file__).resolve().parent / "logs"
            logs_dir.mkdir(parents=True, exist_ok=True)
            stamp = time.strftime("%Y%m%d_%H%M%S")
            save_path = str(logs_dir / f"atx_log_{stamp}.txt")
        else:
            save_path = s
            p = Path(save_path)
            if p.parent:
                try:
                    p.parent.mkdir(parents=True, exist_ok=True)
                except Exception:
                    pass
        try:
            save_fp = open(save_path, "a", encoding="utf-8", errors="ignore", buffering=1)
            try:
                save_fp.write(f"# started_at={time.strftime('%Y-%m-%d %H:%M:%S')}\n")
                save_fp.write(f"# mode={'stdin' if args.stdin else 'adb'}\n")
                if args.serial:
                    save_fp.write(f"# serial={args.serial}\n")
                save_fp.write(f"# tags={args.tags}\n")
                save_fp.write("\n")
            except Exception:
                pass
        except Exception as e:
            print(f"[WARN] 로그 저장 파일을 열 수 없습니다: path={save_path} err={e}", file=sys.stderr)
            save_fp = None
            save_path = None

    app = VisualizerApp(q=q, initial_size=initial, save_fp=save_fp, save_path=save_path)
    # 시작 시 replay 옵션이 있으면 즉시 로드/재생
    if isinstance(args.replay, str) and args.replay.strip():
        app.speed_var.set((args.speed or "1x").strip())
        app.on_speed_change()
        app.load_replay(args.replay.strip())
    app.run()


if __name__ == "__main__":
    main()

