# 运营工作台与严格响应契约验证

2026-10-19 在本机 Docker 开发拓扑上使用 Chromium 154.0.8037.57、真实 HTTP 和独立角色会话验证。支付与设备渠道为本地模拟器；浏览器操作和服务端响应均为实际执行结果。验证未创建订单、对账差异或其他业务记录，仅登录、退出及读取既存记录。

- [部署候选的浏览器结果](operations-ui-deployed-a212.json)：候选 `a212a8b4ffb91444c1170478995786c1f2780d02`，`sourceOverlay=false`，OPS、FINANCE、ADMIN、CUSTOMER 四种身份全部通过。浏览器页面和控制台错误均为 0。覆盖工作人员权限区块、既存订单分页、计量证据、对账空列表、Outbox 已完成任务禁用重放、退出后令牌失效和普通用户自动撤销登录。
- [部署候选的截图](operations-admin-deployed-a212.png)：管理员读取既存数据的完整页面；不含密码、令牌或请求头。
- [严格响应契约结果](contracts-staff.json)：34/34 检查通过，业务写入为 0。新增工作人员订单分页、计量复核、站点/枪/费率、券模板、会员记录、用户管理、审计和 Outbox 只读接口均使用严格响应 schema。报告保存实际验证的 OpenAPI 文件 SHA256。
- 主库没有既存对账差异，财务差异详情及批次详情的契约检查明确跳过；未将空列表覆盖计为详情接口覆盖。

部署候选的金额格式化探针发现缺失金额与数值零均显示 `¥0.00`。已将源码修为：`null`、`undefined`、空串显示 `—`，数值 `0` 显示 `¥0.00`。后续 [源码覆盖回归](operations-ui-source-overlay.json) 仅在浏览器中替换精确的 `operations.js` 响应，业务 HTTP 仍请求同一已部署服务。该报告的 `sourceOverlay=true` 不代表静态修复已部署；最终镜像部署后需再次执行无覆盖模式。

复现时先按仓库开发环境说明启动 API，并在进程环境中提供已有的 `CHARGEHUB_DEMO_PASSWORD`，不要把密码放进命令行或证据文件：

```bash
python3 scripts/check-openapi.py
python3 scripts/check-contracts.py --login --max-orders 100 \
  --report .local/reports/contracts-staff.json
node scripts/browser/operations-smoke.cjs
```

浏览器依赖路径、版本、可选安装方式与全部配置见 [脚本复现说明](../../../scripts/browser/README.md)。仅验证待部署静态源码时可设置 `CHARGEHUB_UI_SOURCE_OVERLAY=1`；正式部署验收必须不设置此变量。脚本拦截并阻止除登录/退出以外的非读取业务请求，报告只记录路径、状态和断言，不保存密码、令牌或响应正文。
