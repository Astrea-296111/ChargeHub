# v1 实际验证记录

日期：2026-10-18。范围：工作区源码、Java 21 / Spring Boot 4.1.1 / MySQL 8.4.7、独立 SQLite 模拟器，API 与两个 Worker。首次验收时原仓库无提交；本节保留该基线的实测证据，后续变更以 Git 历史与新增验收记录为准。源码文件指纹见 `evidence/source-files.sha256`。记录的是本次开发环境实测，不代表已发布、真实支付接入或生产运行。

## 实际结果

| 检查 | 已观察到的结果 | 证据 |
| --- | --- | --- |
| 完整 Maven 构建及真实 MySQL 测试 | 68 项 Java 测试，0 失败、0 错误、0 跳过 | `evidence/java-tests.json`，各模块 JUnit XML |
| 独立模拟器 | 7 项测试通过，含独立持久事实、业务幂等、重启恢复、签名与退款保护 | `evidence/simulator-tests.txt` |
| 8 组金额向量 | 全部复算一致；逐分段 HALF_UP、会员上限、优惠券门槛、零电量和 SHORTFALL | BillingCalculatorTest |
| 真正的 MySQL 迁移 | V1/V2 空库迁移成功，重复迁移执行数 0，连接时区 UTC | WorkflowMysqlTest / 本次启动日志 |
| 券发行并发 | 1000 个不同用户竞争 100 张券，成功 100，无超发；同用户并发只领 1 张 | BenefitConcurrencyTest |
| 交易与资源 | 用户/枪资源竞争、失败回滚、请求幂等及摘要冲突、取消晚到付款与截止边界 | WorkflowMysqlTest |
| 真实计量及资金 | 缺序列不出账，乱序补齐后应付 2100；重复资金通知没有二次流水；退款额度正确转移 | WorkflowMysqlTest |
| 对账并发 | 12 个并发同批次导入保持一个批次，双向差异、哈希冲突和审计可核对 | ReconciliationMysqlTest |
| 正常完整交易 | 预付10000分、电费1600、服务费1000、会员优惠200、券优惠300，应付2100、退款7900，财务CLOSED | `evidence/demo.json` |
| HTTP 验收 | 19 项具名检查全部通过，含权限、幂等、占用、20次重复通知、取消、普通用户2600/7400结算 | `evidence/acceptance.json` |
| 故障恢复 | 下列4个实际场景全部通过，两个Worker运行，所有订单独立核对渠道与设备事实 | `evidence/faults.json` |
| 运行契约 | 13 次响应校验通过，含完整订单、资金汇总、账单明细；业务写入0 | `evidence/contracts.json` |
| 浏览器 | 登录、选枪报价、创建、取消4个流程通过；无页面JS错误及桌面溢出 | `evidence/ui-smoke.json` / `evidence/console.png` |
| 数据不变量 | 资金、权益、绑定和关系SQL均返回零违规；检查器遇违规会非零退出 | `make test-invariants` |
| 备份恢复 | MySQL备份恢复至新隔离库，14张核心表行数一致，恢复后不变量零违规 | `evidence/backup-restore.json` |
| 运行恢复 | 云环境恢复后重新启动现有API、两个Worker和依赖，持久事实保留，后续故障检查通过 | 本次运行日志 |

故障场景分别是：支付通知丢失后主动查单恢复；设备已运行但启动响应与通知丢失后查询恢复；退款已成功但响应与通知丢失后按原退款号恢复；跨费率边界采样通知丢失后从设备原始事实补传。第四个场景实际观察到缺口期间 REVIEW 且账单接口返回409，补齐后才正式出账。没有按时长均摊真实电量。

正常演示在早先运行中已完成。最终 `make demo` 以 `reusedExisting: true` 重新核验该真实样本的账单、资金、独立渠道与设备序列，不再消费会员次数和已用券。普通用户交易与4个故障场景分别产生新的独立订单。

## 复现

```bash
make up
make integration-test
make demo
make acceptance
make faults
make test-invariants
make backup-check
set -a; source .local/dev.env; set +a
python3 scripts/check-contracts.py --login
```

契约校验需要 `requirements-dev.txt` 中锁定的可选 Python 依赖。集成测试新建独立 `_test` 数据库，并检查库名与空库前置条件；不会删除正常演示数据。`make test` 未提供测试库环境变量时会明确跳过数据库集成类；上述68项全通过结果来自 `make integration-test` 的完整运行。

初轮真实集成验证发现并修正了幂等响应序列化不一致和对账并发插入死锁。最终记录保留通过的完整重跑结果；没有将失败或跳过当作通过。

## 未完成或未验证

- A01–A47 全量故障矩阵没有全部执行，不能把本记录等同于47项全通过。
- 50RPS持续15分钟、100会话持续30分钟、百万历史订单索引和P95目标尚未实测。
- RocketMQ、MQ Ack前后杀进程、完整死信故障矩阵尚未接入或执行；当前使用数据库Outbox。
- 尚未验证升级旧版数据库、切换旧应用镜像回滚，以及模拟器卷的备份恢复。此次恢复仅覆盖MySQL快照。
- Redis已预留，当前查询直接读取MySQL；没有宣称完成缓存策略或Redis压测。
- 真实支付/OCPP、真实试用反馈和企业交付经历不在此版本成果中。
- 云端CI配置已提供，GitHub Actions尚未因本次工作推送触发；新任务快照恢复与环境发布未由本次操作验证。
