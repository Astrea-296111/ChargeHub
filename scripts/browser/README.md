# 运营工作台浏览器验证

`operations-smoke.cjs` 使用真实 Chromium、真实 API 和现有 `ops`、`finance`、`admin`、`customer` 开发账号。只允许登录、退出及业务读取；遇到其他业务写请求会阻止并失败。它不启动服务，也不创建业务样本。先运行严格契约检查，以便从报告中选取一个已有设备会话的订单验证计量展示。

当前云环境实际使用 Node.js `24.19.0`、Playwright `1.57.0` 和 Chromium `154.0.8037.57`。脚本首先使用 Node 标准模块解析 `require('playwright')`，包括 `NODE_PATH`；当前云环境备用路径为 `/opt/codex/cua_node/lib/node_modules/playwright`。默认浏览器路径为 `/usr/bin/chromium`。

其他机器需要已有 Node.js、可执行的 Chromium 和 Playwright。可将同版本 Playwright 安装到 Git 忽略的本地目录，不改项目运行依赖：

```bash
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --prefix .local/browser-tools \
  --no-audit --no-fund --save-exact playwright@1.57.0
```

这是其他机器的依赖准备方式；本次云环境复用了已安装工具，没有执行上述安装。Chromium 仍需通过所在操作系统支持的安装方式提供，脚本不下载或安装浏览器。

在仓库根目录运行。将已生成的开发环境变量提供给进程，勿启用 shell 跟踪或打印凭证：

```bash
set -a
source .local/dev.env
set +a
python3 scripts/check-contracts.py --login --max-orders 100 \
  --report .local/reports/contracts-staff.json
NODE_PATH="$PWD/.local/browser-tools/node_modules" \
  CHARGEHUB_BROWSER_PATH=/usr/bin/chromium \
  node scripts/browser/operations-smoke.cjs
```

Python 契约工具的依赖见根目录 `requirements-dev.txt`。开发账号不存在或密码与既存账号不一致时应修复开发环境，脚本不会新增或重置账号。

| 环境变量 | 用途 |
| --- | --- |
| `CHARGEHUB_DEMO_PASSWORD` | 必需；已有四种角色开发账号的登录密码，不写入报告 |
| `CHARGEHUB_API_URL` | 默认 `http://127.0.0.1:8080` |
| `CHARGEHUB_BROWSER_PATH` | 默认 `/usr/bin/chromium` |
| `CHARGEHUB_UI_REPORT` | 默认 `.local/reports/operations-ui.json`；截图写入同目录 |
| `CHARGEHUB_UI_METER_ORDER` | 可选；已有设备会话的订单 UUID；否则读取 `.local/reports/contracts-staff.json` 中成功的计量复核检查 |
| `CHARGEHUB_UI_SOURCE_OVERLAY` | 默认关闭；设为 `1` 时仅以本地源码替换 `operations.js`，结果明确标记 `sourceOverlay=true` |

正式部署验收必须关闭源码覆盖，以验证镜像实际提供的静态资源。报告不会保存令牌、密码、请求头或响应正文；公开前仍需检查截图及实例数据。付款与设备仍是本地模拟渠道，浏览器和 HTTP 响应没有模拟替代。
