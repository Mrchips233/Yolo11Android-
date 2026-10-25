"""Export a YOLO11n TT100K45 checkpoint for the Android raw-DFL decoder.

Requires PyTorch, Ultralytics and pnnx in the active Python environment, or in
the optional --deps directory. Inference remains on CPU during conversion.
Android performs DFL decoding, sigmoid, grid construction and class-aware NMS.
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import types


CLASS_NAMES = (
    "i2", "i4", "i5", "il100", "il60", "il80", "io", "ip", "p10", "p11",
    "p12", "p19", "p23", "p26", "p27", "p3", "p5", "p6", "pg", "ph4",
    "ph4.5", "ph5", "pl100", "pl120", "pl20", "pl30", "pl40", "pl5",
    "pl50", "pl60", "pl70", "pl80", "pm20", "pm30", "pm55", "pn", "pne",
    "po", "pr40", "w13", "w32", "w55", "w57", "w59", "wo",
)


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True,
                        help="Trained YOLO11n TT100K45 .pt checkpoint")
    parser.add_argument("--output", type=Path, required=True,
                        help="Directory for the converted model, labels and conversion log")
    parser.add_argument("--deps", type=Path,
                        help="Optional directory containing extra Python packages, such as pnnx")
    return parser.parse_args()


def load_raw_model(weights):
    import torch
    from ultralytics import YOLO
    from ultralytics.nn.modules import C2f, Detect

    yolo = YOLO(str(weights))
    names = yolo.names
    if len(names) != 45 or tuple(names[i] for i in range(45)) != CLASS_NAMES:
        raise ValueError("Checkpoint must use the 45 TT100K classes in Android label order")

    model = yolo.model.cpu().float().eval().fuse()
    head = model.model[-1]
    if not isinstance(head, Detect) or head.nc != 45 or head.reg_max != 16:
        raise ValueError("Expected a 45-class Detect head with reg_max=16")
    if head.stride.tolist() != [8.0, 16.0, 32.0]:
        raise ValueError("Expected detection strides [8, 16, 32]")

    def raw_detect_forward(self, x):
        outputs = []
        for i in range(self.nl):
            boxes = self.cv2[i](x[i])
            classes = self.cv3[i](x[i])
            outputs.append(torch.cat((boxes, classes), 1)
                           .view(x[i].shape[0], 64 + self.nc, -1).transpose(1, 2))
        return torch.cat(outputs, 1)

    head.forward = types.MethodType(raw_detect_forward, head)
    for module in model.modules():
        if isinstance(module, C2f):
            module.forward = module.forward_split
    for parameter in model.parameters():
        parameter.requires_grad_(False)
    return model, names


def main():
    args = parse_args()
    weights = args.weights.expanduser().resolve()
    output = args.output.expanduser().resolve()
    if not weights.is_file():
        raise FileNotFoundError(f"Checkpoint not found: {weights}")
    if args.deps is not None:
        deps = args.deps.expanduser().resolve()
        if not deps.is_dir():
            raise NotADirectoryError(f"Dependency directory not found: {deps}")
        sys.path.insert(0, str(deps))

    output.mkdir(parents=True, exist_ok=True)
    config_dir = output / ".cache" / "ultralytics"
    config_dir.mkdir(parents=True, exist_ok=True)
    os.environ["YOLO_CONFIG_DIR"] = str(config_dir)

    # Import after argument parsing so --help works without export dependencies.
    import pnnx
    import torch

    torch.set_num_threads(6)
    model, names = load_raw_model(weights)
    example = torch.zeros(1, 3, 640, 640)
    traced_path = output / "raw_dfl.torchscript.pt"
    with torch.inference_mode():
        trace = torch.jit.trace(model, example, strict=True)
        trace.save(str(traced_path))

    (output / "labels.txt").write_text(
        "\n".join(names[i] for i in range(45)) + "\n", encoding="utf-8")
    # Two aspect ratios let pnnx retain spatially dynamic reshape dimensions.
    command = [
        str(pnnx.EXEC_PATH), str(traced_path),
        "inputshape=[1,3,640,640]", "inputshape2=[1,3,480,960]",
        "device=cpu", "fp16=0", "optlevel=2",
        f"pnnxparam={output / 'raw_dfl.pnnx.param'}",
        f"pnnxbin={output / 'raw_dfl.pnnx.bin'}",
        f"pnnxpy={output / 'raw_dfl_pnnx.py'}",
        f"pnnxonnx={output / 'raw_dfl.pnnx.onnx'}",
        f"ncnnparam={output / 'yolo11n_tt100k45.param'}",
        f"ncnnbin={output / 'yolo11n_tt100k45.bin'}",
        f"ncnnpy={output / 'raw_dfl_ncnn.py'}",
    ]
    (output / "export_command.json").write_text(
        json.dumps(command, ensure_ascii=False, indent=2), encoding="utf-8")
    with (output / "pnnx.log").open("w", encoding="utf-8") as log:
        subprocess.run(command, cwd=output, stdout=log, stderr=subprocess.STDOUT, check=True)
    print(f"Exported {output / 'yolo11n_tt100k45.param'}", flush=True)


if __name__ == "__main__":
    main()
