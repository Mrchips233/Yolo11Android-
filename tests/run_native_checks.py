#!/usr/bin/env python3
"""Compile and run host checks against the production CSV export class."""

from pathlib import Path
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="yolo-native-checks-") as temporary:
    executable = Path(temporary) / "native_csv_test"
    subprocess.run(
        [
            "g++", "-std=c++11", "-Wall", "-Wextra", "-pedantic", "-pthread",
            "-I", str(root / "app/src/main/jni"),
            str(root / "tests/native_csv_test.cpp"), "-o", str(executable),
        ],
        check=True,
    )
    subprocess.run([str(executable)], check=True)
