# 架构

## 当前形态（v0.5）

```
浏览器（React 界面）
   │  /api/*（只接受本机来源）
   ▼
本机服务 server/server.ts（127.0.0.1:4173，密钥在内存里）
   │  Chat Completions（HTTPS）
   ▼
用户选择的 AI 服务商
```

演示模式不经过本机服务，回复来自 `src/lib/demo.ts`。

## 一次 AI 调用

1. 界面调用 `actions.ts` 里的操作，例如 `send()`。
2. `actions.ts` 把当前讨论整理成快照（`snapshot()`，类型见 `shared/types.ts` 的 `SessionSnapshot`），交给 `lib/ai.ts` 的 `runAI(purpose, payload)`。
3. 演示模式下，`runAI` 调用 `lib/demo.ts`；API 模式下，请求本机服务的 `/api/generate`。
4. 本机服务用 `buildRequest(purpose, payload)` 组装提示词，调用服务商，再用 `finalize()` 整理输出：
   - `text`：原样返回文本（提示、讲解）。
   - `cover`：解析并去掉末尾的要点标记 `[[要点:1,3]]`，返回文本和讲清楚的要点编号（讨论、辩论、小结反馈）。
   - `prepare` / `review`：从回复中提取 JSON 并逐字段校验（讨论准备、理解复盘）。
5. 结果回到 `actions.ts`，通过 `update()` 写入状态；失败时只显示错误，不改变讨论进度。

## AI 用途

| 用途 | 做什么 | 输出 |
| --- | --- | --- |
| `prepare` | 拆要点、出讨论题、设定角色和开场白 | JSON |
| `discuss` | 追问、圆桌、反向讲解里某个角色的一次发言 | 文本 + 要点 |
| `debate` | 辩论中某个 AI 席位的一次发言 | 文本 + 要点 |
| `hint` | 卡住时的一条思路提示 | 文本 |
| `explain` | 对某句话：换个说法、举例、分辨事实与观点 | 文本 |
| `reflect` | 对学习者小结的反馈 | 文本 + 要点 |
| `review` | 理解复盘与检验题 | JSON |

## 状态与存储

- 全局状态在 `src/store.ts`：`config`（概念、模式、辩论席位与流程）、`plan`（准备好的讨论）、`session`（进行中的讨论）、`library`（知识卡片）、`ai`（连接状态）、`ui`（界面状态）。
- `update(draft => …)` 在副本上修改再整体替换，组件通过 `useApp()` 订阅。
- 持久化到 `localStorage` 的 `sibian-v3`。修改结构时升级版本号，并在 `load()` 里迁移旧数据。

## 规划中的形态

```
安卓 App（单人）──────────────▶ AI 服务商        密钥在 Android Keystore

网页 / 多人房间 ──▶ 自部署的 Cloudflare Worker ──▶ AI 服务商
                    （Durable Object = 一场讨论）
```

见[路线图](../product/roadmap)和[密钥与隐私](../guide/api-and-privacy)。
