`tt100k_val_10887_640.png` is a lossless, opaque 640×640 resize of TT100K
`10887.jpg` from this project's fixed-seed validation split of official train.
It is used only in the instrumentation-test APK, not the shipped application.

Source: Tsinghua-Tencent 100K, https://cg.cs.tsinghua.edu.cn/traffic-sign/ .
Dataset terms: CC BY-NC 4.0; see the repository's `THIRD_PARTY.md`.
The only image change is OpenCV `INTER_LINEAR` resize from 2048×2048 to 640×640.

`tt100k_val_10887_640.expected.json` records the image/checkpoint SHA-256 and
an independent Ultralytics PyTorch CPU float32 prediction with deployment
settings: input 640, confidence 0.465, class-aware NMS IoU 0.7, at most 300 boxes.
The Android NCNN CPU output must match class and count, with absolute tolerance
0.02 for probability and 2.5 pixels per box component. These tolerances allow
the phone CPU's FP16-storage/kernel rounding while catching channel-order,
coordinate, class-order and decoding mistakes.

This is a deployment regression check, not an accuracy estimate. Its expected
boxes come from the model, not manual ground truth. No official test image was
used, and no parameters were adjusted from this fixture's results.
