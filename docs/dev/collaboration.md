# 协作与 Agent 规范

思辨场由一个学生团队开发，大部分工作由成员指挥 AI Agent 完成。两份规范放在仓库根目录：

- [CONTRIBUTING.md](https://github.com/maxlen727/debating-ground/blob/main/CONTRIBUTING.md)：写给人类成员，包括分工、一个任务的流程、审查要点。
- [AGENTS.md](https://github.com/maxlen727/debating-ground/blob/main/AGENTS.md)：写给所有 Agent，包括仓库地图、必须运行的命令、不能破坏的约定、界面规范、Git 流程、什么时候停下来问人。Claude Code 通过 `CLAUDE.md` 自动读取它。

## 一句话版本

一个 Issue 对应一个分支、一个 Agent、一个 PR。人负责拆任务、写清楚 Issue、审查结果；Agent 在自己的分支上干活，跑通检查后开 PR，由另一位成员审查后 Squash 合并。

## 文档也是代码

本文档站在 `docs/` 目录，用 Markdown 写成。改了用户能感受到的行为，同一个 PR 里就要更新对应的文档页。每页底部都有“在 GitHub 上编辑此页”的链接。
