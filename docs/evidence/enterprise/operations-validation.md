# 运营工作台与严格响应契约验证

最终部署于 **2026-10-19 17:37 UTC** 完成真实 HTTP 和四角色 Chromium 154.0.8037.57 复验。API及两个Worker的实际镜像均为 `sha256:f02d5d40f034582d85637cc0c9ff1aa353b09489c0f4cdbd1dcdd1b2446cc714`，OCI revision为 `9d61589bf96ab8cd0cd12b6de3fe3a936c6092a1`，容器状态均healthy。支付与设备渠道为本地模拟器；浏览器操作和服务端响应均为实际执行结果。验证未创建订单、对账差异或其他业务记录，**业务写入为0**，仅登录、退出及读取既存记录。

- [最终部署的浏览器结果](operations-ui-deployed-9d61589.json)：实际执行开始时间 `2026-10-19T17:37:26.245Z`，`sourceOverlay=false`，OPS、FINANCE、ADMIN、CUSTOMER 四种身份全部通过。浏览器页面和控制台错误均为 0。覆盖工作人员权限区块、空订单列表、既存订单分页、计量证据、对账空列表、Outbox 已完成任务禁用重放、退出后令牌失效和普通用户自动撤销登录。
- [最终部署的截图](operations-admin-deployed-9d61589.png)：管理员读取既存数据的完整页面；不含密码、令牌或请求头。
- [最终严格响应契约结果](contracts-staff.json)：实际执行开始时间 `2026-10-19T17:37:04.942416+00:00`，34/34 检查通过，业务写入为 0。工作人员订单分页、计量复核、站点/枪/费率、券模板、会员记录、用户管理、审计和 Outbox 只读接口均使用严格响应 schema。报告保存实际部署镜像、revision及验证的 OpenAPI 文件 SHA256。
- 主库没有既存对账差异，财务差异详情及批次详情的契约检查明确跳过；未将空列表覆盖计为详情接口覆盖。

最终实际部署的 `operations.js` 金额六断言全部通过：`null`、`undefined`、空串均显示 `—`；`0` 显示 `¥0.00`；`123` 显示 `¥1.23`；`-123` 显示 `¥-1.23`。浏览器没有覆盖静态资源，另读取部署文件确认其SHA256为 `7e139b1921eb53a2885dc2e411d519919a5af8d494a82a64ef0c0151c851c7d5`，与最终源码一致。

早期a212部署发现缺失金额误显示零，历史 [浏览器结果](operations-ui-deployed-a212.json)、[截图](operations-admin-deployed-a212.png) 和 [严格契约报告](contracts-staff-a212.json) 保留原候选信息。随后 [源码覆盖回归](operations-ui-source-overlay.json) 使用 `sourceOverlay=true` 提前验证修复；该历史报告不作为最终部署证据，已由上述无覆盖实测补齐。

复现时先按仓库开发环境说明启动 API，并在进程环境中提供已有的 `CHARGEHUB_DEMO_PASSWORD`，不要把密码放进命令行或证据文件：

```bash
python3 scripts/check-openapi.py
python3 scripts/check-contracts.py --login --max-orders 100 \
  --report .local/reports/contracts-staff.json
node scripts/browser/operations-smoke.cjs
```

浏览器依赖路径、版本、可选安装方式与全部配置见 [脚本复现说明](../../../scripts/browser/README.md)。仅验证待部署静态源码时可设置 `CHARGEHUB_UI_SOURCE_OVERLAY=1`；正式部署验收必须不设置此变量。脚本拦截并阻止除登录/退出以外的非读取业务请求，报告只记录路径、状态和断言，不保存密码、令牌或响应正文。
