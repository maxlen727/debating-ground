# 自己部署后端

在线版的思辨场默认只能用演示模式。部署一个属于你自己的后端，就能在网页上和真实 AI 讨论。后端运行在你自己的 Cloudflare 账号里，免费额度就够个人和小组使用。

## 它做什么，不做什么

- 后端替你保管 AI 服务商的 API Key，并把讨论请求转发给服务商。**浏览器里不需要密钥**，只需要后端地址和一个访问口令。
- 访问口令用来防止别人用你的额度。没有设置口令时，后端会拒绝所有请求。
- 后端只接受你指定网站的跨域请求。
- 单人讨论时，后端不保存任何讨论内容。[和同学一起讨论](./multiplayer)时，发言记录临时保存在你的后端里，房间结束 1 小时后（最长 4 小时）自动删除。后端关闭了请求日志。代码就在仓库的 `worker/` 目录里，可以自己检查。

## 你需要准备

- 一个 [Cloudflare](https://dash.cloudflare.com/sign-up) 账号（免费）。
- 一个 [GitHub](https://github.com/signup) 账号（免费）。一键部署会把后端代码复制一份到你的 GitHub，Cloudflare 从那里构建。
- 一个 AI 服务商的 API Key，以及它的 Base URL 和模型 ID。**建议专门新建一个 Key 并设置消费上限**，见[密钥与隐私](./api-and-privacy#不管用哪种方式给密钥设上限)。

## 一键部署

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/maxlen727/debating-ground/tree/main/worker)

1. 点上面的按钮，登录 Cloudflare。
2. 连接你的 GitHub 账号，给即将创建的仓库起个名字。默认是私有仓库就可以。
3. 按提示填写这几项。前四项会存成加密变量，填完后连你自己也看不到原文：
   - **API_KEY**：AI 服务商的 API Key。
   - **API_BASE_URL**：服务商的 Base URL，例如 `https://api.example.com/v1`。
   - **MODEL**：模型 ID。
   - **ACCESS_PASSWORD**：自己设一个访问口令，越长越好。
   - **ALLOWED_ORIGINS**：用在线版思辨场就保持 `https://maxlen727.github.io` 不变。
4. 点 **创建并部署**，等一两分钟。完成后页面上会显示后端地址，类似 `https://debating-ground-api.你的子域.workers.dev`。

之后想换模型或换 Key，在 Cloudflare 控制台里改：**Workers 和 Pages → 你的后端 → 设置 → 变量和机密**。

::: tip 怎么更新后端
一键部署复制的是当时的代码，不会自动跟着思辨场更新。需要更新时，把思辨场仓库 `worker/` 目录里的新代码合并进你自己的仓库，Cloudflare 会自动重新部署。也可以删掉旧的后端，再点一次按钮。

v0.7 起后端支持[和同学一起讨论](./multiplayer)。之前部署的后端需要更新一次代码；用命令行部署的，在 `worker` 目录重新运行 `npx wrangler deploy` 即可。加密变量不受影响。
:::

## 用命令行部署

不想连接 GitHub，也可以在自己电脑上部署。需要安装 [Node.js](https://nodejs.org/) 22.18 或更新版本。

1. 下载或克隆[思辨场仓库](https://github.com/maxlen727/debating-ground)，进入 `worker` 目录并安装部署工具：
   ```bash
   cd worker
   npm install
   ```
2. 登录 Cloudflare（会打开浏览器授权）：
   ```bash
   npx wrangler login
   ```
3. 如果你的界面不在 `https://maxlen727.github.io`，打开 `worker/wrangler.toml`，把 `ALLOWED_ORIGINS` 改成你打开思辨场的网站地址。多个地址用逗号分隔。
4. 部署：
   ```bash
   npx wrangler deploy
   ```
   完成后会显示后端地址。
5. 设置四个加密变量。每条命令都会提示你输入值，输入的内容不会显示，也不会保存在电脑上：
   ```bash
   npx wrangler secret put API_KEY          # AI 服务商的 API Key
   npx wrangler secret put API_BASE_URL     # 服务商的 Base URL，例如 https://api.example.com/v1
   npx wrangler secret put MODEL            # 模型 ID
   npx wrangler secret put ACCESS_PASSWORD  # 自己设一个访问口令，越长越好
   ```
   也可以在 Cloudflare 控制台里设置：**Workers 和 Pages → debating-ground-api → 设置 → 变量和机密**，类型选“机密”。

## 在思辨场里连接

1. 打开思辨场，点底部的 **设置**。
2. AI 来源选 **我的后端**。
3. 填写后端地址和访问口令，点 **连接并测试**。看到“已连接，测试通过”就可以开始了。

后端地址和访问口令保存在这台设备的浏览器里，不会写进讨论记录、知识库或导出文件。换一台设备时需要重新填写。

## 常见问题

**提示“访问口令不正确”**：检查口令是否和 `ACCESS_PASSWORD` 一致。改过口令后，要在思辨场里重新填写。

**提示“连不上你的后端”**：检查地址是否正确；确认已经部署成功（一键部署可以在 Cloudflare 控制台的“部署”里看构建日志）；确认 `ALLOWED_ORIGINS` 包含你当前打开思辨场的网站地址（包括 `https://`，结尾不带 `/`）。

**提示“后端还没有配置好 AI 接口”**：`API_KEY`、`API_BASE_URL`、`MODEL` 有缺失或格式不对。地址必须是 HTTPS。

**怎么换模型或换 Key**：在 Cloudflare 控制台的“变量和机密”里修改，或重新运行对应的 `wrangler secret put` 命令，不需要重新部署。

**会花多少钱**：Cloudflare Workers 的免费额度是每天 10 万次请求，一次讨论通常只用几十次。AI 服务商按各自的价格计费。

## 团队维护的实例

如果你们团队要维护一个公共后端，可以用仓库里的 GitHub Actions 自动部署：在仓库的 **Settings → Secrets and variables → Actions** 里添加 `CLOUDFLARE_API_TOKEN`（只授予“编辑 Cloudflare Workers”权限）和 `CLOUDFLARE_ACCOUNT_ID`。之后每次合并到 `main`，后端会自动更新到和仓库一致的代码。AI 服务商的密钥仍然只设置在 Cloudflare 上，部署代码不会覆盖它们。
