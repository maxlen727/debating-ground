# AGENTS.md · 给在本仓库工作的 AI Agent

本文件是所有 Agent（Claude Code、Codex、Cursor、Copilot 等）在这个仓库里的行为规范。开始任何任务前先读完，再读一遍 [`LESSONS.md`](LESSONS.md)（错题本：以前犯过的错和做对的事，见“错题本和每日复盘”）。

**Agent 全权负责从实现到合并的全过程**：自己拆任务、开 Issue、写代码、自查、合并。人只定品味和方向（见“什么时候停下来问人”），可以事后看任何改动、随时叫停。和人类成员的指示冲突时，以人类成员的明确要求为准。

## 项目是什么

思辨场是一个学习应用：用户写下一个概念，和 AI 讨论、追问、辩论，把它真正弄懂；结束后生成理解复盘，整理进这个概念的笔记，越讨论越完整。**目标是学懂概念，不是练口才**，所有设计决定都服务于这一点。

产品有两种形态，都使用用户自己的 API Key：

- **网页版**：Web 界面加两种后端，本机 Node 服务，或用户自己部署的 Cloudflare Worker。
- **原生安卓 App**（`native/`，Kotlin + Compose）：演示模式、连接自己部署的后端，或直接连接 AI 服务商。

## 仓库地图

| 路径 | 内容 | 改动时注意 |
| --- | --- | --- |
| `shared/` | 前后端共用的类型（`types.ts`）、模式定义（`modes.ts`）和多人房间协议（`room.ts`） | 改了类型，前后端要一起改，并跑类型检查 |
| `server/ai.ts` | 提示词组装、Chat Completions 调用、输出解析与校验 | 提示词是产品的核心，改动必须更新 `tests/ai.test.ts` |
| `server/server.ts` | 本机服务：托管 `dist/`，代理 `/api/*`，会话与密钥管理 | 涉及安全，见下方“不能破坏的约定” |
| `server/tts.ts`、`server/stt.ts` | 讯飞朗读与语音听写（可选），Worker 和房间共用；`server/wsserver.ts` 是本机服务的最小 WebSocket 服务端 | 讯飞密钥同 API Key 一样只在后端 |
| `worker/` | 可自部署的后端（Cloudflare Worker），与本机服务提供同样的 `/api`，复用 `server/ai.ts`；多人房间（Durable Object）在 `src/room.ts` | 涉及安全；必须能单独部署（一键部署按钮只复制这个目录），不引用目录外的文件；`src/vendor/` 是生成的副本，不要手改 |
| `src/store.ts` | 全局状态与 localStorage 持久化 | 改存储结构要写迁移，不能让用户丢知识库 |
| `src/actions.ts` | 学习流程的全部操作 | 业务逻辑放这里，不要写进组件 |
| `src/lib/` | AI 调用入口、演示模板、赛制、概念笔记（`notes.ts`）、导出 | |
| `src/screens/`、`src/components/` | React 界面 | 只负责展示和调用 actions |
| `src/styles.css` | 全部样式与设计令牌 | 颜色只用令牌，见“界面规范” |
| `tests/` | `node:test` 测试 | |
| `docs/` | 产品文档站（VitePress），截图在 `docs/public/screenshots/` | 用户可见的行为变了，同一个 PR 里更新对应文档页 |
| `native/` | 安卓 App（Kotlin + Compose，路线图见 #70），替换了原来的网页套壳版（#104）；发版在 `.github/workflows/android.yml`，签名密钥只在 CI Secrets 里，永远不进仓库（见 `docs/dev/android.md`） | 颜色令牌、图标、导航规则和网页版一致，由 `TokensTest` 等测试把关，改网页版的 `styles.css` 或图标时也要跑原生版测试；存档检查和导出用网页版生成的标准答案逐字比对（`npm run fixtures:native`、`FixturesTest`）；新增依赖写进 `gradle/libs.versions.toml` 并在 PR 里说明；`app/src/main/assets/sibian-ai.js` 和 `sibian-room.js`（手机当局域网房主时跑的房间规则，#148）是 `npm run bundle:native` 生成的，不要手改；见 `docs/dev/native.md` |

## 命令

```bash
npm install          # 安装依赖。开发用 npm 11（package.json 的 packageManager，和 CI、dependabot 一致；npm 10 生成的锁文件和它不兼容，见 #83）
npm run check        # 类型检查（tsc）
npm run lint         # 代码检查（ESLint：没处理的 Promise、Hook 依赖、switch 漏分支等，只管对错不管格式）
npm run format       # 自动整理格式（Prettier）；npm run format:check 只检查
npm test             # 全部测试
npm run build        # 构建界面到 dist/
npm run server       # 本机服务 127.0.0.1:4173
npm run server:lan   # 同时打开局域网房间的入口（0.0.0.0:4174，只给界面和房间连接，见 server/lan.ts、#148）
npm run dev          # 界面热更新 127.0.0.1:5173（需同时运行 server）
npm run docs:dev     # 文档站热更新
npm run sync:worker  # 改了 server/ai.ts 或 shared/ 后，同步到 worker/src/vendor/
npm run fixtures:native # 改了存档结构、src/lib/restore.ts 或导出后，重新生成原生版的标准答案
npm run bundle:native   # 改了 server/ai.ts、shared/ 或 worker/src/room.ts 后，重新打包原生版“直接连接”用的提示词（assets/sibian-ai.js）和手机当局域网房主的房间规则（assets/sibian-room.js）
python3 scripts/atmosphere.py    # 改了背景氛围的参数（src/assets/atmosphere/atmosphere.json）或颜色令牌后，重新渲染背景图（网页版和原生版共用，需要 numpy 和 Pillow）
npm run eval:prompts # 提示词回归评测：用真实模型和模拟学习者聊几场（见脚本开头的说明）
cd native && ./gradlew ktlintCheck lintDebug assembleDebug testDebugUnitTest   # 原生安卓版：代码检查、构建、单元测试和截图测试（截图在 app/build/screenshots/）；./gradlew ktlintFormat 自动整理写法
cd worker && npm install && npm run dev   # 本地运行后端（先把 .dev.vars.example 复制为 .dev.vars）
```

**每次提交前必须通过 `npm run check && npm run lint && npm run format:check && npm test && npm run build`。** 没跑过就不要说“已完成”。如果某项失败且与你的改动无关，在 PR 里写明，不要顺手大改。

## 不能破坏的约定

1. **AI 服务商的 API Key 只存在后端：本机服务的内存里，或 Worker 的加密变量里。** 唯一的例外是原生安卓 App（`native/`）的“直接连接”：用户在 App 里填的 Key 用 Android Keystore 加密，存在不进系统备份的 `noBackupFilesDir`；它只用来设置发给服务商的请求头，不传进 JS 引擎，不进存档、导出、日志、界面状态和错误信息，用户可以一键删除。网页版不提供直接连接。浏览器里不保存它；不写入磁盘、localStorage、日志、导出文件、测试快照或错误信息。不要为了调试打印请求头或请求体。Worker 必须在没有设置 `ACCESS_PASSWORD` 时拒绝服务，只对 `ALLOWED_ORIGINS` 返回跨域许可，并保持关闭请求日志。浏览器里只能保存后端地址、访问口令和多人房间的凭据（邀请码、成员凭据），而且它们不能进入讨论记录、知识库或导出文件。讯飞密钥（`XFYUN_*`）同样只在后端；讯飞是可选的，没配时朗读和语音输入不出现，其余功能不受影响。语音输入的录音只在内存里转发，不保存。
2. **AI 失败不能悄悄降级。** API 模式下请求失败、超时、截断或格式错误时，显示错误并保持讨论进度不变；不要自动换成演示模板，不要编造回复。流式输出中途失败时，撤回已经显示的半截文字，不把它写进讨论记录。流式显示的文字不能包含要点、术语标记和密钥（`visibleText`）。
3. **演示模式必须始终可用。** 没有网络和密钥时，“机会成本”能走完整个流程。新增 AI 用途时同时在 `src/lib/demo.ts` 补演示内容。
4. **复盘只依据学习者本人的发言和小结**，AI 角色说出来的内容不算学习者懂了。
5. **辩论模式下 AI 只做辩手**，不当教练；但不得为了立场歪曲概念或事实。追问解释走讲解助手，不进入比赛记录。
6. **服务端和 Worker 不加运行时依赖**，保持“Node 原生运行 TypeScript”。Workers 不支持 `redirect: 'error'`，Worker 里调用 `complete()` 要传 `redirect: 'manual'`。只用可擦除的类型语法（不用 enum、namespace、参数属性），导入写 `.ts` 后缀。
7. **结构化输出必须校验。** 模型返回的 JSON 一律经过 `finalize` 清洗和兜底，前端不信任未校验的字段。
8. **不改已发布的存储键的含义。** 改 `sibian-v4` 的结构时升级版本号，并在 `store.ts` 的 `load()` 里迁移旧数据。
9. **多人房间的状态以后端为准。** 浏览器只发意图，房间判断是否轮到这个人。同学凭房间邀请码加入，永远拿不到访问口令；房间只保存邀请码和凭据的哈希；人数、AI 调用次数、发言长度都有上限（`ROOM_LIMITS`）；房间结束 1 小时后或创建 4 小时后删除全部存储。个人请求（提示、讲解、小结、复盘）只回复提问的人，复盘只把这个人标为学习者。

## 代码规范

- TypeScript `strict`，不用 `any`；确实需要时用 `unknown` 再收窄。`JSON.parse` 的结果先当 `unknown`，或断言成字段全是可选、之后逐个检查的结构。
- 代码检查（`eslint.config.js`）报的问题要修，不要整条关掉规则。确属故意的写法，用 `// eslint-disable-next-line 规则名` 并在上一行写明原因。“发出去不等”的 Promise 写 `void`。
- 业务逻辑写在 `actions.ts` 或 `lib/`，组件里只做展示和事件绑定。状态修改一律通过 `update(draft => …)`。
- 格式交给 Prettier（`.prettierrc.json`），不手动对齐、不争论写法；写完跑一次 `npm run format`。ESLint 只管对错，两者不重叠。
- 小步修改，一个 PR 只做一件事。不要顺手重命名或重构与任务无关的代码。
- 不新增依赖，除非任务需要且在 PR 里说明理由；优先用平台能力。
- 注释和界面文案用中文，代码标识符用英文。注释写“为什么”，不复述代码。

## 界面规范（移动优先）

- 先按 390px 宽的手机设计，再往上适配：600px（折叠屏、小平板，内容放宽）、840px（平板和电脑：左侧导航栏、页面分栏、面板变成居中对话框）、横着拿的手机（高度 ≤ 500px：左侧导航栏，顶栏和底部按钮不常驻）。规则都在 `styles.css` 的“响应式布局”一节；新页面在这几档下都看一眼。任何宽度都不能出现横向滚动，最窄看 320px。
- 可点区域至少 44×44px；主要操作放在底部（`.dock` 或 `.composer`），单手可达。
- 控件层是悬浮的（参考 iOS 液态玻璃规范，见 #100）：
  - 底部导航是磨砂胶囊，往下滚时收起；顶栏、底部操作、讨论页的输入栏都不垫整条底色，内容可以滚到它们下面，滚到附近时变模糊、变淡。
  - 磨砂（`--glass-bg`）只用于导航和控件，卡片、气泡这些内容层不用。
  - 有文字的按钮是胶囊，只有图标的按钮是圆形。
  - 面板是四边留空隙的悬浮卡片，内容多时分半高、全高两档，下拖关闭。
  - 原生版用同样的规则（`ui/Glass.kt`）。
- 颜色只用 `styles.css` 里的令牌（`--brand`、`--ink`、`--muted` 等），浅色和深色都要看一遍，不写死颜色。
- 页面底下铺着一层背景氛围（渐隐的色块和淡噪点，极慢地漂，见 `styles.css` 的“背景氛围”一节，原生版是 `ui/Atmosphere.kt`；色块是 `scripts/atmosphere.py` 预渲染的图，两边共用，改了颜色令牌要重新生成，`tests/atmosphere.test.ts` 会提醒）。页面、栏目不要再铺满屏的纯色底，否则会把它盖住；卡片、气泡、面板这些承载内容的地方照常用 `--surface`、`--raised`，保证文字对比度。吸附条用半透明加小范围模糊。
- 字体分工：衬线（`--f-display`）只用于“知识本身”：概念名、讨论题、要点、学习者自己的小结、复盘结论、术语词条、自检题；对话气泡、按钮、标签、提示用无衬线（`--f-body`）。字号用 `--t-*` 阶梯，衬线正文行距用 `--lh-serif`。只用系统字体，不引入网络字体（App 要离线可用）。
- 新的多行输入框用 `CaretField` 包一层，和其他输入框一样有平滑光标。
- 文案站在用户一侧，说清楚会发生什么：按钮写动作（“开始讨论”），错误写原因和办法，不道歉、不含糊。不用 emoji 当图标。
- 尊重 `prefers-reduced-motion`；输入框字号不小于 16px（避免手机缩放）。
- 动效讲“从哪里来，回哪里去”：换页面、开关面板都经过 `update()` 交给 `lib/motion.ts`，不要绕开它直接改 DOM 或另写转场。跨页面是同一个东西的新元素，在 `styles.css` 的“连贯动效”一节登记 `view-transition-name`；只在某次导航里才连着的，用 `--vtn` 加 `data-nav` 限定。原生版同样的规则在 `ui/Motion.kt`（换页 `PageHost`、面板 `SheetHost`）。
- 改了界面，在手机宽度下截图检查，并把截图附在 PR 里。

## 改提示词时

提示词在 `server/ai.ts`。修改前想清楚它服务哪个学习目标；修改后：

- 更新或新增 `tests/ai.test.ts` 里对应的断言（结构、关键约束、输出格式）。
- 在 PR 里写出改动前后的差别，以及你用什么概念、什么模式试过（附一两段真实输出）。
- 不要删除“不编造来源”“区分事实与观点”“要点标记”这些通用约束。
- 讨论类提示词的方向是“一起想，不是拷问”（见 `docs/product/principles.md`）：不要恢复“每轮最后提一个问题”“逐句指出事实和观点”这类规则；说话要有活人感（`VOICE_RULE`），不要加回固定字数区间。改动前后各跑一次 `npm run eval:prompts`（需要真实模型），把汇总表贴在 PR 里。

## Git 与协作流程

- **不直接推送 `main`，不 force push 共享分支，不改写别人的提交。**
- 每个任务对应一个 Issue。分支名：`<类型>/<issue号>-<简短描述>`，例如 `feat/12-streaming`、`fix/18-reflect-card`。类型：`feat` `fix` `refactor` `docs` `test` `chore` `style`。
- 提交信息：`<类型>: <做了什么>`，中文即可，正文写“为什么”。Agent 生成的提交在末尾加上 `Co-Authored-By:` 标明是哪个 Agent，作者用负责这个任务的人类成员。改动来自人类成员的反馈时，在正文里原样引用那句话（`负责人：“……”`），每日复盘靠它找到返工和被打回的改动。
- 开 PR 时填写模板：关联 Issue、改了什么、怎么验证的、截图、风险。PR 描述是写给事后翻看的人的记录，要如实。
- **PR 由 Agent 自查后合并（Squash merge），不等人审查。** 合并前：CI 全部通过；没有冲突；对照“完成一个任务的标准”逐项确认；从头到尾复读一遍 diff，确认没有夹带无关改动；PR 上有审查意见的，先处理完。合并后删除分支，在 Issue 里勾掉做完的事。
- 多个 Agent 并行时，各自在独立分支（推荐 `git worktree`）上工作；开始前 `git fetch` 并基于最新的 `main`。发现别人正在改同一个文件，先在 Issue 里沟通。
- 不提交 `node_modules/`、`dist/`、`.env`、密钥、个人配置或截图以外的大文件。

## 错题本和每日复盘

[`LESSONS.md`](LESSONS.md) 是 Agent 自己的错题本，格式和记法写在它开头。

- **每天复盘一次**：一个定时任务每天开一个新会话，把过去 24 小时合进 `main` 的提交和对应 PR 过一遍，只找三类事：返工的、不该合的、验证太糙的。找到就记进 `LESSONS.md`（已有的教训加上日期），开 PR、按常规流程合并。没有新教训就什么都不提交。
- **事不过三**：同一条教训在 3 个不同的日子出现，就挪进本文件对应的章节，变成硬规则，错题本里留一行指向它。
- 做对了、值得照着来的事也记，免得只学会“别这样”。
- 复盘只能看到仓库里留下的东西，所以提交正文要引用负责人的原话（见“Git 与协作流程”），PR 要如实写验证。

## 什么时候停下来问人

只在两类事情上问，其余自己判断、自己做完，把理由写进 PR：

- **品味**：界面的观感和手感、文案的语气、动效的分寸，这些没有对错、取决于偏好的选择。有现成的规范或先例可循时照着做，不用问。
- **方向**：做什么、不做什么，产品的学习目标，讨论模式和学习流程的设计原则，提示词的学习目标（措辞和细节的调整不算，照常改并跑评测）。

另外，**不可逆、直接落到用户身上的操作**先说一声再做：发正式版（推 `v*` 标签）、改签名密钥或发布用的 Secrets、任何可能让用户设备上的数据丢失的发布。

以下情况**不需要**问，自己处理并在 PR 里写明：
- 新增依赖、调整架构：先确认确实需要、优先用平台能力，理由写进 PR。
- 测试失败且原因与任务无关：查清根因；能修就另开 Issue 修掉，修不了就在 PR 里写明。
- 需求有几种理解：选最符合“把概念弄懂”这个目标、改动最小、最容易撤回的那种，在 PR 里写明为什么这样选。

## 完成一个任务的标准

1. 代码改完，`npm run check && npm run lint && npm run format:check && npm test && npm run build` 全部通过。
2. 新行为有测试覆盖（流程改动加到 `tests/flow.test.ts`，提示词改动加到 `tests/ai.test.ts`）。
3. 界面改动在手机宽度、浅色和深色下检查过。
4. 用户可见的行为变了，就更新 `README.md` 和 `docs/` 里对应的页面；界面明显变了，重新截图。
5. PR 描述如实写明做了什么、没做什么、有什么不确定。**没跑的验证单独列出来，写明缺什么（真机、真实模型、某个服务商的账号……），不省略**；更新说明里写成“能用”的功能，必须真的验证过，没验证的照样写明。
6. 修的是人类成员指出的问题：先在对方指出的那个页面、那个状态下原样复现，修完对着同一个画面比；改的是共用的组件或效果，每个用到它的地方都看一遍。
7. 修 bug 尽量带一个回归测试：把旧代码放回去，这个测试要真的失败。做不到的（纯视觉、只能真机复现的）在 PR 里说明。
