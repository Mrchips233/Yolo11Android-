# YOLO11 Android

基于自训练 YOLO11 模型与 ncnn 的 Android 目标检测应用，集成相机实时识别、视频录制、GPS 定位记录和 CSV 数据导出。Java 层负责界面、CameraX 录制与定位，C++ / JNI 层负责 ncnn 推理和检测结果绘制。

## 功能

| 功能 | 当前实现 |
| --- | --- |
| 相机实时检测 | 显示检测框、类别、置信度和 FPS，支持切换前后摄像头。 |
| 自训练模型 | 内置 YOLO11s / YOLO11m 的 ncnn 模型；当前类别标签为 `W`、`P`、`S`、`I`、`A`、`WD`、`PD`、`SD`、`ID`、`AD`。 |
| 推理后端 | 提供 CPU、Vulkan GPU 和 Turnip 选项；GPU / Turnip 需要设备和驱动支持，建议先用 CPU 验证。 |
| 定位与地图 | 通过高德 SDK 显示地图、经纬度和定位精度，并记录带时间的 GPS 数据。 |
| 视频录制与抽帧 | 使用 CameraX 录制视频，按权限情况启用音频；录制完成后通过 OpenCV 导出 JPEG 帧。 |
| 数据导出 | 实时检测结果写入 CSV；录制视频的帧与 GPS 记录匹配后生成 CSV，便于后续分析。 |

这是开发中的项目。界面提供 640 / 320 模型选项，但当前 JNI 输入尺寸固定为 320；模型切换、录制错误处理和帧时间匹配仍有已知问题，详见 [维护与整改清单](docs/maintenance.md)。当前仓库不包含训练脚本、数据集和端侧性能基准。

## 构建环境

| 项目 | 版本 / 要求 |
| --- | --- |
| JDK | 建议 17；Gradle 8.11.1 也支持 JDK 21。 |
| Gradle | 8.11.1，使用仓库中的 Wrapper，无需另装全局 Gradle。 |
| Android Gradle Plugin | 8.9.2。 |
| Android SDK | Platform 35；Build Tools 35.0.0。 |
| Android NDK | 27.0.12077973。 |
| CMake | 3.31.5。 |
| 最低 Android 版本 | Android 7.0 / API 24。 |
| 依赖下载 | 首次构建需联网访问 Google Maven、Maven Central 和 Gradle 分发服务。 |

`compileSdk` 为 35。本轮补齐工程配置保留了原有 `targetSdk` 行为；提高目标 SDK 需要另行适配存储、权限和后台定位流程。

### 1. 获取工程与 SDK

```bash
git clone https://github.com/Mrchips233/Yolo11Android-.git
cd Yolo11Android-
```

用 Android Studio 打开仓库根目录，选择 JDK 17 作为 Gradle JDK。在 SDK Manager 中安装 Android SDK Platform 35、Build Tools 35.0.0、NDK 27.0.12077973 和 CMake 3.31.5；NDK / CMake 需启用“Show Package Details”以选择对应版本。

已配置 Android 命令行工具时，也可以使用：

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973" "cmake;3.31.5"
```

### 2. 配置本地 SDK 与高德 Key

若尚无 `local.properties`，复制 `local.properties.example` 并重命名；若已有该文件，直接补充本机配置。例如：

```properties
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
AMAP_API_KEY=YOUR_AMAP_ANDROID_KEY
```

在 [高德开放平台](https://console.amap.com/) 创建 Android Key，并绑定包名 `com.Chips.yolo11ncnn` 和对应签名证书的 SHA-1。SDK 配置完成后，可用 `gradlew.bat :app:signingReport`（Windows）或 `./gradlew :app:signingReport` 查看证书信息。

`AMAP_API_KEY` 也可通过同名环境变量提供，优先于本地配置。未配置 Key 时仍可构建，但地图与定位无法正常使用。`local.properties`、密钥和签名文件均已加入忽略规则。

### 3. 检查模型和原生依赖

正常克隆已包含以下构建输入，无需手工创建 `:opencv` 模块：

```text
app/src/main/assets/
  yolo11s.param / yolo11s.bin
  yolo11m.param / yolo11m.bin
app/src/main/jni/
  ncnn-20250503-android-vulkan/
  opencv-mobile-4.11.0-android/
app/src/main/jniLibs/arm64-v8a/
  libvulkan_freedreno.so
```

Java 抽帧使用 Maven Central 的 `org.opencv:opencv:4.11.0`，JNI 推理使用仓库内的 opencv-mobile；两者用途不同。Turnip 库仅提供 `arm64-v8a` 版本，其可用性还取决于 GPU 型号。

### 4. 编译与安装

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/`。安装需连接并授权 Android 设备；首次启动按提示授予相机、定位，以及录制音频所需权限，并开启系统定位。

Release 构建使用 `:app:assembleRelease`，分发前需自行配置发布签名和与之对应的高德 Key；仓库不提供发布密钥。

仓库提供 [GitHub Actions 构建模板](docs/android-ci.example.yml)。使用有工作流发布权限的账号或 Token 将其复制到 `.github/workflows/android.yml` 后，会在 `master` 推送和 Pull Request 时用 JDK 17 及上述工具版本编译 Debug APK。模板目前尚未启用；构建检查也不能代替相机、GPU、定位和录制的真机验证。

### 手动发布 UI 预览版

`.github/workflows/release-ui-preview.yml` 提供手动发布入口。在 GitHub Actions 页面运行该流程，会构建四种 ABI 的 Debug APK，生成 SHA-256 校验文件，并发布附带静态界面预览图的 `v1.1.0-ui-preview.1` 预发布版本。发布说明会注明调试包、高德 Key 配置和设备验证状态；同名已发布版本不会被覆盖。

## 输出文件

- 视频：Android 10 及以上写入共享媒体库的 `Movies/CameraX-video`。
- 抽帧图片、GPS 文本及 `匹配结果.csv`：应用专属外部目录的 `Pictures/<视频名称>/`。
- 实时检测 CSV：应用专属外部文件目录下以时间命名的 `results.csv`。

应用专属目录通常位于 `Android/data/com.Chips.yolo11ncnn/files/`；目录可见性受系统版本和文件管理器限制。卸载应用可能移除其中的数据，分析前请导出所需文件。

## 工程结构

```text
app/
  build.gradle                Android 模块配置
  src/main/
    assets/                   自训练模型
    java/com/Chips/yolo11ncnn/ 界面、录制、定位、抽帧与匹配
    jni/                      C++ / JNI、ncnn 与 opencv-mobile
    jniLibs/                  Turnip 原生库
    res/                      界面与资源
gradle/wrapper/               固定版本的 Gradle Wrapper
docs/maintenance.md          已知问题与整改验收标准
build.gradle                 Android 插件版本
settings.gradle              模块与依赖仓库
gradle.properties            Gradle / AndroidX 配置
```

提交规范见 [CONTRIBUTING](CONTRIBUTING.md)，第三方来源及授权说明见 [THIRD_PARTY](THIRD_PARTY.md)。构建缓存、IDE 设置、APK 与本地配置不应提交到 Git。
