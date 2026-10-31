# 开发指南

## 环境

- Node.js 22.18 或更新版本（服务端由 Node 原生运行 TypeScript）。
- 推荐 VS Code 或任何支持 TypeScript 的编辑器。

## 常用命令

```bash
npm install          # 安装依赖
npm run server       # 本机服务 http://127.0.0.1:4173
npm run dev          # 界面热更新 http://127.0.0.1:5173（/api 自动转发给本机服务）
npm run check        # 类型检查
npm test             # 测试
npm run build        # 构建界面到 dist/
npm start            # 用构建好的 dist/ 启动
npm run docs:dev     # 文档站热更新
npm run docs:build   # 构建文档站
```

后端（Cloudflare Worker）在 `worker/` 目录：

```bash
cd worker
npm install
cp .dev.vars.example .dev.vars   # 填写本地测试用的密钥和口令，这个文件不会提交
npm run dev                      # http://127.0.0.1:8787，允许本机界面跨域
npx wrangler deploy --dry-run    # 只检查能否打包
```

Worker 使用的 `server/ai.ts` 和 `shared/` 是复制进 `worker/src/vendor/` 的副本（一键部署只会复制 `worker/` 目录）。**改了 `server/ai.ts` 或 `shared/` 之后运行 `npm run sync:worker`**，否则测试会失败。不要直接改 `vendor/` 里的文件。

开发界面时开两个终端，一个 `npm run server`，一个 `npm run dev`。

**提交前必须通过 `npm run check && npm test && npm run build`。**

## 项目结构

```
shared/        前端与服务端共用的类型（types.ts）、模式定义（modes.ts）和多人房间协议（room.ts）
server/
  ai.ts        提示词组装、调用 Chat Completions、校验模型输出（本机服务和 Worker 共用）
  server.ts    本机服务：托管界面、代理 /api、在内存中管理密钥
worker/        可自部署的后端（Cloudflare Worker），多人房间在 src/room.ts
src/
  store.ts     全局状态与本地持久化（localStorage: sibian-v3）
  actions.ts   学习流程的全部操作
  lib/         AI 调用入口、演示模板、赛制、间隔复习、导出
  screens/     学习、准备、讨论、复盘、知识库、设置
  components/  通用界面组件
  styles.css   样式与设计令牌
tests/         node:test 测试
docs/          本文档站（VitePress）
```

更多细节见[架构](./architecture)。

## 测试

测试使用 Node 内置的 `node:test`，直接运行 TypeScript：

- `tests/ai.test.ts`：提示词组装、要点标记解析、结构化输出校验、接口调用与超时。
- `tests/server.test.ts`：密钥只在内存中、会话隔离、拒绝外部来源、静态文件。
- `tests/worker.test.ts`：Worker 的访问口令、跨域限制、缺少配置时拒绝服务、不跟随重定向、房间路由。
- `tests/room.test.ts`：多人房间的加入、轮流发言、失败与重试、辩论席位、个人复盘、上限与过期删除。
- `tests/room-flow.test.ts`：房主用真实的 actions 创建房间、邀请、发言、小结、结束并生成自己的复盘。
- `tests/flow.test.ts`：在 Node 里直接驱动 actions，走完“准备 → 讨论 → 小结 → 复盘 → 知识卡片 → 复习”；API 失败、取消、辩论轮次。

改提示词时更新 `tests/ai.test.ts`，改流程时更新 `tests/flow.test.ts`。

## 部署

合并到 `main` 后，GitHub Actions 会自动构建并发布到 GitHub Pages：

- 文档站：`https://maxlen727.github.io/debating-ground/`
- 演示版界面：`https://maxlen727.github.io/debating-ground/app/`（默认演示模式；在设置里连接自己部署的后端后可以使用真实 AI）

工作流在 `.github/workflows/pages.yml`。

后端由 `.github/workflows/deploy-worker.yml` 部署：PR 上只检查能否打包；合并到 `main` 后，如果仓库设置了 `CLOUDFLARE_API_TOKEN` 和 `CLOUDFLARE_ACCOUNT_ID`，就部署到 Cloudflare。AI 密钥只设置在 Cloudflare 上，见[自己部署后端](../guide/self-host#团队维护的实例)。
