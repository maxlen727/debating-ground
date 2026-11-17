# 原生安卓版（开发中）

用 Kotlin + Jetpack Compose 重写的安卓 App，代码在 `native/`。它会直接替换现在的 Capacitor App（`android/`）：同一个包名、同一把签名密钥，用户升级后知识库原样还在。路线图和阶段划分见 [issue #70](https://github.com/maxlen727/debating-ground/issues/70)。

开发期间：

- 现有 App 照常由 `android.yml` 发布，原生版只构建、测试，不发布。
- 原生版的 debug 包名是 `debating.ground.app.dev`，可以和正式版同时装在一台手机上对比。

## 本地构建

需要 JDK 21 和安卓 SDK（compileSdk 37）。用 Android Studio 打开 `native/` 目录即可，命令行：

```bash
cd native
./gradlew assembleDebug          # debug 包在 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 单元测试和截图测试
./gradlew ktlintCheck lintDebug  # 代码检查（ktlintFormat 自动整理写法）
```

命令行构建时，用 `ANDROID_HOME` 环境变量或 `native/local.properties`（`sdk.dir=…`，不进仓库）告诉 Gradle SDK 在哪里。

## 和网页版保持一致

原生版和网页版是同一套设计。下面这些由测试把关，改一边漏了另一边，`testDebugUnitTest` 会失败：

- **颜色令牌**：`ui/theme/Tokens.kt` 和 `src/styles.css` 的浅色、深色令牌逐项比对（`TokensTest`）。网页版新增令牌，原生版也要加。
- **图标**：`ui/Icons.kt` 用和 `src/components/ui.tsx` 相同的 SVG 路径（`IconPathsTest`）。
- **背景氛围**：`ui/Atmosphere.kt` 的色块位置、半径、颜色、浓度、漂移周期和距离，和 `styles.css` 的“背景氛围”一节逐项比对（`AtmosphereTest`）。系统关掉动画时不漂；截图测试里也固定成静止画面。
- **主导航**：四个标签和网页版一样。窄屏用底部导航；宽度 ≥ 840dp 或横屏高度 ≤ 500dp 用左侧导航（`NavigationTest`）。

字体分工、字号阶梯、只用系统字体这些规则，和 AGENTS.md 的界面规范相同，写在 `ui/theme/Theme.kt`。

## 代码检查

- **ktlint**：管 Kotlin 写法，用 Kotlin 官方编码规范，和 Android Studio 的默认格式一致，设置在 `native/.editorconfig`。它直接调 ktlint 的命令行版本，不用第三方 Gradle 插件，因为那些插件和 AGP 9 自带的 Kotlin 支持不一定兼容。`./gradlew ktlintFormat` 能自动改好大部分问题。
- **Android Lint**：AGP 自带，开了严格模式，警告也算失败。“有新版本可用”这类要联网的检查关掉了，否则同一份代码今天能过、明天可能就过不了；版本升级交给 dependabot。
- 确属故意的写法，单独关掉那一处检查并写明原因，例如 `ui/Icons.kt` 里那行很长的图标路径。

## 没有模拟器时怎么看界面

`ScreenshotTest` 用 Robolectric 在 JVM 里渲染页面，存成图片到 `app/build/screenshots/`。目前覆盖这几种情况：

- 手机 390dp（浅色、深色）、横着拿的手机 844×390dp
- 最窄的 320dp
- 平板 1280dp
- 切换标签

CI 会把截图作为构建产物上传，审查界面改动时直接下载来看。

截图的局限：

- Robolectric 里没有中文衬线字体，衬线文字会显示成黑体；字体效果要在真机上看。
- 截图里系统栏高度为 0，避让状态栏、刘海是否正确，也要在真机上确认。

第一次跑测试时，Robolectric 会从 Maven Central 下载安卓运行库（几百 MB），之后就用本地缓存了。

## CI（`.github/workflows/native.yml`）

`native/`、`src/styles.css`、`src/components/ui.tsx` 有改动时运行：代码检查（ktlint、Android Lint），构建 debug 包，跑单元测试和截图测试，上传 debug 包、截图和检查报告（保留 14 天）。不签名、不发 Release。
