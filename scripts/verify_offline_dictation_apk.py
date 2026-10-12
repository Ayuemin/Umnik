#!/usr/bin/env python3
"""Verify that the on-device Vosk runtime is shipped inside a test APK."""
from __future__ import annotations

import sys
from pathlib import Path
from zipfile import ZipFile


def verify(apk: Path) -> None:
    if not apk.is_file():
        raise SystemExit(f"Missing APK: {apk}")
    with ZipFile(apk) as bundle:
        entries = {item.filename: item.file_size for item in bundle.infolist()}

    model = "assets/vosk-ru-small/am/final.mdl"
    if entries.get(model, 0) < 1024 * 1024:
        raise SystemExit(f"Missing or incomplete offline Russian model: {model}")

    for abi in ("arm64-v8a", "x86_64"):
        runtime = f"lib/{abi}/libvosk.so"
        if entries.get(runtime, 0) < 1024 * 1024:
            raise SystemExit(f"Missing Vosk native runtime for {abi}: {runtime}")

    print(
        f"Vosk offline assets OK in {apk.name}: "
        f"model {entries[model]:,} bytes, "
        "native runtime for arm64-v8a and x86_64"
    )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_offline_dictation_apk.py <apk>")
    verify(Path(sys.argv[1]))
