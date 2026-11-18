# 安卓 App 与自动发版

安卓 App 是原生版（`native/`，Kotlin + Jetpack Compose），开发细节见 [原生安卓版](./native.md)。它在 2026 年 11 月替换了原来用 Capacitor 把网页打包的套壳版（#104）：

- **包名、签名都不变**：`debating.ground.app`，同一把签名。
- **versionCode 接着往上涨**：versionCode 规则不变，手机上看到的是一次普通更新。
- **知识库自动搬过来**：第一次打开时，从套壳版留下的 WebView 存储里搬过来，旧数据不删。

## 本地构建

需要 Android SDK 和 JDK 21：

```bash
cd native && ./gradlew assembleDebug   # debug 包，包名带 .dev，能和正式版同时装
```

## CI/CD（`.github/workflows/android.yml`）

| 触发 | 做什么 |
| --- | --- |
| Pull Request | 构建 debug 包（包名带 `.dev`），作为构建产物上传（14 天），可以直接装来试 |
| 合并到 `main` | 构建正式签名的 APK，更新同一个“预览版”Release（标签 `preview` 跟着 main 走） |
| 推送 `v*` 标签 | 构建正式签名的 APK，发正式 Release，自动生成更新说明 |

版本号：`versionName` 来自 `package.json`（预览版加 `-preview.N`，正式版用标签），`versionCode` 是 `1000 + 工作流运行序号`，保证每次都比上一次大。

发正式版：

```bash
# 先把 package.json 的 version 改成要发的版本并合进 main，然后
git tag v0.10.0 && git push origin v0.10.0
```

## 签名密钥

安卓只允许用**同一把密钥**签名的新版本覆盖安装旧版本。密钥丢了，以后的版本用户只能卸载重装，本机知识库会丢。所以：

- 密钥文件和口令离线保存（密码管理器 + 加密 U 盘），**不进任何仓库**（`.gitignore` 已经排除 `*.jks`、`*.keystore`）。
- CI 从仓库 Secrets 里取：

  | Secret | 内容 |
  | --- | --- |
  | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 sibian-release.jks` 的输出 |
  | `ANDROID_KEYSTORE_PASSWORD` | 密钥库口令 |
  | `ANDROID_KEY_ALIAS` | `sibian` |
  | `ANDROID_KEY_PASSWORD` | 密钥口令（PKCS12 和密钥库口令相同） |

  用 GitHub CLI 设置：`gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 sibian-release.jks)`，其余三个同理。
- 没配 Secrets 时，合并到 main 只构建 debug 包作为构建产物、不更新预览版；推标签会直接失败，不会发出没签名的正式版。
