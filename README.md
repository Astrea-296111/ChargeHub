# ChargeHub v1

面向充电交易与会员权益的可运行后端，含中文演示台、独立支付/设备模拟器。Java 21 模块化单体，MySQL 8.4 保存交易事实，事务 Outbox 支持数据库轮询与原生 RocketMQ 投递。金额一律整数 **分**，电量一律 **Wh**。

本版本只接模拟支付和模拟设备。`prod`/`production` profile 拒绝启动。没有真实扣款、真实设备控制或企业背书。

## 启动

需要完整 JDK 21、Docker Engine + Compose v2、Python 3.12。Maven Wrapper 自动安装经过 SHA256 校验的 Maven 3.9.16。

```bash
cd /workspace/ChargeHub
# 仅当云环境预装的 Java 缺少 javac/ct.sym 时：
python3 scripts/setup-jdk.py
make up
```

`make up` 会生成本地随机开发密钥和密码、构建工程，并启动 API、Worker、MySQL、Redis、支付模拟器和设备模拟器。所有宿主机端口仅绑定本机。中文演示台使用 8080 端口；模拟器控制使用 18081、18082 端口。

开发账号 `customer`、`member`、`ops`、`finance`、`admin` 分别对应普通用户、会员、运营、财务、管理员。密码由 `CHARGEHUB_DEMO_PASSWORD` 注入；本地生成值保存在 Git 忽略的 `.local/dev.env`，不提交或复制到公开资料。`__system__` 是禁用登录的后台审计身份。

默认本地业务时钟固定为 `2026-10-18T07:00:00Z`（上海 15:00），用于复现规定费率。HMAC 验签始终使用真实时钟。持续时间边界、支付到期等验证使用测试中的可控时钟；若要体验实时业务，清空 `.local/dev.env` 中 `CHARGEHUB_FIXED_TIME` 并重建服务。

## 演示和验证

```bash
make demo              # 10000分预付 → 20kWh → 2100分账单 → 7900分退款
make acceptance        # API权限、幂等、占枪、20次重复通知、取消和资金闭合
make faults            # 丢付款通知、启动响应丢失、退款双丢失、边界计量补传
make test-invariants   # 任一SQL不变量违规即非零退出
make test              # Java单元/领域测试 + 独立模拟器测试
make integration-test  # 新建隔离真实MySQL测试库并执行并发/事务/迁移/对账测试
make backup-check      # MySQL备份恢复到新隔离库，核对行数与不变量
make mq-up             # RocketMQ 5.5.0 + 原生Java客户端，两Worker
make monitoring-up     # Prometheus/Grafana，仅本机端口和独立监控身份
```

`make demo` 首次创建并完成业务，之后对已完成的同一金额样本重新核验，不重复使用已核销券。运行原始数据和 JUnit XML 在 `.local/reports/`、各模块 `target/surefire-reports/`。隔离集成库名称以 `_test` 结尾，保留供检查，正常演示库不被测试脚本删除。并发领券测试实际发起 1000 个不同用户的请求，断言发出 100 张；不是压力测试业绩。

浏览器可以完成领券、报价、建单、查看退款。`/operations.html` 是运营与财务演示台，按服务端角色提供异常订单、计量证据与双人复核、补传、对账差异和死信重放。模拟付款及电量推进通过受控制密钥保护的独立模拟器执行，不提供普通用户伪造付款成功接口。要完成浏览器已有订单：

```bash
set -a
source .local/dev.env
set +a
python3 scripts/demo.py --username customer --order <订单UUID>
```

此辅助剧本使用固定日间 20kWh 计量；实时模式跨费率演示请使用 `scripts/faults.py` 或模拟器 `advance` 接口。

## 工程与契约

| 目录 | 职责 |
| --- | --- |
| `chargehub-infrastructure` | 参数化SQL、幂等请求、Outbox租约、HMAC、统一协议与追踪 |
| `chargehub-identity` | BCrypt、短期不透明访问令牌、角色与当前身份 |
| `chargehub-station` | 站点、版本化费率、时段校验、纯函数金额计算 |
| `chargehub-benefit` | 会员月额度、券发行、预占、核销、释放与流水 |
| `chargehub-charging` | 订单、设备会话、计量证据、不可变账单与预付止损 |
| `chargehub-payment` | 独立资金事实、支付/退款适配与额度占用 |
| `chargehub-reconcile` | 渠道CSV、双向差异、批次去重与审计 |
| `chargehub-app` | HTTP、种子、运行装配、恢复任务、监控和演示台 |
| `simulators` | Python标准库、各自SQLite、HMAC、幂等与故障注入 |

GitHub 仓库：<https://github.com/Astrea-296111/ChargeHub>。验收入口与逐项证据见 [A01–A47 验收矩阵](docs/acceptance-matrix.md)。源码存在、测试通过和持续性能达标分别记录。

运行接口见 [docs/openapi.yaml](docs/openapi.yaml)，原始资料保存在 [docs/spec](docs/spec/README.md)。公开源码的实际阅读与取舍见 [设计参考](docs/references/README.md)。技术取舍见 [架构 ADR](docs/decisions/ADR-001-architecture.md)，运维见 [运行手册](docs/operations.md)，实际结果见 [验证记录](docs/validation.md)。

服务间签名覆盖方法、路径、时间戳、nonce 和原始请求体SHA256。签名密钥分别使用 `CHARGEHUB_PAYMENT_KEY`、`CHARGEHUB_DEVICE_KEY`，模拟控制密钥为 `CHARGEHUB_CONTROL_KEY`。模拟器是一个受信任网关身份；首版尚未提供每台物理设备的独立密钥分发。

`api` 和 `worker` 是相同镜像的不同 profile。外部调用不持有数据库业务事务；资金、账单、权益状态和 Outbox 在本地事务里提交。租约 token 阻止旧 Worker 确认已接管任务；重试沿用原支付、退款和命令编号。订单结束与退款到账分别表示，退款未知时持续占用额度。

平台在已确认的优惠前费用达到预付款 95% 时发出停止指令；计量无法证明完整时保守停止并保留审核证据。正式账单仍只使用完整、可验证的边界电量。超预付保留真实费用并标记 SHORTFALL；渠道最终拒绝退款返回 FAILED_FINAL，需要人工核实，不能生成新退款号绕过终态。

## 运行边界

v1 包含核心交易、共享登录限流、令牌撤销、角色管理、目录与会员运营、不可变资金流水、财务查询恢复、计量双人复核、Redis 展示缓存、RocketMQ、审计与监控。正式建单、券库存、月额度和退款始终以 MySQL 为准；Redis 失联按短超时降级。持久层采用 Spring JDBC 显式 SQL，MyBatis Starter 同代依赖已接入但无业务 Mapper，取舍在 ADR 中说明。

真实支付沙箱、OCPP、付费续订、多个券叠加和分账钱包属于后续接入范围。模拟环境不等于生产认证；最终负载、恢复结果与云端 CI 以 [验证记录](docs/validation.md) 的实际执行为准。未组织外部人员试用，不虚构用户反馈。

```bash
make down # 停止自己启动的服务，保留数据库与模拟器卷
```

开发任务直接使用现有检出目录；每个云任务已有隔离环境，无需另建 Git worktree。

## 云环境复用

安装脚本和启动说明已保存到当前云环境草稿的 `install_script`、`start_skill`。这些保存动作不代表环境已发布；需要复用时，在环境设置中检查并保存配置，再发布环境。当前工作区与本地 Docker 启动已验证，新任务的发布快照恢复仍需单独验证。
