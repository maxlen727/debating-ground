# 架构

## 当前形态（v0.7）

```
浏览器（React 界面）
   │
   ├─ 本机服务 server/server.ts ──────────────▶ AI 服务商
   │   （127.0.0.1，同源，密钥在内存里）
   │
   └─ 自己部署的后端 worker/src/index.ts ─────▶ AI 服务商
       （Cloudflare Worker，跨域 + 访问口令，密钥在加密变量里）
          │
          └─ 多人房间 worker/src/room.ts（Durable Object，一场讨论一个）
               ▲ WebSocket，凭房间邀请码加入
          同学的浏览器
```

两种后端提供同样的 `/api/config`、`/api/test`、`/api/generate`，都复用 `server/ai.ts` 组装提示词和校验输出。演示模式不经过后端，回复来自 `src/lib/demo.ts`。

Worker 与本机服务的差别：

- 密钥来自 Cloudflare 加密变量（`API_KEY`、`API_BASE_URL`、`MODEL`），不能从界面修改；`POST /api/config` 不存在。
- 每个 `/api/*` 请求都要带 `Authorization: Bearer <ACCESS_PASSWORD>`；没设口令时一律拒绝。
- 只对 `ALLOWED_ORIGINS` 里的网站返回跨域许可。
- Workers 不支持 `redirect: 'error'`，`complete()` 在 Worker 里用 `manual`，并把 3xx 当作失败。
- `worker/` 必须能单独部署：Deploy to Cloudflare 按钮只复制这个子目录。所以 Worker 引用的是 `worker/src/vendor/` 里的副本，由 `npm run sync:worker` 从 `server/ai.ts` 和 `shared/` 原样生成；`tests/worker.test.ts` 会检查副本是否最新。

## 一次 AI 调用

1. 界面调用 `actions.ts` 里的操作，例如 `send()`。
2. `actions.ts` 把当前讨论整理成快照（`snapshot()`，类型见 `shared/types.ts` 的 `SessionSnapshot`），交给 `lib/ai.ts` 的 `runAI(purpose, payload)`。
3. 演示模式下，`runAI` 调用 `lib/demo.ts`；否则请求本机服务或自己部署的后端的 `/api/generate`（`lib/api.ts`）。
4. 后端用 `buildRequest(purpose, payload)` 组装提示词，调用服务商，再用 `finalize()` 整理输出：
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

## 多人房间

- **创建**：房主带访问口令请求 `POST /api/rooms`，提交准备好的讨论设置（`RoomSetup`，见 `shared/room.ts`）。后端重新校验设置（席位、赛制、长度），随机生成房间编号、邀请码和房主凭据；只保存邀请码和凭据的 SHA-256 哈希。
- **加入**：浏览器连接 `wss://…/api/rooms/<编号>/ws`（只接受 `ALLOWED_ORIGINS` 的网站），第一条消息带邀请码和名字（`hello`），或者带自己的成员凭据（`resume`，用于断线重连）。同学不需要访问口令。
- **状态以后端为准**：浏览器只发意图（`say`、`pass`、`callOn`、`aiTakeover`、`retry`、`start`、`finish`），房间判断是否轮到这个人，然后把完整状态（`RoomView`）广播给所有人。界面用 `src/lib/room.ts` 的 `toSession()` 把它映射成本地的 `Session`，所以讨论页、复盘和知识库沿用单人模式的代码。
- **AI 发言**在房间里进行，使用 `server/ai.ts` 的同一套提示词与校验。AI 看到的快照里，所有真人都标为“学习者”。
- **个人请求**（`ask`：提示、讲解、小结、复盘）只回复给提问的人。快照里只有这个人标为“学习者”，其他真人标为“同学”，所以复盘只依据他自己的发言和小结。小结只存在各自的设备上，复盘时一起提交。
- **上限与清理**：人数、AI 调用次数、每人个人请求次数、发言长度、记录条数都有上限（`ROOM_LIMITS`）。房间用 Durable Object 的 alarm 在结束 1 小时后或创建 4 小时后删除全部存储。
- **测试**：`RoomCore` 不依赖 Workers 运行时，`tests/room.test.ts` 直接驱动它；`tests/room-flow.test.ts` 让真实的 actions 通过假 WebSocket 连到 `RoomCore`。

## 规划中的形态

```
安卓 App（单人）──────────────▶ AI 服务商        密钥在 Android Keystore
```

见[路线图](../product/roadmap)和[密钥与隐私](../guide/api-and-privacy)。
