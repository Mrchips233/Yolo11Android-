# TT100K45 手机模型部署记录

更新日期：2026-10-16。应用内置模型已由原来的 10 类 YOLO11s/m 更换为第二轮训练的 **YOLO11n TT100K45**。标签保留官方类别代码，不猜测中文释义。

## 使用方式

检测页默认 `960 · 清晰`，可切换 `640 · 流畅`，两档共用同一套动态权重。960 是输入长边，不是相机分辨率；图像保持比例缩放，边缘使用 114 补齐至 32 的倍数。相机从设备公布的 YUV 输出中优先选择 1280×720，其次 1280×960，未支持时选用其他合适的受支持尺寸。

默认置信度 0.465 来自验证集 F1 曲线，按类别 NMS IoU 为 0.7，每帧最多保留 300 个检测。检测框与 CSV 都使用同一份 45 类标签。CPU/GPU/Turnip 仍由用户选择；后两者是否可用取决于设备与驱动。960 的像素数为 640 的 2.25 倍，真机帧率、发热及内存尚需实测。

## 模型与评估来源

- 训练：Ultralytics 8.4.177、YOLO11n、TT100K 2016 固定45类；第一轮 640 训练100轮，再从最佳权重做960微调40轮。
- 实验：`tt100k45_yolo11n_improved_20261016_163748`，按 val mAP50-95 选择第39轮 `best.pt`。
- checkpoint SHA256：`0f3593fe0fe5470790313e2aca0857c30493629fb12789f5843c136e3a3855f2`。
- 同条件 val（610图、1517目标）960：mAP50 **80.98%**、mAP50-95 **63.01%**；第一版同960分别为75.73%、58.36%。
- 模型与设置固定后，官方 test（3071图、7706目标）960：mAP50 **81.22%**、mAP50-95 **61.98%**；未按 test 调参。
- 640 下新模型 mAP50-95 **50.23%**，相对旧模型50.59%没有整体提升。640档用于算力取舍，不能认为960收益自动保留。

上述为 PyTorch 数据集评测。相机画面、预处理、CPU/GPU数值和手机环境不同，不能直接当作 APK 的实测精度或速度。

## 文件与45类顺序

assets 中包含：

| 文件 | 大小 | SHA256 |
|---|---:|---|
| yolo11n_tt100k45.param | 21,314字节 | `c11fa69dcb12e24d6d35a87e2bc5cc86ff9d464e2e4637ec008aaa3086481694` |
| yolo11n_tt100k45.bin | 10,431,256字节 | `b1f9200d79e0008ffbd713297013ecc529de53bd3dcfeffad31b6d9c3c3775f8` |
| tt100k45.labels.txt | 234字节 | `50643c2806f4f6b06a7eda521219b06495d8a7834b6b9f7ea3711af5be5401b4` |

权重与结构合计约9.97MiB，FP32存储，未进行INT8量化。完整来源、类别和输入输出契约保存在 `tt100k45.model.json`；C++编译标签位于 `tt100k45_labels.h`。

```text
i2,i4,i5,il100,il60,il80,io,ip,p10,p11,p12,p19,p23,p26,p27,p3,p5,p6,
pg,ph4,ph4.5,ph5,pl100,pl120,pl20,pl30,pl40,pl5,pl50,pl60,pl70,pl80,
pm20,pm30,pm55,pn,pne,po,pr40,w13,w32,w55,w57,w59,wo
```

## 导出协议与重现

JNI 解码器使用原始 DFL 格式，不能直接替换成 Ultralytics 默认已经解码的 NCNN 导出文件。仓库提供 `tools/export_tt100k_ncnn.py`：在具有 PyTorch 2.14.0、Ultralytics 8.4.177 和 pnnx 20260526 的独立 Python 环境中运行：

```bash
python tools/export_tt100k_ncnn.py --weights /path/to/best.pt --output /path/to/export
```

额外依赖若装在独立目录，可使用 `--deps /path/to/dependencies`。工具在内存中 fuse Conv+BatchNorm，重写 Detect forward 输出原始三尺度分支，经 TorchScript 和 PNNX 转换，不修改原 checkpoint；使用两种输入形状保留动态高宽。

- 输入 `in0`：CHW、batch1、RGB、像素除以255，高宽为32倍数。
- 输出 `out0`：二维 `[grid_rows, 109]`，每行64个DFL logits（left/top/right/bottom 顺序，每边16bin）及45个类别logits。
- 网格顺序：stride8、16、32；每个尺度按 y 后 x；中心坐标为 `(x+0.5,y+0.5)`。
- 应用执行DFL softmax、距离期望、sigmoid、按类别NMS和逆缩放。

已用与 Android bundled runtime 相同的 **NCNN 1.0.20250503** 实际验证：

| 输入高×宽 | 输出行数×列数 |
|---|---:|
| 640×640 | 8400×109 |
| 960×960 | 18900×109 |
| 480×960 | 9450×109 |
| 960×544 | 10710×109 |
| 384×640 / 640×384 | 5040×109 |

验证集20图、640/960共40次对比，使用部署默认阈值0.465、NMS0.7、max300：48个保留预测覆盖18类，NCNN与PyTorch数量/类别一致；原始张量最大绝对误差0.0008622，框坐标最大差0.000021px，置信度最大差0.00000245。还验证了自定义原始头的解码与 Ultralytics 原头一致，坐标差小于0.000121px。仅使用val作转换检查，未使用test调参。

## 真机验证

连接并授权 USB 调试后，在 Android Studio 打开项目根目录，选择 `app` 和手机后运行。先用CPU确认加载及标签正常，再比较设备支持的GPU。覆盖960/640切换、前后摄像头、横竖屏和退出重进，记录稳定FPS、温升及漏检/误报。相机预览仍采用原有中心裁剪，检测范围跟随可见预览。

## 来源与授权

数据来源：[TT100K](https://cg.cs.tsinghua.edu.cn/traffic-sign/)，官网数据使用CC-BY-NC非商业条款；模型训练与导出基于 [Ultralytics](https://github.com/ultralytics/ultralytics)，适用AGPL-3.0或相应商业授权。来源和授权汇总见 [THIRD_PARTY](../THIRD_PARTY.md)。本记录不替代上游授权条款。
