# 协作指南

给团队里的人类成员看。Agent 的行为规范在 [AGENTS.md](AGENTS.md)，请让你的 Agent 每次开工前先读它（Claude Code 会通过 `CLAUDE.md` 自动读取）。

## 分工

每个方向有一位负责人，负责拆 Issue、指挥自己的 Agent、审查相关 PR。

| 方向 | 主要目录 | 负责人 |
| --- | --- | --- |
| 界面与交互 | `src/screens/` `src/components/` `src/styles.css` | 待定 |
| 讨论流程与状态 | `src/actions.ts` `src/store.ts` `src/lib/` | 待定 |
| 提示词与 AI | `server/ai.ts` `src/lib/demo.ts` | 待定 |
| 服务端、打包与发布 | `server/server.ts` `.github/`，后续的 Capacitor | 待定 |

## 一个任务的流程

1. **开 Issue**：用模板写清楚要解决什么、做完的标准、不做什么。Issue 写得越具体，Agent 越不容易跑偏。放进项目看板的“待办”。
2. **认领**：把 Issue 指派给自己，移到“进行中”。同一时间一个 Issue 只交给一个 Agent。
3. **开分支**：`git switch -c feat/12-简短描述`。并行跑多个 Agent 时，每个用独立的 worktree：
   ```bash
   git worktree add ../debating-ground-12 -b feat/12-streaming
   ```
4. **让 Agent 干活**：把 Issue 内容交给它，要求它遵守 AGENTS.md，完成后跑 `npm run check && npm test && npm run build`。
5. **自己先看一遍**：读 diff，在手机宽度下点一遍，确认没有夹带无关改动。Agent 说“完成了”不等于完成了。
6. **开 PR**：填模板，关联 Issue。CI 必须通过。
7. **审查与合并**：至少一位其他成员审查。提示词和存储结构的改动请对应方向的负责人看。用 Squash merge 合并，删除分支。
8. **收尾**：Issue 自动关闭，看板移到“完成”。

## 节奏建议

- 每周一次 15 分钟同步：看看板，调整优先级，把大任务拆小。
- 一个 PR 控制在一两天内能审完的大小。大功能先拆成可以独立合并的几步。
- 每完成一个阶段（例如单人 MVP）打一个版本标签：`git tag v0.6.0 && git push --tags`，并写一条 Release 说明。

## 审查时重点看什么

- 有没有破坏 AGENTS.md 里“不能破坏的约定”，尤其是密钥处理和失败降级。
- 测试是真的在验证行为，还是只为了让测试通过。
- 界面在手机上能不能单手用，深色模式下看不看得清。
- 提示词改动是否让 AI 更好地帮助学习者理解概念，而不只是说得更漂亮。
