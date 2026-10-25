#!/usr/bin/env python3
"""Compile and exercise the production runtime-metrics window on the host."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]
source = root / "tests/runtime_metrics_test.cpp"
includes = root / "app/src/main/jni"
with tempfile.TemporaryDirectory(prefix="yolo-runtime-metrics-checks-") as temporary:
    executable = Path(temporary) / ("runtime_metrics_test.exe" if os.name == "nt" else "runtime_metrics_test")
    compiler = shutil.which("g++") or shutil.which("clang++")
    if compiler:
        subprocess.run(
            [compiler, "-std=c++11", "-O2", "-Wall", "-Wextra", "-pedantic",
             "-I", str(includes), str(source), "-o", str(executable)], check=True,
        )
    elif os.name == "nt":
        properties = root / "local.properties"
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        if not sdk and properties.exists():
            for line in properties.read_text(encoding="utf-8").splitlines():
                if line.startswith("sdk.dir="):
                    sdk = line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\")
                    break
        ndks = sorted((Path(sdk) / "ndk").glob("*/toolchains/llvm/prebuilt/windows-x86_64/bin")) if sdk else []
        if not ndks:
            raise SystemExit("Set ANDROID_SDK_ROOT or sdk.dir in local.properties, or install a C++ host compiler.")
        binaries = ndks[-1]
        object_file = Path(temporary) / "runtime_metrics_test.obj"
        subprocess.run(
            [str(binaries / "clang++.exe"), "--target=x86_64-pc-windows-msvc", "-std=c++11", "-O2",
             "-Wall", "-Wextra", "-pedantic", "-fno-stack-protector", "-nostdlib", "-I", str(includes),
             "-c", str(source), "-o", str(object_file)], check=True,
        )
        subprocess.run(
            [str(binaries / "ld.lld.exe"), "-flavor", "link", "/entry:main", "/subsystem:console",
             "/nodefaultlib", f"/out:{executable}", str(object_file)], check=True,
        )
    else:
        raise SystemExit("Install g++ or clang++ to run the runtime metrics checks.")
    subprocess.run([str(executable)], check=True)
print("Runtime metrics: all 9 timing/reset/failure checks passed.")
