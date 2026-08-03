#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import os
import pathlib
import tempfile
import urllib.request

FILENAME = "millenaire-9.0.0-beta.2.jar"
SHA256 = "0d993af17355e2e2d08d4852bb3fd0215655519e372246c4a61369a174b94d13"
DEFAULT_URL = f"https://bannerok.avanpostmc.site/cache/v1/objects/{SHA256}"


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    target = root / "vendor" / FILENAME
    if target.is_file() and digest(target) == SHA256:
        print(f"dependency ready: {target}")
        return 0
    target.parent.mkdir(parents=True, exist_ok=True)
    url = os.environ.get("MILLENAIRE_JAR_URL", DEFAULT_URL)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{FILENAME}.", dir=target.parent)
    os.close(fd)
    temporary = pathlib.Path(temporary_name)
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "bannerok-standalone-mod-build/1"})
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        actual = digest(temporary)
        if actual != SHA256:
            raise RuntimeError(f"Millenaire SHA-256 mismatch: {actual} != {SHA256}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"materialized {FILENAME} from {url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
