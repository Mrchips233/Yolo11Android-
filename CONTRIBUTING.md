# 开发与提交约定

先按照 [README](README.md) 准备构建环境，在独立分支上修改。业务修复、仓库维护和文档更新应分成可单独审查的提交。

## 提交内容

- 提交源码、资源、构建配置、Gradle Wrapper 和必要文档。
- 当前模型、ncnn、opencv-mobile 和 Turnip 库是构建输入；迁移到外部下载前应保留，并记录版本、来源和校验值。
- 不提交 `.cxx/`、`.gradle/`、`build/`、APK、IDE 缓存、`local.properties`、API Key 或签名文件。
- 遵循 `.editorconfig` 和 `.gitattributes`；不要在功能提交中混入全仓库排版修改。

## 提交说明

采用 Conventional Commits 格式：

```text
<type>(<scope>): <具体修改>
```

常用类型：`feat` 新功能、`fix` 缺陷修复、`docs` 文档、`build` 构建配置、`chore` 维护、`refactor` 重构、`test` 验证。scope 可省略。

```text
build(android): restore the Gradle project configuration
fix(camera): handle recording finalization errors
docs(readme): document features and build steps
chore(repo): stop tracking native build caches
```

提交正文说明修改原因和验证结果。新提交使用该格式，已有历史保留，便于追溯。

## 提交前检查

```bash
git diff --check
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。涉及相机、GPU、录制或定位的修改，还需要记录真机型号、Android 版本和复现场景；无法完成的检查要注明原因。确认暂存区没有本地路径、密钥和构建产物，再提交。
