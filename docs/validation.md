# v1 当前验证记录

日期：2026-10-19。此处记录本次模拟环境的真实运行，包含 Java21、Spring Boot4.1.1、MySQL8.4.7、Redis7.4、RocketMQ5.5.0/原生客户端5.2.2，以及各自保存 SQLite 事实的独立支付和设备模拟器。历史基线保存在 [原始验证记录](evidence/baseline-validation.md)。

业务 Java、SQL迁移和模块依赖固定于 `302507165a4496ded25ac6ff0505a93869bec47c`；容器健康探针与运营登出修复的候选为 `a212a8b4ffb91444c1170478995786c1f2780d02`，镜像 `sha256:52bdaff20c1a57f6c0e518c5592779d64a31b202cf80d8a9ff36932dddc082a2`。后续文档、验收工具和静态页面修复会分别记录，不把不同来源的证据混称同一镜像执行。

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| Java完整回归 | 26类128项通过，0失败/错误/跳过；其中真实依赖9类38项，其他90项为单元/领域测试 | [Java报告](evidence/enterprise/java-tests.json) |
| 真实持久依赖 | 每域9个全新隔离MySQL库、V1–V8迁移、独立Redis；并发、租约、回滚、权益、计量、财务及HTTP测试执行完成 | [依赖报告](evidence/enterprise/mysql-integration.json) |
| 独立模拟器 | 21项通过；原始事实、退款重试、设备拒绝和未知结果保护 | [Python报告](evidence/enterprise/python-tests.json) |
| 正常金额样本 | 10000分预付，1600电费+1000服务费−200会员−300券=2100应付，退款7900，财务CLOSED | [金额演示](evidence/enterprise/demo-db.json) |
| 数据库模式HTTP与故障 | 19项HTTP验收、4类丢通知/响应/边界采样恢复通过；资金与权益SQL零违规 | [HTTP](evidence/enterprise/acceptance-db.json)、[故障](evidence/enterprise/faults-db.json) |
| 严格接口契约 | 正式OpenAPI语义57路径/64操作/156schema；真实读取34响应全部通过，缺少既存财务明细的覆盖明确跳过 | [契约报告](evidence/enterprise/contracts-staff.json) |
| 浏览器运营台 | 已部署a212四角色验证通过，JS错误0；缺失金额显示修复另有源码覆盖报告，最终部署待复验 | [浏览器说明](evidence/enterprise/operations-validation.md) |
| Redis实际服务故障 | 清空、真实停机、重启和回填通过；故障期间HTTP回源0.1809秒 | [Redis报告](evidence/enterprise/redis.json) |
| 完整回滚与恢复 | 旧版空库→新版V1–V8及真实模拟交易→旧镜像读升级数据→MySQL新库和双SQLite新卷恢复；11表逐行摘要一致，零违规 | [回滚报告](evidence/enterprise/rollback.json) |
| RocketMQ进程故障 | A40、A41两个窗口及A42物理死信/原事件审计重放全部通过；真实SIGKILL、重投ACK和资金单笔证据齐全 | [MQ报告](evidence/enterprise/mq.json) |
| 监控采集 | Promtool配置/12条规则已通过；完整实时采集待完成 | [工程说明](engineering.md) |
| A47持续负载 | 尚未完成；4轮准备失败均没有业务负载或百万行seed，不能作为吞吐结果 | [工程说明](engineering.md) |
| GitHub CI | 已实际触发；a212的4类门禁成功，严格schema与SQLite恢复工具的两项失败已定位并修复，待新提交完整重跑 | [a212工作流](https://github.com/Astrea-296111/ChargeHub/actions/runs/39242486619) |

## 证据边界与故障修复

实际缺陷包括：并发开发初始化锁升级死锁、运营停用与建单竞争、退款旧attempt延迟失败、计量补传长处理租约、健康探针目录权限、Compose时间/挂载字符串类型，以及SQLite备份WAL关闭问题。均保留失败诊断与对应回归，不能把脚本存在或容器创建成功当作功能通过。

本云环境Docker使用vfs和32GB磁盘，多栈同时准备及监控镜像解压曾耗尽空间，Broker明确拒写后按原事件自动重试恢复；未降低磁盘阈值或重写资金事实。后续重栈顺序运行，持续负载独占窗口会记录实际宿主/cgroup容量与容器限制，目标数字不当作实测数字。

## 复现

```bash
make up
make integration-test
make demo acceptance faults test-invariants
python3 -m pip install -r requirements-dev.txt
python3 scripts/check-openapi.py
set -a; source .local/dev.env; set +a
python3 scripts/check-contracts.py --login
```

完整矩阵见 [A01–A47](acceptance-matrix.md)，运行/恢复步骤见 [运行手册](operations.md) 与 [工程验收](engineering.md)。未设置数据库环境变量的普通单测会跳过依赖测试；128项全执行来源是完整隔离集成入口。

真实支付/OCPP、外部人员试用反馈和企业生产交付不属于本次模拟成果。环境安装/启动配置已保存为草稿；发布快照和新任务恢复未由保存动作自动完成。
