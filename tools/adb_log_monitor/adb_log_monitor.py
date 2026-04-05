#!/usr/bin/env python3
"""
adb_log_monitor.py
------------------
Multi-device ADB logcat monitor with pattern filtering, sliding window,
trigger-based context capture, and automatic reconnection with audible alerts.

USAGE
-----
    python adb_log_monitor.py --config monitor_config.yaml
    python adb_log_monitor.py --patterns patterns.yaml --output ./logs
    python adb_log_monitor.py --patterns patterns.yaml --output ./logs --devices R3CN204 emulator-5554
    python adb_log_monitor.py --patterns patterns.yaml --output ./logs --devices all

REQUIREMENTS
------------
    pip install pyyaml
    ADB must be on PATH. Devices must be authorised.

FILTER SYNTAX  (patterns.yaml → filters:)
-----------------------------------------
Two forms are accepted:

  Legacy (all-include, no excludes):
    filters:
      - pattern: "SomeTag"
      - pattern: "regex.*here"
        is_regex: true
        label: "human readable name"

  Modern dict form (recommended):
    filters:
      include:
        - pattern: "I/System.out"          # substring match (case-insensitive)
        - pattern: "Activity.*started"      # regex if is_regex: true
          is_regex: true
          label: "activity_start"
      exclude:
        - pattern: "InputDispatcher"        # drop lines matching ANY exclude
        - pattern: "Adreno-EGL"

  Logic: line is kept if (include list is empty OR ≥1 include matches)
                     AND (no exclude pattern matches).

  Per-entry fields:
    pattern   : str  — substring to search, or regex if is_regex: true
    is_regex  : bool — compile pattern as a case-insensitive regex (default false)
    label     : str  — optional display name (defaults to first 30 chars of pattern)

STATE PATTERNS  (patterns.yaml → state_patterns:)
-------------------------------------------------
Run on EVERY raw logcat line before filtering so tracked state is always current.

    state_patterns:
      - field: mesh_status           # DeviceState field to update
        pattern: "startMesh.*success"
        is_regex: true
        set_value: ACTIVE            # literal value to assign when pattern matches

      - field: nodes_in_topology
        pattern: "topology.*(\\d+)\\s+node"
        is_regex: true
        extract_group: 1             # use capture group 1 as the value instead of set_value

  Tracked fields (DeviceState dataclass):
    mesh_status           — UNKNOWN | CONNECTING | ACTIVE | GATEWAY | DISCONNECTED
    nodes_in_topology     — UNKNOWN | <integer as string> | SHRINKING
    vpn_connection_status — UNKNOWN | CONNECTED | DISCONNECTED
    orbot_active          — UNKNOWN | true | false
    internet_available    — UNKNOWN | true | false

TRIGGER SYNTAX  (patterns.yaml → triggers:)
--------------------------------------------
type: pattern  (default)
    Fires when a filtered log line matches the pattern.
    Optional state_requires guard limits firing to a specific device state.

    - pattern: "FATAL EXCEPTION"
      label: "fatal_crash"

    - type: pattern
      pattern: "broadcast.*fail"
      is_regex: true
      label: "broadcast_fail_while_mesh_active"
      state_requires:
        field: mesh_status
        value: ACTIVE                # only dump if mesh_status is currently ACTIVE
      devices:                       # optional: list of serials or omit/[all]
        - R3CN204XXXX

type: state_change
    Fires when a tracked state field changes value.
    Omit from:/to: to fire on ANY change to that field.

    - type: state_change
      state_change:
        field: mesh_status
        from: ACTIVE                 # optional: only fire if old value was ACTIVE
        to: DISCONNECTED             # optional: only fire if new value is DISCONNECTED
      label: "mesh_went_offline"
      devices: all                   # all | list of serials

  Common trigger fields:
    label     : str  — name written into the trigger dump header
    devices   : list — serials to apply to, or "all" (default)

LOG STRUCTURE
-------------
Two rotating log files are written per device:

  <serial>_filtered.log
    Every logcat line that passes the filter gate, tagged with:
      [YYYY-MM-DD HH:MM:SS.mmm][<serial>][<marker>] <original line>
    Markers: SESSION, CONNECT, DISCONNECT, RECONNECT, WARN, ERROR, etc.

  <serial>_triggers.log
    Written when a trigger fires. Each block looks like:

      ======================================================================
      TRIGGER #N | label=<label> | device=<serial> | session=<N>
      Timestamp : YYYY-MM-DD HH:MM:SS.mmm
      Line      : [tagged log line that fired the trigger]
      State     : mesh=<value>  vpn=<value>  orbot=<value>  nodes=<value>  internet=<value>
      [STATE_CHANGE] <field>: '<old>' → '<new>'   (state_change triggers only)
      Context   : last N filtered lines
      ======================================================================
      ... up to trigger_context_lines lines from the sliding window ...
      ======================================================================

METRICS (per DeviceMonitor thread)
-----------------------------------
  _trigger_count     — total trigger dumps written this run
  _session_count     — number of logcat sessions started (increments on reconnect)
  _disconnect_count  — number of device disconnection events detected
  DeviceState fields — current tracked state for the device (see STATE PATTERNS above)

ROTATION
--------
  Both log files rotate when they reach max_file_size_mb.
  Up to max_rotations rotated copies are kept (e.g. _filtered.log.1 … _filtered.log.4).
  Use clean_logs.py to delete all accumulated log files.
"""

import argparse
import collections
import dataclasses
import datetime
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional

try:
    import yaml
except ImportError:
    sys.exit("Missing dependency: pip install pyyaml")


# ─────────────────────────────────────────────
# Per-device tracked state
# ─────────────────────────────────────────────

@dataclasses.dataclass
class DeviceState:
    """Real-time state extracted from logcat lines for one device."""
    mesh_status:           str = "UNKNOWN"
    nodes_in_topology:     str = "UNKNOWN"
    vpn_connection_status: str = "UNKNOWN"
    orbot_active:          str = "UNKNOWN"
    internet_available:    str = "UNKNOWN"

    def as_dict(self) -> dict:
        return dataclasses.asdict(self)

    def get(self, field: str) -> str:
        return getattr(self, field, "UNKNOWN")

    def set(self, field: str, value: str):
        if hasattr(self, field):
            setattr(self, field, value)

    def copy(self) -> "DeviceState":
        return DeviceState(**dataclasses.asdict(self))


# ─────────────────────────────────────────────
# Audible alert
# ─────────────────────────────────────────────

def beep(count: int = 3, interval: float = 0.4):
    """
    Cross-platform terminal beep. Runs in a daemon thread so it never
    blocks the monitor loop.
    """
    def _beep():
        for _ in range(count):
            # sys.stdout.write flushes the BEL character to the terminal.
            # On most OSes this produces an audible or visual bell.
            sys.stdout.write("\a")
            sys.stdout.flush()
            time.sleep(interval)
    t = threading.Thread(target=_beep, daemon=True)
    t.start()


# ─────────────────────────────────────────────
# Config loader
# ─────────────────────────────────────────────

DEFAULT_SETTINGS = {
    "window_size":              300,
    "max_file_size_mb":         8.0,
    "max_rotations":            4,
    "trigger_context_lines":    75,
    "flush_interval_sec":       3,
    "reconnect_interval_sec":   5,    # how long to wait between reconnect attempts
    "reconnect_beep_count":     3,    # beeps on disconnect
    "reconnect_beep_interval":  0.35, # seconds between each beep
}


def load_patterns(path: str) -> dict:
    with open(path, "r") as f:
        raw = yaml.safe_load(f)

    def compile_list(items):
        out = []
        for item in (items or []):
            pat = item["pattern"]
            compiled = re.compile(pat, re.IGNORECASE) if item.get("is_regex") else None
            out.append({
                "raw":      pat,
                "compiled": compiled,
                "label":    item.get("label", pat[:30]),
            })
        return out

    def compile_trigger(item):
        trig_type = item.get("type", "pattern")
        devices = item.get("devices", ["all"])
        if isinstance(devices, str):
            devices = [devices]
        entry = {
            "type":    trig_type,
            "label":   item.get("label", "unnamed"),
            "devices": list(devices),
        }
        if trig_type == "pattern":
            pat      = item["pattern"]
            compiled = re.compile(pat, re.IGNORECASE) if item.get("is_regex") else None
            entry["raw"]      = pat
            entry["compiled"] = compiled
            if "state_requires" in item:
                entry["state_requires"] = item["state_requires"]
        elif trig_type == "state_change":
            entry["state_change"] = item["state_change"]
        return entry

    def compile_state_pattern(item):
        pat      = item["pattern"]
        compiled = re.compile(pat, re.IGNORECASE) if item.get("is_regex") else None
        return {
            "field":         item["field"],
            "raw":           pat,
            "compiled":      compiled,
            "set_value":     item.get("set_value"),
            "extract_group": item.get("extract_group"),
        }

    # ── Parse filters: legacy flat list = all-include; new dict has include/exclude ──
    raw_filters = raw.get("filters", [])
    if isinstance(raw_filters, list):
        filters_include = compile_list(raw_filters)
        filters_exclude = []
    else:
        filters_include = compile_list(raw_filters.get("include", []))
        filters_exclude = compile_list(raw_filters.get("exclude", []))

    settings = {**DEFAULT_SETTINGS, **raw.get("settings", {})}
    # Coerce types defensively
    settings["window_size"]           = int(settings["window_size"])
    settings["max_file_size_mb"]      = float(settings["max_file_size_mb"])
    settings["max_rotations"]         = int(settings["max_rotations"])
    settings["trigger_context_lines"] = int(settings["trigger_context_lines"])
    settings["flush_interval_sec"]    = int(settings["flush_interval_sec"])
    settings["reconnect_interval_sec"]= int(settings["reconnect_interval_sec"])
    settings["reconnect_beep_count"]  = int(settings["reconnect_beep_count"])
    settings["reconnect_beep_interval"] = float(settings["reconnect_beep_interval"])

    return {
        "filters_include":  filters_include,
        "filters_exclude":  filters_exclude,
        "triggers":         [compile_trigger(t) for t in raw.get("triggers", [])],
        "state_patterns":   [compile_state_pattern(sp) for sp in raw.get("state_patterns", [])],
        "settings":         settings,
    }


def load_monitor_config(path: str) -> dict:
    """
    Load an optional top-level monitor_config.yaml that can specify
    patterns file, output dir, and device list, so the whole session
    is reproducible from a single file.

    Structure:
        patterns: ./patterns.yaml
        output:   ./burn_in_logs
        devices:
          - R3CN204XXXX
          - emulator-5554
          - 192.168.1.42:5555
    """
    with open(path, "r") as f:
        return yaml.safe_load(f) or {}


# ─────────────────────────────────────────────
# Matching helpers
# ─────────────────────────────────────────────

def _pattern_match(line: str, p: dict):
    """Return a regex Match object, True (substring hit), or None (no match)."""
    if p["compiled"]:
        return p["compiled"].search(line)
    if p["raw"].lower() in line.lower():
        return True
    return None


def matches_include(line: str, pattern_list: list) -> Optional[str]:
    """Return the label of the first matching pattern, or None."""
    for p in pattern_list:
        if _pattern_match(line, p) is not None:
            return p["label"]
    return None


def passes_filter(line: str, config: dict) -> bool:
    """
    Return True if line passes the filter gate:
      - No include patterns defined OR line matches at least one include pattern
      - AND line does not match any exclude pattern
    """
    include_list = config.get("filters_include", [])
    exclude_list = config.get("filters_exclude", [])
    if include_list and matches_include(line, include_list) is None:
        return False
    if exclude_list and matches_include(line, exclude_list) is not None:
        return False
    return True


# ─────────────────────────────────────────────
# ADB helpers
# ─────────────────────────────────────────────

def adb_devices() -> set[str]:
    """Return set of currently connected, authorized device serials."""
    try:
        r = subprocess.run(
            ["adb", "devices"],
            capture_output=True, text=True, timeout=10
        )
    except FileNotFoundError:
        sys.exit("adb not found on PATH. Install Android SDK platform-tools.")
    serials = set()
    for line in r.stdout.splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) == 2 and parts[1] == "device":
            serials.add(parts[0])
    return serials


def is_device_online(serial: str) -> bool:
    return serial in adb_devices()


# ─────────────────────────────────────────────
# Rotating file writer
# ─────────────────────────────────────────────

class RotatingWriter:
    def __init__(self, base_path: Path, max_bytes: int, max_rotations: int, flush_interval: int):
        self.base_path      = base_path
        self.max_bytes      = max_bytes
        self.max_rotations  = max_rotations
        self.flush_interval = flush_interval
        self._lock          = threading.Lock()
        self._fh            = None
        self._last_flush    = time.time()
        self._open()

    def _open(self):
        self._fh = open(self.base_path, "a", encoding="utf-8", buffering=1)

    def _rotate(self):
        self._fh.close()
        for i in range(self.max_rotations - 1, 0, -1):
            src = Path(f"{self.base_path}.{i}")
            dst = Path(f"{self.base_path}.{i+1}")
            if src.exists():
                if dst.exists():
                    dst.unlink()
                src.rename(dst)
        aged = Path(f"{self.base_path}.1")
        if aged.exists():
            aged.unlink()
        if self.base_path.exists():
            self.base_path.rename(aged)
        oldest = Path(f"{self.base_path}.{self.max_rotations + 1}")
        if oldest.exists():
            oldest.unlink()
        self._open()

    def write(self, line: str):
        with self._lock:
            self._fh.write(line + "\n")
            now = time.time()
            if now - self._last_flush >= self.flush_interval:
                self._fh.flush()
                self._last_flush = now
            if self._fh.tell() >= self.max_bytes:
                self._rotate()

    def flush(self):
        with self._lock:
            if self._fh:
                self._fh.flush()

    def close(self):
        with self._lock:
            if self._fh:
                self._fh.flush()
                self._fh.close()
                self._fh = None


# ─────────────────────────────────────────────
# Per-device monitor — reconnect-aware
# ─────────────────────────────────────────────

class DeviceMonitor(threading.Thread):
    """
    Manages the full lifecycle of one device's log session, including:
      - Waiting for the device to appear if not yet online
      - Streaming logcat
      - Detecting disconnection and re-entering the wait loop
      - Beeping on disconnect/reconnect events
    The thread runs until stop_event is set.
    """

    # States for the console status line
    STATE_WAITING     = "WAITING"
    STATE_CONNECTED   = "CONNECTED"
    STATE_RECONNECTING = "RECONNECTING"

    def __init__(self, serial: str, config: dict, output_dir: Path, stop_event: threading.Event):
        super().__init__(name=f"mon-{serial}", daemon=True)
        self.serial     = serial
        self.config     = config
        self.output_dir = output_dir
        self.stop_event = stop_event
        s               = config["settings"]

        self.reconnect_interval = s["reconnect_interval_sec"]
        self.beep_count         = s["reconnect_beep_count"]
        self.beep_interval      = s["reconnect_beep_interval"]
        self.trigger_ctx        = s["trigger_context_lines"]

        self.window       = collections.deque(maxlen=s["window_size"])
        self.conn_state   = self.STATE_WAITING
        self.device_state = DeviceState()
        self._state_lock  = threading.Lock()
        self._trigger_count    = 0
        self._session_count    = 0   # increments on each reconnect
        self._disconnect_count = 0

        safe = re.sub(r"[^A-Za-z0-9_\-]", "_", serial)
        max_bytes = int(s["max_file_size_mb"] * 1024 * 1024)
        rot       = s["max_rotations"]
        flush     = s["flush_interval_sec"]

        self.filtered_writer = RotatingWriter(output_dir / f"{safe}_filtered.log",  max_bytes, rot, flush)
        self.trigger_writer  = RotatingWriter(output_dir / f"{safe}_triggers.log",  max_bytes, rot, flush)

    # ── helpers ──────────────────────────────

    def _stamp(self) -> str:
        return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

    def _tag(self, line: str, marker: str = "") -> str:
        m = f"[{marker}]" if marker else ""
        return f"[{self._stamp()}][{self.serial}]{m} {line}"

    def _log(self, msg: str, marker: str = ""):
        tagged = self._tag(msg, marker)
        self.filtered_writer.write(tagged)
        print(tagged)

    # ── session runner ────────────────────────

    def _run_session(self):
        """
        Stream logcat for one connected session.
        Returns when the device disconnects or the process dies.
        """
        self._session_count += 1
        self.conn_state = self.STATE_CONNECTED
        self._log(f"=== Session {self._session_count} started ===", "SESSION")

        cmd = ["adb", "-s", self.serial, "logcat", "-v", "threadtime"]
        proc = None
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
            )

            for raw_line in proc.stdout:
                if self.stop_event.is_set():
                    return

                line = raw_line.rstrip()
                if not line:
                    continue

                # State extraction — runs on all lines before the filter
                state_changes = self._update_state(line)

                # Filter pass
                if not passes_filter(line, self.config):
                    continue

                tagged = self._tag(line)
                self.window.append(tagged)
                self.filtered_writer.write(tagged)

                # Trigger evaluation
                self._check_triggers(line, tagged, state_changes)

            # stdout closed — device likely disconnected
            proc.wait(timeout=3)

        except Exception as exc:
            self._log(f"Session exception: {exc}", "ERROR")
        finally:
            if proc and proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    proc.kill()
            self.filtered_writer.flush()

    # ── main thread loop ──────────────────────

    def run(self):
        self._log("=== Monitor thread started ===", "INIT")

        while not self.stop_event.is_set():

            # ── Wait for device to come online ─────────────────
            if not is_device_online(self.serial):
                if self.conn_state != self.STATE_WAITING:
                    # Transition into waiting — alert the operator
                    self._disconnect_count += 1
                    self.conn_state = self.STATE_RECONNECTING
                    self._log(
                        f"Device offline (disconnect #{self._disconnect_count}). "
                        f"Reconnecting every {self.reconnect_interval}s — plug cable.",
                        "DISCONNECT"
                    )
                    beep(self.beep_count, self.beep_interval)
                else:
                    # First time waiting — quieter notice
                    self._log(
                        f"Device not online yet. Polling every {self.reconnect_interval}s.",
                        "WAITING"
                    )

                # Poll until device reappears
                while not self.stop_event.is_set():
                    time.sleep(self.reconnect_interval)
                    if is_device_online(self.serial):
                        break

                if self.stop_event.is_set():
                    break

                # Device is back
                if self._disconnect_count > 0:
                    self._log("Device back online. Resuming logcat.", "RECONNECT")
                    beep(1, 0.1)   # single short beep on successful reconnect
                else:
                    self._log("Device online. Starting logcat.", "CONNECT")

            # ── Stream logcat ──────────────────────────────────
            self._run_session()

            # After session ends, loop back to the top to re-check connectivity.
            # If device is still online the process died for another reason —
            # give it a moment before restarting to avoid tight-looping.
            if not self.stop_event.is_set() and is_device_online(self.serial):
                self._log("Logcat process exited unexpectedly. Restarting in 2s.", "WARN")
                time.sleep(2)

        # Shutdown
        self._log("=== Monitor thread stopped ===", "STOP")
        self.filtered_writer.close()
        self.trigger_writer.close()

    # ── trigger dump ──────────────────────────

    def _dump_trigger(self, trigger_line: str, label: str, extra_header: str = ""):
        self._trigger_count += 1
        with self._state_lock:
            ds = self.device_state.as_dict()
        state_str = (
            f"mesh={ds['mesh_status']}  vpn={ds['vpn_connection_status']}  "
            f"orbot={ds['orbot_active']}  nodes={ds['nodes_in_topology']}  "
            f"internet={ds['internet_available']}"
        )
        parts = [
            "",
            "=" * 70,
            f"TRIGGER #{self._trigger_count} | label={label} | device={self.serial} | session={self._session_count}",
            f"Timestamp : {self._stamp()}",
            f"Line      : {trigger_line}",
            f"State     : {state_str}",
        ]
        if extra_header:
            parts.append(extra_header)
        parts.append(f"Context   : last {min(len(self.window), self.trigger_ctx)} filtered lines")
        parts.append("=" * 70)
        self.trigger_writer.write("\n".join(parts))
        for entry in list(self.window)[-self.trigger_ctx:]:
            self.trigger_writer.write(entry)
        self.trigger_writer.write("=" * 70 + "\n")

    # ── device state extraction ───────────────

    def _update_state(self, line: str) -> dict:
        """
        Match state_patterns against every raw logcat line (before filtering).
        Returns a dict of changed fields: {field: (old_value, new_value)}.
        """
        changes = {}
        for sp in self.config.get("state_patterns", []):
            field    = sp["field"]
            compiled = sp["compiled"]
            if compiled:
                m       = compiled.search(line)
                matched = m is not None
            else:
                m       = None
                matched = sp["raw"].lower() in line.lower()
            if not matched:
                continue

            extract_group = sp.get("extract_group")
            if extract_group is not None and m:
                try:
                    new_value = m.group(extract_group)
                except (IndexError, AttributeError):
                    new_value = sp.get("set_value") or "UNKNOWN"
            else:
                new_value = sp.get("set_value") or "UNKNOWN"

            with self._state_lock:
                old_value = self.device_state.get(field)
                if old_value != new_value:
                    self.device_state.set(field, new_value)
                    changes[field] = (old_value, new_value)
        return changes

    def _check_triggers(self, raw_line: str, tagged_line: str, state_changes: dict):
        """
        Evaluate all trigger rules against the current line and device state.

        Trigger types (set via `type:` key in patterns.yaml, default = pattern):
          pattern      — line matches a regex/substring pattern.
                         Optional `state_requires: {field, value}` guard limits
                         firing to when the named state field holds that value.
          state_change — fires when a tracked field changed while processing
                         this line.  Optional `from:` and/or `to:` constrain
                         which transitions fire.

        All types honour the `devices:` field (list of serials or ["all"]).
        """
        for trig in self.config.get("triggers", []):
            # ── device scope ───────────────────────────────────────────────
            trig_devices = trig.get("devices", ["all"])
            if "all" not in trig_devices and self.serial not in trig_devices:
                continue

            label     = trig.get("label", "unnamed")
            trig_type = trig.get("type", "pattern")

            if trig_type == "pattern":
                # ── PATTERN trigger ─────────────────────────────────────────
                compiled = trig.get("compiled")
                raw      = trig.get("raw", "")
                if compiled:
                    matched = compiled.search(raw_line) is not None
                else:
                    matched = raw.lower() in raw_line.lower()
                if not matched:
                    continue

                # Optional state guard
                req = trig.get("state_requires")
                if req:
                    with self._state_lock:
                        current = self.device_state.get(req["field"])
                    if current != str(req.get("value", "")):
                        continue
                self._dump_trigger(tagged_line, label)

            elif trig_type == "state_change":
                # ── STATE_CHANGE trigger ────────────────────────────────────
                sc    = trig.get("state_change", {})
                field = sc.get("field")
                if not field or field not in state_changes:
                    continue
                old_val, new_val = state_changes[field]
                from_ok = sc.get("from") is None or sc["from"] == old_val
                to_ok   = sc.get("to")   is None or sc["to"]   == new_val
                if from_ok and to_ok:
                    extra = f"[STATE_CHANGE] {field}: {old_val!r} \u2192 {new_val!r}"
                    self._dump_trigger(tagged_line, label, extra_header=extra)


# ─────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(
        description="Multi-device ADB logcat monitor with auto-reconnect and pattern filtering.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Use a single config file for everything:
  python adb_log_monitor.py --config monitor_config.yaml

  # Override devices on the command line:
  python adb_log_monitor.py --patterns patterns.yaml --output ./logs --devices R3CN204 emulator-5554

  # Monitor all currently connected devices:
  python adb_log_monitor.py --patterns patterns.yaml --output ./logs --devices all
        """
    )
    p.add_argument("--config",   help="Top-level monitor config YAML (patterns, output, devices).")
    p.add_argument("--patterns", help="Patterns YAML file. Overrides --config if both given.")
    p.add_argument("--output",   help="Log output directory. Overrides --config if both given.")
    p.add_argument("--devices",  nargs="*",
                   help="Device serials. Use 'all' for all connected. Overrides --config.")
    return p.parse_args()


def resolve_config(args) -> tuple[str, str, list[str]]:
    """
    Merge --config file with explicit CLI overrides.
    Priority: CLI args > config file defaults.
    Returns (patterns_path, output_dir, device_list).
    """
    cfg_patterns = None
    cfg_output   = None
    cfg_devices  = []

    if args.config:
        if not os.path.isfile(args.config):
            sys.exit(f"Config file not found: {args.config}")
        mc = load_monitor_config(args.config)
        cfg_patterns = mc.get("patterns")
        cfg_output   = mc.get("output")
        cfg_devices  = mc.get("devices") or []

    patterns_path = args.patterns or cfg_patterns
    output_dir    = args.output   or cfg_output
    devices_raw   = args.devices  or cfg_devices or ["all"]

    if not patterns_path:
        sys.exit("Patterns file required. Provide --patterns or set 'patterns' in --config.")
    if not output_dir:
        sys.exit("Output directory required. Provide --output or set 'output' in --config.")
    if not os.path.isfile(patterns_path):
        sys.exit(f"Patterns file not found: {patterns_path}")

    return patterns_path, output_dir, devices_raw


def resolve_devices(devices_raw: list[str]) -> list[str]:
    if "all" in devices_raw:
        serials = list(adb_devices())
        if not serials:
            # "all" with no devices connected is fine — monitors will wait
            print("No devices currently online. Monitors will wait for connections.")
        return serials if serials else []
    return list(devices_raw)


def main():
    args = parse_args()
    patterns_path, output_dir, devices_raw = resolve_config(args)
    config = load_patterns(patterns_path)

    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)

    serials = resolve_devices(devices_raw)

    # If 'all' was specified but nothing is online, we have no serials.
    # Warn and exit — there's nothing to monitor.
    if not serials:
        if "all" in devices_raw:
            sys.exit(
                "No devices found and 'all' was specified. "
                "Connect at least one device, or list serials explicitly so "
                "monitors can wait for them."
            )

    s = config["settings"]
    print(f"\nadb_log_monitor — burn-in mode")
    print(f"  Patterns file    : {patterns_path}")
    print(f"  Output directory : {out.resolve()}")
    print(f"  Devices          : {', '.join(serials) if serials else '(none — exit)'}")
    print(f"  Window size      : {s['window_size']} lines")
    print(f"  Max file size    : {s['max_file_size_mb']} MB  ({s['max_rotations']} rotations)")
    print(f"  Reconnect poll   : every {s['reconnect_interval_sec']}s")
    print(f"  Disconnect beeps : {s['reconnect_beep_count']}")
    print(f"\nPress Ctrl+C to stop.\n")

    stop_event = threading.Event()
    monitors   = [
        DeviceMonitor(serial, config, out, stop_event)
        for serial in serials
    ]

    for m in monitors:
        m.start()
        print(f"  Monitor started for {m.serial}")

    try:
        while any(m.is_alive() for m in monitors):
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down — waiting for monitors to flush...")
        stop_event.set()
        for m in monitors:
            m.join(timeout=8)
        print("Done.")


if __name__ == "__main__":
    main()
