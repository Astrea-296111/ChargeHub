# v1 当前验证记录

日期：2026-10-19 UTC。此处记录本次模拟环境的真实运行，包含 Java21、Spring Boot4.1.1、MySQL8.4.7、Redis7.4、RocketMQ5.5.0/原生客户端5.2.2，以及各自保存 SQLite 事实的独立支付和设备模拟器。历史基线保存在 [原始验证记录](evidence/baseline-validation.md)。

最终应用静态候选为 `9d61589bf96ab8cd0cd12b6de3fe3a936c6092a1`，镜像 `sha256:f02d5d40f034582d85637cc0c9ff1aa353b09489c0f4cdbd1dcdd1b2446cc714`，持续压测仍固定此候选。业务 Java、SQL迁移和模块依赖仍与 `302507165a4496ded25ac6ff0505a93869bec47c` 的已验证指纹一致。后续 `aaa2c26` 仅更新工具、文档及监控门禁。已有 a212 镜像的运行证据保留原候选标识；本机最终镜像的无覆盖四角色浏览器、34项严格契约及完整监控复验均已通过。

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| Java完整回归 | 26类128项通过，0失败/错误/跳过；其中真实依赖9类38项，其他90项为单元/领域测试 | [Java报告](evidence/enterprise/java-tests.json) |
| 真实持久依赖 | 每域9个全新隔离MySQL库、V1–V8迁移、独立Redis；并发、租约、回滚、权益、计量、财务及HTTP测试执行完成 | [依赖报告](evidence/enterprise/mysql-integration.json) |
| 独立模拟器 | 21项通过；原始事实、退款重试、设备拒绝和未知结果保护 | [Python报告](evidence/enterprise/python-tests.json) |
| 正常金额样本 | 10000分预付，1600电费+1000服务费−200会员−300券=2100应付，退款7900，财务CLOSED | [金额演示](evidence/enterprise/demo-db.json) |
| 数据库模式HTTP与故障 | 19项HTTP验收、4类丢通知/响应/边界采样恢复通过；资金与权益SQL零违规 | [HTTP](evidence/enterprise/acceptance-db.json)、[故障](evidence/enterprise/faults-db.json) |
| 严格接口契约 | 正式OpenAPI语义57路径/64操作/156schema；最终镜像真实读取34/34响应通过，缺少既存财务明细的覆盖明确跳过 | [契约报告](evidence/enterprise/contracts-staff.json) |
| 浏览器运营台 | 最终9d/f02部署四角色通过，sourceOverlay=false；六项金额显示断言通过，JS错误和业务写入均0 | [浏览器说明](evidence/enterprise/operations-validation.md) |
| Redis实际服务故障 | 清空、真实停机、重启和回填通过；故障期间HTTP回源0.1809秒 | [Redis报告](evidence/enterprise/redis.json) |
| 完整回滚与恢复 | 旧版空库→新版V1–V8及真实模拟交易→旧镜像读升级数据→MySQL新库和双SQLite新卷恢复；11表逐行摘要一致，零违规 | [回滚报告](evidence/enterprise/rollback.json) |
| RocketMQ进程故障 | A40、A41两个窗口及A42物理死信/原事件审计重放全部通过；真实SIGKILL、重投ACK和资金单笔证据齐全 | [MQ报告](evidence/enterprise/mq.json) |
| 监控采集 | 远端与本机均PASS：API、双Worker全部实际采集；逐实例有限业务指标、HTTP/Hikari直方图、12条规则、10面板与认证数据源查询通过 | [监控报告](evidence/enterprise/monitoring.json)、[部署证明](evidence/enterprise/runtime-final.json) |
| A47持续负载 | **PASS（核心模拟场景）**：百万历史订单下50RPS×900秒，45000次全发；含排队的成功查询/建单P95为49.63/56.44ms。100会话×1800秒，36000计量请求全成功，100笔账单与独立渠道资金逐笔一致，零不变量违规 | [完整证据与边界](evidence/enterprise/performance/README.md) |
| GitHub CI | aaa2c265037249044c174abe3f5fc4f997b2d0e6的7组任务全部PASS：unit/images及MySQL、API故障、RocketMQ、Redis停机、备份回滚、实时监控六组backend | [实际工作流](https://github.com/Astrea-296111/ChargeHub/actions/runs/39287529175)、[CI汇总](evidence/enterprise/github-ci-aaa2c26.json) |

## 证据边界与故障修复

实际缺陷包括：并发开发初始化锁升级死锁、运营停用与建单竞争、退款旧attempt延迟失败、计量补传长处理租约、健康探针目录权限、Compose时间/挂载字符串类型，以及SQLite备份WAL关闭问题。均保留失败诊断与对应回归，不能把脚本存在或容器创建成功当作功能通过。

本云环境Docker使用vfs和32GB磁盘，多栈同时准备及监控镜像解压曾耗尽空间，Broker明确拒写后按原事件自动重试恢复；未降低磁盘阈值或重写资金事实。重栈顺序运行；持续负载已记录实际宿主/cgroup容量与容器限制，应用及依赖合计4CPU/8GiB，压测端另有配额。当前开发环境为DB轮询、两个Worker及监控，命名事实卷保留。

原始调度器22次漏发的900秒失败、冷启动短测失败及全部准备失败均保留；修复后未降低延迟阈值。管理端百万历史站点订单的单次SQL仍约1307ms，未计入客户混合HTTP SLO，后续优化建议见 [工程说明](engineering.md)。不能将本次结果扩展为全部管理查询或生产容量承诺。

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
