"""Check that the packaged model, compiled labels, UI modes and provenance agree."""

import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
metadata = json.loads((ASSETS / "tt100k45.model.json").read_text(encoding="utf-8"))
names = metadata["names"]
assert len(names) == metadata["class_count"] == 45 and len(set(names)) == 45
labels = (ASSETS / "tt100k45.labels.txt").read_text(encoding="utf-8").splitlines()
assert labels == names, "Packaged labels disagree with checkpoint order"
header = (ROOT / "app/src/main/jni/tt100k45_labels.h").read_text(encoding="utf-8")
array = re.search(r"class_names\[\]\s*=\s*\{(.*?)\};", header, re.S).group(1)
assert re.findall(r'"([^"\n]+)"', array) == names, "Compiled labels disagree with model"
for record in metadata["artifacts"].values():
    path = ASSETS / record["filename"]
    assert path.stat().st_size == record["bytes"], f"Wrong byte count: {path.name}"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == record["sha256"], f"Wrong SHA256: {path.name}"
param = (ASSETS / "yolo11n_tt100k45.param").read_text(encoding="utf-8")
layers = param.splitlines()
assert layers[0] == "7767517"
assert int(layers[1].split()[0]) == len(layers[2:])
assert layers[2].split()[1] == "in0" and " out0 " in (" " + layers[-1] + " ")
assert metadata["output"]["row_width"] == 4 * metadata["reg_max"] + len(names) == 109
assert len([line for line in layers if line.startswith("Reshape") and "1=109" in line]) == 3
resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
modes = resources.find("string-array[@name='model_array']").findall("item")
assert len(modes) == 2 and "960" in modes[0].text and "640" in modes[1].text
native_modes = (ROOT / "app/src/main/jni/yolo11ncnn.cpp").read_text(encoding="utf-8")
table = re.search(r"model_configs\[\]\s*=\s*\{(.*?)\};", native_modes, re.S).group(1)
assert [int(value) for value in re.findall(r'"yolo11n_tt100k45.bin",\s*(\d+)', table)] == [960, 640]
settings = metadata["deployment"]
decoder = (ROOT / "app/src/main/jni/yolo11_det.cpp").read_text(encoding="utf-8")
assert float(re.search(r"const float prob_threshold = ([\d.]+)f", decoder).group(1)) == settings["probability"]
assert float(re.search(r"const float nms_threshold = ([\d.]+)f", decoder).group(1)) == settings["class_aware_nms_iou"]
assert "picked.size() >= 300" in decoder and settings["max_detections"] == 300
assert not any((ASSETS / name).exists() for name in ("yolo11s.bin", "yolo11m.bin", "yolo11s.param", "yolo11m.param")), "Unused 10-class models remain packaged"
print("Model assets verified: hashes, 45-class order, raw DFL109, 960/640 modes and thresholds.")
