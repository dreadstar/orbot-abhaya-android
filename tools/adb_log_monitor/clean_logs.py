#!/usr/bin/env python3
"""
clean_logs.py
-------------
Delete accumulated log files produced by adb_log_monitor.py.

─────────────────────────────────────────────────────────────────────────────
SETUP / REQUIREMENTS
─────────────────────────────────────────────────────────────────────────────
Python version : 3.10 or later  (uses X | Y union-type hint in one place)
Dependencies   : pyyaml — ONLY needed if you use --config to read the output
                 directory from monitor_config.yaml.  Not required otherwise.

    pip install pyyaml          # optional; install alongside adb_log_monitor

No other external packages are required.  The script uses only the Python
standard library (argparse, glob, os, sys, pathlib).

─────────────────────────────────────────────────────────────────────────────
WHAT IT CLEANS
─────────────────────────────────────────────────────────────────────────────
The monitor writes rotating log pairs for every monitored device serial:

    <output_dir>/<serial>_filtered.log        ← main filtered rolling log
    <output_dir>/<serial>_filtered.log.1      ← rotation 1 (oldest kept copy)
    <output_dir>/<serial>_filtered.log.2      …
    <output_dir>/<serial>_triggers.log        ← trigger-event dumps
    <output_dir>/<serial>_triggers.log.1
    …

This script finds and removes all files matching *_filtered.log* and
*_triggers.log* inside the target directory.  It does NOT remove the
directory itself or any other files.

─────────────────────────────────────────────────────────────────────────────
USAGE
─────────────────────────────────────────────────────────────────────────────
  # Use the default output directory (./burn_in_logs) with confirmation prompt
  python clean_logs.py

  # Point at an explicit log directory
  python clean_logs.py --dir ./my_session_logs

  # Read the output directory from your monitor session config (requires pyyaml)
  python clean_logs.py --config monitor_config.yaml

  # Preview: show what would be deleted without actually deleting anything
  python clean_logs.py --dry-run
  python clean_logs.py --config monitor_config.yaml --dry-run

  # Skip the y/N confirmation prompt (useful in CI or shell scripts)
  python clean_logs.py --yes
  python clean_logs.py --dir ./burn_in_logs --yes

─────────────────────────────────────────────────────────────────────────────
OPTIONS
─────────────────────────────────────────────────────────────────────────────
  --dir  <path>     Directory to clean.  Default: ./burn_in_logs
  --config <file>   Read output directory from a monitor_config.yaml file.
                    Ignored if --dir is also provided.
  --dry-run         List matching files and total size; do not delete.
  --yes / -y        Delete without asking for confirmation.
─────────────────────────────────────────────────────────────────────────────
"""

import argparse
import glob
import os
import sys
from pathlib import Path

try:
    import yaml
    _HAVE_YAML = True
except ImportError:
    _HAVE_YAML = False


def load_output_dir_from_config(config_path: str) -> str | None:
    if not _HAVE_YAML:
        sys.exit("PyYAML not installed. Use --dir instead, or: pip install pyyaml")
    with open(config_path, "r") as f:
        cfg = yaml.safe_load(f) or {}
    return cfg.get("output")


def find_log_files(log_dir: Path) -> list[Path]:
    """
    Collect all *_filtered.log* and *_triggers.log* files (including rotations)
    inside log_dir, sorted for predictable display.
    """
    patterns = [
        "*_filtered.log",
        "*_filtered.log.*",
        "*_triggers.log",
        "*_triggers.log.*",
    ]
    files = []
    for pat in patterns:
        files.extend(log_dir.glob(pat))
    return sorted(set(files))


def human_bytes(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def parse_args():
    p = argparse.ArgumentParser(
        description="Remove accumulated adb_log_monitor log files.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python clean_logs.py
  python clean_logs.py --dir ./my_session_logs
  python clean_logs.py --config monitor_config.yaml --dry-run
  python clean_logs.py --yes
        """,
    )
    p.add_argument(
        "--dir",
        default=None,
        help="Log output directory to clean. Default: ./burn_in_logs",
    )
    p.add_argument(
        "--config",
        default=None,
        help="monitor_config.yaml to read output directory from.",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="List files that would be deleted without deleting them.",
    )
    p.add_argument(
        "--yes", "-y",
        action="store_true",
        help="Skip the confirmation prompt and delete immediately.",
    )
    return p.parse_args()


def main():
    args = parse_args()

    # ── Resolve output directory ──────────────────────────────────────────
    output_dir_str = args.dir
    if output_dir_str is None and args.config:
        if not os.path.isfile(args.config):
            sys.exit(f"Config file not found: {args.config}")
        output_dir_str = load_output_dir_from_config(args.config)
        if output_dir_str is None:
            sys.exit(f"'output' key not found in {args.config}")
    if output_dir_str is None:
        output_dir_str = "./burn_in_logs"

    log_dir = Path(output_dir_str).resolve()

    if not log_dir.exists():
        print(f"Directory does not exist: {log_dir}")
        print("Nothing to clean.")
        return
    if not log_dir.is_dir():
        sys.exit(f"Not a directory: {log_dir}")

    # ── Collect files ─────────────────────────────────────────────────────
    files = find_log_files(log_dir)

    if not files:
        print(f"No log files found in: {log_dir}")
        return

    total_bytes = sum(f.stat().st_size for f in files)
    print(f"\nLog directory : {log_dir}")
    print(f"Files found   : {len(files)}  ({human_bytes(total_bytes)})\n")
    for f in files:
        size = human_bytes(f.stat().st_size)
        print(f"  {size:>10}  {f.name}")

    if args.dry_run:
        print(f"\n[dry-run] {len(files)} file(s) would be deleted — no changes made.")
        return

    # ── Confirm ───────────────────────────────────────────────────────────
    if not args.yes:
        try:
            answer = input(f"\nDelete {len(files)} file(s)? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("\nAborted.")
            return
        if answer not in ("y", "yes"):
            print("Aborted.")
            return

    # ── Delete ────────────────────────────────────────────────────────────
    deleted = 0
    errors  = 0
    for f in files:
        try:
            f.unlink()
            deleted += 1
        except OSError as exc:
            print(f"  ERROR deleting {f.name}: {exc}")
            errors += 1

    print(f"\nDeleted {deleted} file(s)  ({human_bytes(total_bytes)} freed).")
    if errors:
        print(f"  {errors} error(s) — check permissions.")


if __name__ == "__main__":
    main()
