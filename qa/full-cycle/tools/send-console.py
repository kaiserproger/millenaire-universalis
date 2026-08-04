#!/usr/bin/env python3
"""Write one command to a running full-cycle QA server console FIFO."""

from __future__ import annotations

import os
import pathlib
import sys


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: send-console.py <run-id> <command>")
    run_id, command = sys.argv[1:]
    if not command or "\n" in command or "\r" in command:
        raise SystemExit("command must be one non-empty line")
    repo_root = pathlib.Path(__file__).resolve().parents[3]
    fifo = repo_root / "qa" / "full-cycle" / "runs" / run_id / "control" / "console.fifo"
    if not fifo.exists():
        raise SystemExit(f"console FIFO is unavailable: {fifo}")
    descriptor = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
    try:
        os.write(descriptor, command.encode("utf-8") + b"\n")
    finally:
        os.close(descriptor)
    print(f"sent run={run_id} command={command}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
