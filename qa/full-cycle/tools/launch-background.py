#!/usr/bin/env python3
"""Launch one isolated full-cycle QA run and return immediately for bounded polling."""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import sys


def main() -> int:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: launch-background.py <run-id> <bannerok-server-repo> <stopped-runtime-root>"
        )
    run_id, server_repo_raw, runtime_root_raw = sys.argv[1:]
    if not re.fullmatch(r"[A-Za-z0-9._-]+", run_id):
        raise SystemExit("unsafe run id")

    repo_root = pathlib.Path(__file__).resolve().parents[3]
    server_repo = pathlib.Path(server_repo_raw).expanduser().resolve()
    runtime_root = pathlib.Path(runtime_root_raw).expanduser().resolve()
    runner = repo_root / "qa" / "full-cycle" / "bin" / "run.sh"
    if not (server_repo / "stress-harness" / "mod" / "build.gradle").is_file():
        raise SystemExit(f"invalid bannerok-server repository: {server_repo}")
    if not (runtime_root / "libraries").is_dir() or not (runtime_root / "mods").is_dir():
        raise SystemExit(f"invalid stopped runtime root: {runtime_root}")

    control = repo_root / "qa" / "full-cycle" / "control"
    control.mkdir(parents=True, exist_ok=True)
    log = control / f"{run_id}.runner.log"
    pid_file = control / f"{run_id}.runner.pid"
    run_dir = repo_root / "qa" / "full-cycle" / "runs" / run_id
    if run_dir.exists() or log.exists() or pid_file.exists():
        raise SystemExit(f"run id already exists: {run_id}")

    environment = os.environ.copy()
    environment["BANNEROK_SERVER_REPO"] = str(server_repo)
    environment["BANNEROK_QA_RUNTIME_ROOT"] = str(runtime_root)
    with log.open("wb") as output:
        process = subprocess.Popen(
            ["bash", str(runner), run_id],
            cwd=repo_root,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    pid_file.write_text(f"{process.pid}\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "runId": run_id,
                "pid": process.pid,
                "log": str(log.relative_to(repo_root)),
                "result": str((run_dir / "result.json").relative_to(repo_root)),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
