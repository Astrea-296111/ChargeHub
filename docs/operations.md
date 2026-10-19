# ChargeHub v1 运行与恢复手册

本版本接入独立的模拟支付渠道和模拟设备，不连接真实收单或充电设施。数据库是订单、权益和资金事实的权威来源；模拟器分别保存自己的 SQLite 数据。恢复演练必须同时考虑这些数据，不能仅恢复平台库后就认定双方资金一致。

## 开发启动与观察

要求 JDK 21、Python 3.11+、Docker Compose v2。构建使用仓库的 Maven Wrapper。首次启动先生成本地配置，随机密码和签名密钥仅写入被忽略的 `.local/dev.env`：

```bash
make env
docker compose --env-file .local/dev.env config --quiet
docker compose --env-file .local/dev.env up -d --build --scale worker=2
docker compose --env-file .local/dev.env ps
curl --fail --silent http://127.0.0.1:8080/actuator/health
```

API 使用 `8080`，模拟支付使用 `18081`，模拟设备使用 `18082`。开发端口仅在本机开放。`mysql`、`redis`、`mock-gateway`、`mock-device`、`api`、`worker` 为 Compose 服务名。API 负责 HTTP 入口，Worker 处理持久化任务与查询恢复；运行两个 Worker 时仍依赖数据库租约和稳定业务号保证重试安全。

```bash
docker compose --env-file .local/dev.env logs --tail=100 api worker
docker compose --env-file .local/dev.env up -d --scale worker=2 worker
```

不要把 `.local/dev.env`、数据库转储、登录令牌、签名密钥或完整通知报文提交到 Git，也不要将 `docker compose config` 的完整输出附到问题报告。可记录请求号、订单号、事件号、状态、金额和已脱敏的错误信息。

## 可选消息与监控拓扑

`make mq-up` 启动固定摘要的 RocketMQ 5.5.0、初始化 Topic/Group，并将两个 Worker 切到 `rocketmq` 模式。默认 `db` 模式使用 MySQL 领取；同一 Outbox 同时只选择一种发布模式。切换前暂停写入、排空原模式在途任务，再切换所有 Worker，不能把模式切换作为未完成任务的删除手段。原生客户端为 5.2.2；开发网络关闭 TLS，真实环境须配置 TLS 和专用凭据。消息版本、Inbox、短租约、完成证据和重投机制见消息 ADR。

`make monitoring-up` 启动仅本机开放的 Prometheus 9090 与 Grafana 3000。`__monitor__` 使用独立随机密码，应用只允许它访问自身身份、注销和 Prometheus 三个接口；采集代理续期短令牌，配置中没有长期 Bearer。Grafana 初始密码在 `.local/monitoring/grafana-admin-password`，勿放进问题报告。普通 OPS 账号保留正常运营权限。

运营台在 `/operations.html`，按需登录 `ops`、`finance`、`admin`。计量修正只能选择已有且属于该会话的原始证据，并由不同人复核；批准后仍不能改写已生成账单。财务恢复先查独立渠道，再走统一事实入口，UNKNOWN 不释放退款额度。真实金额/币种冲突保留 DIFF，不允许通过人工“设为成功”消除。

## 发布前检查

```bash
make test
python3 docs/spec/tools/check_spec.py
make integration-test
```

可选的运行接口检查使用 `requirements-dev.txt` 中锁定的 PyYAML 与 jsonschema。安装这些开发工具后，可以对已存在的业务数据执行：

```bash
set -a
source .local/dev.env
set +a
python3 scripts/check-contracts.py --login
```

该检查只查询业务接口，不创建订单、领券或操作设备；`--login` 仅允许创建短期登录令牌。若提供 `CHARGEHUB_CONTRACT_TOKEN` 与 `CHARGEHUB_FINANCE_TOKEN`，可省略 `--login`，全程只发送 GET。结果写入 `.local/reports/contracts.json`，不会保存令牌、密码或响应正文。它验证当前数据实际覆盖的响应；没有已出账订单时不会声称已经验证正式账单。当前运行接口见 [运行版 OpenAPI](openapi.yaml)，原资料设计基线仍保留在 `docs/spec/04-openapi.yaml`。

在隔离的 MySQL 8.4 数据库执行全新迁移、重复启动、正常计费与故障恢复演示，再执行 `scripts/invariants.sql`。所有查询应返回零行；出现任何行时保留该次运行数据并定位原因。该脚本使用一致性只读事务，检查资金净额、退款占用、券库存与次数，以及用户、订单、枪、会话、权益之间的绑定和孤儿记录。零行只是数据一致性的补充证据，不能替代逐笔渠道对账或故障验收。

部署前记录 Git SHA、JDK 版本、已验证应用镜像的不可变标识、MySQL 版本、迁移版本、配置变量名称及验收结果。资料包中的 `A01` 至 `A47` 是验收计划；没有实际执行记录的项目保持“未验证”。当前版本的功能边界和实测结果见根目录说明及测试报告，不将 MQ 故障矩阵、吞吐或延迟作为未经执行的承诺。

## 备份与隔离恢复

以下示例用于 Compose 开发库。备份文件包含业务数据，应保存在受控目录并加密转存。先确认渠道与设备模拟器没有写入新事实，生产环境应使用受支持的在线备份与时间点恢复策略，并为平台库和外部事实设计恢复点。

```bash
umask 077
mkdir -p .local/backups
docker compose --env-file .local/dev.env exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF chargehub' \
  > .local/backups/chargehub.sql
test -s .local/backups/chargehub.sql
sha256sum .local/backups/chargehub.sql > .local/backups/chargehub.sql.sha256
```

验证备份可恢复到**另一个数据库**，不覆盖运行中的库：

```bash
sha256sum --check .local/backups/chargehub.sql.sha256
docker compose --env-file .local/dev.env exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --execute="CREATE DATABASE chargehub_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"'
docker compose --env-file .local/dev.env exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root chargehub_restore' \
  < .local/backups/chargehub.sql
docker compose --env-file .local/dev.env exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names chargehub_restore' \
  < scripts/invariants.sql > .local/backups/restore-invariants.tsv
test ! -s .local/backups/restore-invariants.tsv
```

确认 Flyway 历史、核心表行数与代表订单的账单和流水，再将隔离应用指向 `chargehub_restore` 进行只读验收。记录恢复耗时、备份时间和验证订单。上述手工命令可按需执行；本次另通过 `make backup-check` 将 MySQL 备份恢复到新隔离库，14 张核心表行数与不变量均已核对。证据见 `docs/evidence/backup-restore.json`，这不包含模拟器卷恢复或应用镜像回滚。重新执行演练时使用新的隔离库名称；不要直接删除旧库。

模拟器的持久卷必须独立备份。SQLite 在线备份应使用 SQLite backup API，或在停止对应模拟器后备份卷；不能在写入过程中只复制主数据库文件而遗漏 WAL。恢复后先核对渠道业务号与设备会话事实，再恢复平台 Worker。

## 应用回滚

1. 在入口暂停新建订单与领券，保留支付回调和设备事件收件；保存当前镜像标识和数据库迁移版本。
2. 保持设备适配与必要的查询恢复运行，直到所有可能占用的会话有权威结果。不得为回滚强行清空活动订单或释放未知设备。
3. 确认旧应用可读取当前 schema。先在恢复副本验证旧镜像，再在本地 Compose override 中将 `api`、`worker` 的 `image` 指向该已验证镜像，使用 `up -d --no-build api worker` 切换。
4. 检查健康状态、回调收件、任务积压、退款状态和不变量，再逐步恢复新单。

数据库采用向后兼容的扩展迁移。禁止用反向 `DROP` 或直接修改已成功执行的 Flyway 迁移回滚资金事实。若迁移无法兼容旧应用，先前向修复；只有在明确恢复点、外部事实重放方案和审计记录完备时才执行数据库恢复。恢复数据库可能丢失备份之后的收件和本地事实，必须从独立渠道与设备重新确认，不能把回滚当作取消交易。

## 常见故障处理

| 观察到的情况 | 处理顺序与恢复验证 |
| --- | --- |
| MySQL 不可用 | 暂停扩大写入；保留渠道和设备独立事实。恢复数据库连接后等待租约接管，按原业务号重试，核对回调、任务与不变量。不得以 Redis 中的状态重建资金流水。 |
| 支付超时或成功通知丢失 | 保持 UNKNOWN/PENDING，主动查原支付号；查得 SUCCESS 后走统一资金事实入口，再由订单状态决定启动或全退。确认一笔 CAPTURE 和至多一个设备会话。 |
| 设备启动超时但可能已运行 | 保留用户与枪占用，使用 QUERY_SESSION 确认。未知状态禁止提前释放枪、全退或另发新 START 业务号。 |
| 停止执行但回执丢失 | 查同一会话；确认结束后收集最终序号与计量，进入计费。不要凭 HTTP 超时推断设备仍运行。 |
| 缺少边界或终点计量 | 保持 BILLING/REVIEW，保存原始事件并补传。累计值回退或同序号冲突须明确可见，不能按时间均摊电量或覆盖原样本。 |
| 退款响应与通知均丢失 | UNKNOWN 继续占用退款额度；按原退款号查询。SUCCESS 进入统一退款事实入口，不能另造退款号。人工重试必须保存原因与操作人。 |
| Redis 清空或不可用 | 查询允许回源数据库；券库存、用户活动单、次数和退款额度仍由数据库约束。恢复后只重建可丢弃缓存。 |
| 任务失败或租约过期 | 检查错误与外部事实，等待过期租约接管；使用相同事件号、命令号和退款号。旧持有者不能越过 lease_token 确认。DEAD 重放限管理员并记录原因。 |
| 对账渠道有、本地无 | 导入保存差异；主动查询独立渠道后通过统一事实入口补录，再重新验证差异。CSV 本身不授权直接入账。 |
| 对账本地有、渠道文件无 | 标记 INCOMPLETE/PENDING_CONFIRMATION，先确认导出日期、时区和完整性，再查原渠道单。不得删除本地流水来制造一致。 |
| 金额、币种、归属或业务号不一致 | 标记 DIFF，保留双方证据并阻止财务闭合；停止自动修复，记录受影响订单、处理原因和结果。不能直接改账单或金额。 |
| 费用超过预付 | 保留真实应付与 SHORTFALL，不追加扣款，也不能把账单应付截断到预付金额。 |

## 对账导入约定

`POST /v1/admin/reconciliations` 需要 FINANCE 或 ADMIN，使用 multipart 字段 `file`、`channel`、`businessDate`、`billType`、`sourceTimezone`、`versionNo`。文件为 UTF-8，精确表头见 [渠道契约](spec/11-integration-contracts.md)，上限 10MiB；支付和退款分开导出。发生时间使用 UTC `Z`，再按 `Asia/Shanghai` 或 `UTC` 归档业务日期，禁止按服务器日期截取。

同渠道、日期、类型与版本的相同文件返回原批次；相同批次但文件哈希不同返回冲突，应先核实再使用新版本。差异查询使用升序 ID 游标。有效 ISO 币种可被导入以保留币种差异，但平台首版仅接受 CNY 资金入账。导入不会直接修改支付、退款或不可变流水。通过权威查单补录后，只自动关闭已逐项核对业务号、金额、币种和业务日期的 CHANNEL_ONLY 差异；LOCAL_ONLY 仍等待完整渠道账单确认。

## 持续负载与完整回滚入口

固定候选镜像须带 `org.opencontainers.image.revision=<40位Git SHA>`。以下命令均使用全新的数据库、卷和内部网络；不对演示库施压：

```bash
python3 scripts/performance/run.py --image chargehub-app:1.0.0 --sha <候选SHA> --phase smoke --history 1000000 --down
python3 scripts/performance/run.py --image chargehub-app:1.0.0 --sha <候选SHA> --phase all --history 1000000 --down
python3 scripts/deployment/rollback.py --candidate-image chargehub-app:1.0.0 --previous-image chargehub-app:baseline-f8dede3
```

完整负载为固定 50RPS 900秒混合请求，再运行100会话、每5秒上报、1800秒；应用与依赖资源上限共4vCPU/8GiB，负载发生器另计。记录全部成功、业务拒绝、错误、超时与未发出的到达，成功查询与成功建单分别判断P95，不能用大量快速拒绝压低延迟。百万历史订单明确为合成 CANCELED/CLOSED 数据，不构造资金流水。原始请求、SQL/EXPLAIN与资源快照在 `.local/performance/<runId>/`。

A46脚本从真实旧版空库启动，升级候选、完成独立模拟交易，停止写入后备份，再切回旧镜像核对事实。最后把MySQL恢复到另一数据库，把两个SQLite通过 backup API恢复到另两个卷，比较表事实摘要后重启候选。不逆向DDL，不覆盖原库，不包含真实渠道恢复或MQ外部日志恢复的承诺。
