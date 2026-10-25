# 第三方依赖与模型说明

| 组件 | 当前版本 / 来源 | 用途与说明 |
| --- | --- | --- |
| ncnn | [Tencent/ncnn](https://github.com/Tencent/ncnn)，仓库目录 `ncnn-20250503-android-vulkan` | JNI 推理，源码包含 BSD 3-Clause 声明；分发时保留相关版权及许可证。 |
| opencv-mobile | [nihui/opencv-mobile](https://github.com/nihui/opencv-mobile)，4.11.0 | JNI 图像处理，仓库内已有 LICENSE 和第三方许可证目录。 |
| OpenCV Java | [OpenCV](https://github.com/opencv/opencv)，Maven `org.opencv:opencv:4.11.0` | Java 视频解码和 JPEG 导出；与 JNI 的精简版分别使用。 |
| AndroidX / CameraX | Google Maven，版本见 `app/build.gradle` | Activity、界面与视频录制；保留其发行包附带的许可证。 |
| 高德 SDK | [高德开放平台](https://lbs.amap.com/)，版本见 `app/build.gradle` | 地图与定位；需自行申请 Key，并遵循服务条款和隐私要求。 |
| Turnip | `app/src/main/jniLibs/arm64-v8a/libvulkan_freedreno.so` | 仓库内现有预编译驱动；原始构建来源、对应版本及完整授权声明尚需维护者补充，不能仅由文件名推断。 |
| YOLO11 模型 | [Ultralytics YOLO11](https://github.com/ultralytics/ultralytics)，8.4.177；TT100K45 微调 YOLO11n | 权重、导出协议及哈希见 [模型记录](docs/tt100k45-model.md) 和 assets 元数据。Ultralytics 适用 AGPL-3.0 或相应商业授权，模型导出不会改变上游授权条件。 |
| TT100K | [清华大学 TT100K 2016](https://cg.cs.tsinghua.edu.cn/traffic-sign/)，45 类子集 | 训练数据来源遵循官网标明的 CC-BY-NC 非商业条款；仓库只包含派生检测模型与类别代码，不打包数据集。项目未新增或代替上游授权。 |

仓库目前没有为项目自身指定根目录 LICENSE。发布或再分发前，应由权利人明确项目代码、模型及训练数据的授权范围，并补齐缺失的第三方声明；不能根据某个依赖的许可证推断整个项目的许可证。
