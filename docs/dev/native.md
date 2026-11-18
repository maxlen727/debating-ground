# 原生安卓版（开发中）

用 Kotlin + Jetpack Compose 重写的安卓 App，代码在 `native/`。它在 2026 年 11 月替换了原来用 Capacitor 打包网页的套壳版（#104）：同一个包名、同一把签名密钥，用户升级后知识库会自动搬过来。路线图和阶段划分见 [issue #70](https://github.com/maxlen727/debating-ground/issues/70)。

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
- **背景氛围**：色块是 `scripts/atmosphere.py` 离线渲染的高斯模糊图，和网页版共用（`res/drawable-nodpi/atmosphere_*.webp`，`AtmosphereTest` 逐字节比对）；漂移路线和 `styles.css` 的 `@keyframes drift` 一致。改了 `src/assets/atmosphere/atmosphere.json` 或用到的颜色令牌，重新跑一次脚本（需要 Python 的 numpy 和 Pillow）。系统关掉动画时不漂；截图测试里也固定成静止画面。
- **主导航**：四个标签和网页版一样。窄屏用底部导航；宽度 ≥ 840dp 或横屏高度 ≤ 500dp 用左侧导航（`NavigationTest`）。

字体分工、字号阶梯、只用系统字体这些规则，和 AGENTS.md 的界面规范相同，写在 `ui/theme/Theme.kt`。

## 数据层和网页版的标准答案

数据层在 `data/`：数据结构、存档检查、间隔复习、Markdown 导出、本地存储。存档格式和网页版的 `sibian-v3` 完全一致，导出文件可以互通，替换旧 App 时（M6）也能直接迁移知识库。

为了让两边的逻辑不走样，`scripts/native-fixtures.ts` 用**网页版的真实代码**跑出几个场景的存档：做完的讨论、带分支的画布、进行中的辩论、内容很全的知识卡片、写坏的存档、旧版本的存档。它再记下网页版读回这些存档的结果和导出的 Markdown，放在 `native/app/src/test/resources/fixtures/`。原生版的 `FixturesTest` 读同样的存档，结果必须逐字一致。

- 改了存档结构、`src/lib/restore.ts` 或 `src/lib/exporter.ts`，运行 `npm run fixtures:native` 重新生成，再跑原生测试，看看原生版要跟着改什么。
- 网页版的 `tests/native-fixtures.test.ts` 会检查标准答案有没有过期，忘了重新生成就会失败。
- 生成过程固定了随机数、时间和时区（上海），每次的结果都一样。

存档检查分两层：`Restore.kt` 在 JSON 层面逐行对应网页版，保证结果逐字一致；`loadState` 再转成类型化的数据，给界面和业务逻辑用。

## 从套壳版搬知识库

原生版要直接替换套壳版（#104）：同一个包名、同一把签名，用户在手机上看到的是一次普通更新。套壳版的数据在 WebView 的 localStorage 里（来源 `https://localhost`，键 `sibian-v3`，更早的是 `sibian-v2`），升级以后还留在手机上。

`migrate/` 负责把它搬过来：

- 第一次启动时，`WebViewLegacySource` 在同一个来源里打开一个只读的小页面，读出这两份数据。页面内容由 App 直接给出，不访问网络。
- `LegacyImport` 用 `loadState` 做和网页版一样的结构检查，再写进原生存储。
- 规则：
  - 原生存储里已经有用户自己的东西（卡片、讨论、准备好的计划）就不搬，也不覆盖；只有设置不算，第一次没搬成时用户改过设置，下次照样重试。
  - 读成功才记下“已搬过”（`legacy-imported` 文件），没读成下次启动再试。
  - **旧数据从不清除**。
  - 旧存档里的 `aiMode` 是 `api` 时改成演示模式：套壳版里没有本机服务，原生版的 `api` 是直接连接，要重新填密钥。
- 搬完后用短暂提示告诉用户搬来了几张卡片。

Robolectric 里的 WebView 不跑 JS，`LegacyImportTest` 用网页版写出来的真实存档代替读取，覆盖上面这些规则；`WebViewLegacySource` 本身要在真机上从套壳版升级一次来验证。

## AI 层

AI 层在 `ai/`，提供三种来源：

- **演示模式**（`Demo.kt`）：照 `src/lib/demo.ts` 移植，没有网络和密钥也能走完“机会成本”（约定 3）。网页版直接读改全局状态，这里改成输入讨论、输出结果和新的 `demoStep`。标准答案里的 `demo-cases.json` 记下了网页版在十几种讨论状态下、每种用途的调用结果，`DemoTest` 逐条比对。
- **自己部署的后端**（`Remote.kt`）：和 Worker 的 `/api/config`、`/api/test`、`/api/generate` 对接，访问口令放在请求头里，不跟随重定向。流式输出解析 SSE；后端不认识流式时按普通请求处理。
- **直接连接服务商**（`Direct.kt`、`Prompts.kt`、`DirectStore.kt`）：App 自己调用服务商的 Chat Completions 接口，不需要部署后端。
  - **提示词只有一份**：`server/ai.ts` 的 `buildRequest` 和 `finalize` 由 `npm run bundle:native` 打包成 `assets/sibian-ai.js`（入口是 `scripts/native-ai-entry.ts`），在 `androidx.javascriptengine` 的独立沙箱里执行。沙箱不是网页，没有网络和存储；Android 8 以下或 WebView 太旧时，说明原因，不换成别的来源。`tests/native-ai.test.ts` 检查打包文件没有过期，并在一个只有语言本身的环境里跑一遍，确认结果和后端一样。
  - **网络请求在 Kotlin 里**：`normalizeDirect`、`visibleText` 和 `DirectBackend.complete` 照 `server/ai.ts` 移植。标准答案里的 `direct-cases.json` 是网页版对一批地址、流式片段和服务商响应（成功、截断、空回复、401/403/404/429/500、重定向、流式报错……）跑出的结果，`DirectTest` 逐条比对显示过的文字和错误文案。
  - **API Key**：AGENTS.md 约定 1 的唯一例外。用 Android Keystore 里不能导出的 AES 密钥加密，存在 `noBackupFilesDir`，只用来设置请求头；不传进 JS，不进存档、导出、界面状态和错误信息（`DirectTest` 检查），设置页可以一键删除。Robolectric 没有 Android Keystore，`KeystoreDirectStore` 只能在真机上试，测试里用 `MemoryDirectStore`。
- **统一入口**（`Ai.kt`）：和网页版 `lib/ai.ts` 一样，同一时间只有一个请求，100 秒超时，可以取消。失败只给出原因，不换成演示模板、不编造回复（约定 2）。错误文案和网页版一致，只有“连不上”那句把浏览器才有的 `ALLOWED_ORIGINS` 换成了“手机能不能上网”。

网络读取是阻塞调用，协程的超时和取消管不到它，所以请求放在单独的线程里跑，超时或取消时直接断开连接。`AiTest` 里有计时断言，确认后端卡住时超时、取消会立刻生效。

## 学习流程

学习流程在 `flow/Learning.kt`，和网页版 `src/actions.ts` 的单人部分一一对应：准备、开始讨论、发言、提示、小结、快捷追问、术语、补充要点、“换我来讲”、知识画布的分支、辩论赛程、结束、复盘、复习、再讨论一次。界面只负责展示和调用这些操作。

- 状态是不可变的 `AppState`，每个操作通过 `update` 整体替换，和网页版的 `update(draft => …)` 一个思路。时间和 id 由外部传入，测试时可以固定。
- 发给 AI 的快照（`flow/Snapshot.kt`）决定了真实的 AI 能看到什么，必须和网页版一模一样。
- **剧本式标准答案**：`scripts/native-fixtures.ts` 用网页版在演示模式下跑五段剧本（追问的完整闭环、圆桌、知识画布的分支树、一整场辩论、各种边界情况），每一步之后记下规整后的状态、当前快照和复盘用的完整快照。`FlowTest` 按同样的剧本操作，逐步比对。id 和时间戳比较前换成占位符；时间相关的逻辑（间隔复习）另有单测。
- 剧本里的操作目标（比如“最后一条 AI 发言”）两边用同一套规则推出来。改了剧本，两边的执行器都要跟着改。

## 界面

界面在 `ui/`，照网页版现有的样子做（M4，分几个 PR）。

- `AppModel.kt` 是界面和流程之间的一层：持有状态，状态一变就在后台线程写存档，启动时读回。序列化也在后台线程，打字时只写最新的一份。整个 App 只有一份，转屏、切换深色模式时 Activity 重建，状态和正在进行的请求都不丢。
- 当前页面由 `state.ui.screen` 决定，和网页版一样；“讨论”“准备”“卡片”是沉浸页，不显示导航。返回键交给流程层的 `goBack()`。
- 通用部件在 `ui/Components.kt`，对应网页版 `styles.css` 的同名样式（`.primary`、`.chip`、`.mode`、`.banner`、`.dock`……）。
- 讨论页在 `ui/screens/DiscussScreen.kt`。讨论记录倒着排（`reverseLayout`），AI 逐字生成时自动贴着底部。逐字显示在 `ui/Reveal.kt`，生成中的气泡和完成后的正式发言用同一个 id 记进度，衔接时不会从头再写；关掉动效时直接显示全文。底部面板在 `ui/Sheet.kt`，宽屏时变成居中的对话框。
- 动效在 `ui/Motion.kt`，照网页版 `lib/motion.ts` 的“从哪里来，回哪里去”：换页由 `PageHost` 按 `navKind` 判断方向（往里走、返回、切标签、卡片放大和缩回，`MotionTest`），旧页面带着离开那一刻的状态退场；面板交给 `SheetHost` 托管，关上时带着原来的内容沉下去；错误提示、忙碌条、提示卡用 `Appear` 展开收起；讨论里新来的条目用 `RiseIn` 淡入上浮，翻回去看旧的不重播。`LocalMotion` 为 false（系统移除动画、截图测试）时全部直接切换。
- 知识画布的部件在 `ui/screens/CanvasParts.kt`，分支图的布局算法（`data/Canvas.kt` 的 `graph()`）和网页版 `graphOf` 一样，`FlowTest` 在画布剧本的每一步逐字比对；辩论台面、空讲台和双方阵容在 `ui/screens/DebateParts.kt`。
- 复盘、知识库、卡片、设置在 `ui/screens/ReviewScreen.kt`、`LibraryScreen.kt`、`SettingsScreen.kt`。导出交给系统的分享面板（`shareMarkdown`），导出内容由 `data/Export.kt` 生成、和网页版逐字一致。设置页的“连接并测试”在流程层的 `connectRemote`：先保存地址和口令，测试通过才切换到“我的后端”。
- 悬浮的部件在 `ui/Glass.kt`：
  - 手机竖屏的底部导航是悬浮的胶囊，页面铺满整屏，内容可以滚到它下面；键盘弹出时胶囊收起。
  - 底部操作（`WithFloatingDock`）和讨论页的输入栏也悬浮，不垫满宽的底色：
    - 主按钮带投影，次按钮、文字链接、工具和输入框用磨砂底（`glass`）。
    - 滚动内容的末尾留出它们量出来的高度。
  - 内容快滚到悬浮控件时用 `fadeBottom` 淡出：只改内容自己的透明度，背景氛围照样透出来，文字不会和按钮叠在一起。
  - Compose 不能模糊身后的内容，所以磨砂底几乎不透明。
  - 布局和交互参考 iOS 液态玻璃的规范（Apple HIG 的 Materials、Tab bars、Toolbars、Sheets、Buttons）：
    - 有文字的按钮是胶囊，只有图标的按钮是圆形磨砂（`GlassIconButton`）。
    - 顶栏不垫底色：返回和右边的操作悬浮。页面滚动后，内容在顶上淡出（`scrollWithEdge`）。讨论页的顶栏、要点进度、辩论台面、画布路径都浮在讨论记录上（`fadeUnder`）。
    - 面板（`Sheet`）是四边留空隙的悬浮卡片。内容多时先停在半高，往上拖到全高；往下拖或在内容顶端继续下滑就关掉。
    - 往下滚时底部胶囊收起成当前标签的小圆，往回滚或点它就展开。
- 截图测试里，AI 调用在后台线程、结果投递回主线程。Robolectric 下 `waitUntil` 不处理这些投递，要用 `ScreenshotTest` 里的 `awaitState`：每次让真实时间过去一点，再让界面空闲一次。

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

## 正式签名

原生版的正式包（`assembleRelease`）用原来套壳版的同一把签名（同样的环境变量 `ANDROID_KEYSTORE_FILE` 等）、同一个包名 `debating.ground.app`，所以手机会把它当成套壳版的更新。本地没配签名就构建未签名的 release 包。debug 包的包名带 `.dev`，可以和正式版同时装着。发版流程和签名密钥的管理见 [安卓 App 与自动发版](./android.md)。

## CI（`.github/workflows/native.yml`）

`native/`、`src/styles.css`、`src/components/ui.tsx` 有改动时运行：代码检查（ktlint、Android Lint），构建 debug 包，跑单元测试和截图测试，上传 debug 包、截图和检查报告（保留 14 天）。不签名、不发 Release；发版在 `android.yml`（见 [安卓 App 与自动发版](./android.md)）。
