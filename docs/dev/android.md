# 安卓 App 与自动发版

App 用 [Capacitor](https://capacitorjs.com/) 把 `dist/` 原样打包进安卓工程（`android/`），不另写一套界面。配置在 `capacitor.config.json`：包名 `debating.ground.app`，页面来源是 `https://localhost`。

## 本地构建

需要 Android Studio（或 Android SDK + JDK 21）。

```bash
npm run android:sync          # 构建界面，并同步到 android/
npx cap open android          # 用 Android Studio 打开，连手机直接运行
cd android && ./gradlew assembleDebug   # 或者命令行构建 debug 包
```

`android/app/src/main/assets/public/` 是同步出来的界面，不进仓库。改了界面后重新 `npm run android:sync`。

## App 里的差异写在哪

- `src/lib/platform.ts`：`isNativeApp()` 判断是否在 App 里（只读 Capacitor 注入的全局对象，测试里也能用），`PUBLIC_APP_URL` 是在线版地址（构建时可用 `VITE_PUBLIC_APP_URL` 覆盖）。
- `src/lib/native.ts`：只在 App 里按需加载，接管返回键（逻辑在 `actions.ts` 的 `goBack()`）。
- 本机服务、下载文件在 App 里不可用：设置页不显示“本机服务”，导出改成复制到剪贴板。
- 图标和启动图由 `scripts/android-icons.py` 从标志生成（深绿圆角方块 + 衬线“〞”）。

## CI/CD（`.github/workflows/android.yml`）

| 触发 | 做什么 |
| --- | --- |
| Pull Request | 构建 debug 包，作为构建产物上传（14 天），可以直接装来试 |
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
