# 维护与整改清单

本清单基于原始提交 [`ea98a5b`](https://github.com/Mrchips233/Yolo11Android-/tree/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e) 的 Java、JNI、摄像头和推理代码静态审查。未完成真机、GPU 驱动或定位精度验证；下列验收项均为后续工作，不能视为已通过。仓库结构和构建说明的整理不代表这些运行时问题已修复。当前功能和构建入口见 [README](../README.md)。

建议先处理 P1 项，再完善数据准确性和性能。每项修复应保留触发场景、验证设备和结果，便于回归。

## 1. P1：统一模型配置与 GPU 生命周期

**依据：** [模型缓存判断及 GPU 重建](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/jni/yolo11ncnn.cpp#L215) 使用 `modelid % 3`，将模型 0（s）和 3（m）视为相同；每次加载都会销毁 Vulkan 实例，但可能保留引用旧设备的模型。[输入尺寸](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/jni/yolo11ncnn.cpp#L242) 固定为 320，与界面的 640 选项不符。错误选模可直接确认；GPU 失效资源导致的具体崩溃需真机复现。

**整改：** 为四个模型选项建立显式配置表，包含模型文件、输入尺寸和类别表。相同配置直接复用；切换驱动时先释放模型和 GPU 资源，再销毁、创建 Vulkan 实例并重新加载。检测 GPU/Turnip 可用性，失败时给出明确状态或按已定义策略回退 CPU。

**验收：** CPU、GPU、Turnip 下逐项核对模型文件和 640/320 尺寸；覆盖 `s-640 → m-320`、重复加载相同配置、反复切换后端。记录至少一台 Vulkan 设备及一台不支持所选驱动的设备结果，确认无崩溃且回退行为明确。

## 2. P1：修复录制结束、空值与权限分支

**依据：** [停止录制](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/java/com/Chips/yolo11ncnn/V.java#L211) 将 `recording` 设为 null；异步 [Finalize 错误回调](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/java/com/Chips/yolo11ncnn/V.java#L274) 仍直接 `recording.close()`，且重复处理错误。[权限列表](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/java/com/Chips/yolo11ncnn/V.java#L391) 在 Android 10 请求写存储权限，但清单的 `maxSdkVersion` 为 28。YOLO 页申请相机权限后仍立即打开相机。

**整改：** 用单一录制状态机处理准备、录制、停止、完成和失败，Finalize 只清理一次，使用对应会话的录制对象并判空。定位回调收到 null 时立即返回。按系统版本申请权限：旧版共享存储权限止于 API 28，Android 10 及以上录制采用 MediaStore；相机只在授权后打开，并允许无麦克风权限时按产品约定静音录制。

**验收：** 覆盖 Android 9、10 和当前目标系统；验证首次授权、拒绝授权、快速开始/停止、后台切换及录制错误。无空指针，失败后按钮恢复，未授权时不启动相机。

## 3. P1：同步 JNI 元数据并释放 Surface 引用

**依据：** [JNI setter](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/jni/yolo11_det.cpp#L408) 修改全局字符串和经纬度，摄像头绘制线程同步读取，但双方未使用同一把锁，存在 C++ 数据竞争。[Surface 转换](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/jni/yolo11ncnn.cpp#L276) 已获取窗口引用，`set_window()` 再次 acquire，JNI 未释放自己的引用；Surface 销毁回调为空，窗口锁失败也未处理。

**整改：** 将时间、位置和输出路径作为一份受锁保护的快照，在锁外写文件。明确 native window 引用所有权，配对 acquire/release；同步窗口替换与绘制，Surface 销毁时清空窗口，窗口为空、尺寸无效或 `ANativeWindow_lock()` 失败时跳过该帧。按 Activity 生命周期停止、销毁定位客户端和地图资源。

**验收：** 连续更新位置并导出 CSV，确认同一行元数据来自同一快照；反复进入退出页面、旋转及切换前后台，检查 native 内存、定位监听和窗口资源没有持续增长，无无效 Surface 绘制。

## 4. P2：按录制会话保存 GPS，按视频 PTS 匹配帧

**依据：** [开始/停止逻辑](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/java/com/Chips/yolo11ncnn/V.java#L200) 只将 `isRecording` 置为 true，停止时未复位；GPS 文件名、上一次定位和异步回调继续使用可变全局会话字段。[帧匹配](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/java/com/Chips/yolo11ncnn/V.java#L319) 固定为 30 FPS，并由帧序号推算时间，无法正确处理其他帧率、变帧率和丢帧。

**整改：** 创建不可变的会话标识、输出目录和起始时间快照；开始时重置缓存、文件名及上次定位，停止时关闭采集并只刷入该会话数据。保存每帧解码 PTS，并建立视频时间轴与定位时间轴的对应关系。GPS 记录使用包含日期、时区的时间戳和固定数值格式，原始点与插值点分开标记；匹配设置最大时间差，无有效定位时输出缺失状态，避免默认 `(0,0)`。

**验收：** 连续录制两段视频、第一段处理期间开始第二段、跨午夜录制；会话文件不串写。使用 24/30/60 FPS 和变帧率样本核对首尾帧 PTS；模拟定位中断，确认超时点不会被当作有效匹配，CSV 可稳定解析。

## 5. P2：让抽帧、匹配和文件写入支持后台处理

**依据：** [抽帧完成回调](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/java/com/Chips/yolo11ncnn/Frame.java#L71) 被派发到主线程，随后读取 GPS、排序、逐帧匹配和写 CSV；每帧 OpenCV `Mat` 也缺少完整释放路径。长视频会增加界面阻塞和 native 内存压力。

**整改：** 让抽帧、匹配和批量 I/O 在受管理的执行器中完成，主线程只更新进度和结果；加入取消机制和 Activity 销毁处理。通过 `finally` 释放 `VideoCapture`、原始及旋转 `Mat`，避免保留整段处理结果于内存。视频读取优先支持 `content://` URI，避免依赖 MediaStore 的旧式真实路径；根据视频旋转元数据处理方向。

**验收：** 用长视频检查操作响应、处理进度和内存曲线；中途取消、离开页面或磁盘写满时能停止处理并释放资源。覆盖横竖视频和仅能通过 URI 访问的视频，输出帧方向正确。

## 6. P2：让模型、摄像头和输出错误可见

**依据：** [模型加载](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/jni/yolo11.cpp#L48) 忽略 ncnn 返回值并始终返回成功；[推理提取](https://github.com/Mrchips233/Yolo11Android-/blob/ea98a5bfe65cea6492ae40ccd64feaa6f666b20e/app/src/main/jni/yolo11_det.cpp#L354) 失败后仍解析输出。摄像头 JNI 也未传播打开失败；类别名仅有 10 项，程序没有校验模型类别数与标签表是否一致。

**整改：** 逐层传播加载、输入、提取、摄像头和文件写入错误，失败时禁用对应操作并显示可理解的恢复方式。解析前校验张量维度、行数、类别数量和标签索引。输出时间只在成功写入后更新；避免失败后同秒重试被跳过。对失败输出提供明确状态，不能把“无检测目标”和“推理失败”混为一谈。

**验收：** 注入缺失/损坏模型、错误输出名、类别数不匹配、相机占用和输出目录不可写；应用不崩溃，日志能定位原因，界面不会报告成功，恢复条件满足后可重新尝试。

## 7. P2：固定依赖、模型来源、许可证和验证基线

**整改：** 按 [README 构建说明](../README.md) 检查新克隆是否具备完整 Wrapper、Android 资源、SDK/NDK/CMake 配置和外部依赖入口；缺失项应以构建检查明确失败。记录 ncnn、OpenCV、CameraX、高德 SDK 和 Turnip 的版本、来源及 SHA-256；区分用于 Java 抽帧的完整 OpenCV 与 JNI 的 opencv-mobile，确认各目标 ABI 的库可打包。为模型提供文件清单、哈希、类别表、导出/转换步骤、训练或授权来源。维护第三方许可证及声明；仓库自身许可证须由权利人确定，不能由依赖许可证推断。

建立设备基线，记录型号、Android 版本、后端/驱动、模型哈希、输入尺寸、预热次数、推理延迟、端到端 FPS、内存和温升；用固定样本核对 CPU/GPU 检测结果及预处理、后处理差异。CSV 增加版本和必要运行元数据，便于追溯结果。

**验收：** 在干净环境按文档构建并安装；全部依赖和模型可核验，APK 包含目标 ABI 所需库，第三方声明随分发提供。保留一次 CPU 和可用 GPU 的可复现结果；缺少驱动、模型或设备时明确标记未验证，不能声称全部后端可用。
