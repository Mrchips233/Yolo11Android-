# 视频识别与相机改造验证记录

日期：2026-10-16。最终代码位于 `D:\Works\Yolo11Android`。用户要求由 Android Studio Run / Debug 部署；因此最后一轮修改只做本机构建，没有再次安装手机，也没有运行需要安装测试 APK 的设备仪器测试。此前一次较早测试版安装已完成。以下通过项均为本机检查，不代表真机效果或速度已经提升。

## 已通过

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --max-workers=2 --console=plain
python tests/run_camera_checks.py
python tests/run_runtime_metrics_checks.py
python tests/check_model_assets.py
git diff --check
```

- 四 ABI Debug APK、设备测试 APK 均构建成功。
- 22 项 Java 单元测试通过：GPS 匹配 6、YUV 拷贝 3、native 会话竞态 6、视频时间/CSV/颜色转换 7。
- 相机宿主检查：8 项尺寸、14 项帧所有权/关闭重开/YUV stride 检查通过。
- 9 项性能统计计时、重置和失败分类检查通过；模型哈希、45 类顺序与解码阈值检查通过。
- Android Lint 无 error / fatal，保留现有警告；未用 suppression 隐藏错误。

## 在 Android Studio 验证

打开已有工程即可读取修改；依赖更新提示出现时执行 Gradle Sync。选择 `app`、`debug` 构建变体和已连接手机，点击 Debug（虫子图标 / Shift+F9）。Java 界面与处理逻辑可直接设断点；调试 JNI 时在运行配置中选择合适的 Native / Dual 调试类型并等待 LLDB 初始化。

1. 首页进入「导入视频 · 逐帧识别」，先选数秒 SDR 视频，再选可写的导出文件夹。先用 CPU / 640，随后检查 960。
2. 确认进度持续变化；完成后检查图片数与独立解码帧数一致。抽查首尾帧、旋转方向、检测框与表格位置。表格的 `timestamp_us` 是原始 PTS，`elapsed_us` 是相对首帧的时间。
3. 检查同帧多个目标分别列行、无目标帧仍有图片与空检测行，`run.json` 为 `completed`。
4. 中途取消或离开页面，检查停止后保留部分结果，`run.json` 为 `cancelled`。授权失效或不可解码视频应显示失败，不能误报完成。
5. 打开实时检测，切换前后摄像头、CPU / GPU / Turnip 和 640 / 960，检查页面响应、返回与前后台切换。GPU / Turnip 不支持时应明确报错并允许换回 CPU。

设备测试源码已编译：`NativeBitmapInstrumentedTest`（真实模型与单图 PyTorch 对照）、`VideoRecognitionProcessorDeviceTest`（真实编解码、PTS/方向/空帧/多目标与取消失败）、`CameraLifecycleInstrumentedTest`（后端切换与主线程响应）。尚未在设备执行；API 24～28、变帧率/B 帧厂商样本、长视频、4K 内存峰值和低存储仍需扩展设备验收。

## Logcat 观察

使用 Android Studio Logcat 按 `YoloPerf`、`ModelConfig` 和 `NdkCamera` 标签筛选：

- `YoloPerf` 约每 2 秒输出已处理回调吞吐 FPS、推理/绘制平均和最大耗时、目标与失败计数。FPS 包含失败回调，应结合失败数判断；它不代表摄像头刷新率。
- `draw_ms` 包含当前实时检测 CSV 写入，不能当纯绘制耗时。
- `ModelConfig` 输出模型复用/重载状态、加载耗时和相机启动状态。
- `camera_stats` 约每 5 秒及关闭时输出接收、处理、替换等待帧、取消等待帧、采集失败和 buffer lost。实时相机主动替换旧等待帧以维持最新画面，属于实时策略；导入视频则逐帧处理，不使用此丢帧策略。

高德功能按用户要求保留现状，缺少 Key 的相关日志不纳入本次视频识别结果。检测准确度仍需人工真值与固定协议，见 [准确度评测方案](mobile-accuracy-evaluation.md)。
