#!/usr/bin/env python3
"""Stop one background full-cycle run, preferring a clean server console shutdown."""

from __future__ import annotations

import os
import pathlib
import signal
import sys
import time


def process_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: abort-background.py <run-id>")
    run_id = sys.argv[1]
    repo_root = pathlib.Path(__file__).resolve().parents[3]
    control = repo_root / "qa" / "full-cycle" / "control"
    pid_file = control / f"{run_id}.runner.pid"
    if not pid_file.is_file():
        raise SystemExit(f"unknown run id: {run_id}")
    pid = int(pid_file.read_text(encoding="utf-8").strip())

    fifo = repo_root / "qa" / "full-cycle" / "runs" / run_id / "control" / "console.fifo"
    if fifo.exists():
        try:
            descriptor = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
        except OSError:
            descriptor = -1
        if descriptor >= 0:
            try:
                os.write(descriptor, b"stop\n")
            finally:
                os.close(descriptor)

    deadline = time.monotonic() + 20.0
    while process_alive(pid) and time.monotonic() < deadline:
        time.sleep(0.25)
    if process_alive(pid):
        try:
            os.killpg(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    print(f"aborted run={run_id} pid={pid}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
