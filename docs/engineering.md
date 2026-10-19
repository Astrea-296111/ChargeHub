# 构建、监控、部署演练与性能证据

本文区分可复用工程配置与已执行结果。业务验收以对应 JSON 报告、原始请求和固定镜像为依据；安装成功、短时 smoke 或配置文件存在均不代表完整性能验收通过。

## 源码镜像与健康状态

`Dockerfile` 从固定 digest 的 Maven 3.9.16 / JDK 21 构建，再复制到固定 digest 的 JRE 21；不依赖宿主机先生成 `target/*.jar`。应用使用 UID/GID 10001，健康探针是编译后的无依赖 Java 类；readiness 与 liveness 由 Spring Boot health probes 提供。镜像 OCI revision 须设置为构建的 Git SHA。

```bash
make env
docker build \
  --secret id=maven_settings,src=.local/container-maven-settings.xml \
  --secret id=maven_ca,src=.local/container-maven-ca.crt \
  --build-arg VCS_REF="$(git rev-parse HEAD)" \
  -t chargehub-app:candidate .
```

公共 CI 可使用空代理配置。云环境代理所需 Maven settings 通过 BuildKit secret 挂载，`localRepository` 不包含宿主绝对路径。平台提供的公开根 CA 仅导入构建步骤的 tmpfs 信任库；Maven `-C` 校验与 TLS 验证始终开启，最终镜像不复制代理凭据、secret mount 或临时信任库。

实际网络验证记录：独立小构建已通过真实 Maven `package` 下载 SLF4J 2.0.17 并生成 JAR，日志 `.local/network-smoke/build-project.log`。首轮只有代理配置时发生 PKIX 失败；配置平台 CA 后普通项目构建通过。此记录验证构建链路，不代替业务候选镜像的完整测试。

## 只读监控路径

```bash
make monitoring-up
```

`compose-monitoring.yaml` 提供固定版本 Prometheus 与 Grafana、只读 scrape proxy 和两个 Worker 采样目标。Prometheus/Grafana 仅绑定 loopback；没有公开敏感 Actuator 端点。代理只允许三个固定 GET 路径，分别读取 API 与两个明确 Worker 实例的 `/actuator/prometheus`，不能转发任意 URL、业务 POST 或用户自带认证头。

`__monitor__` 使用单独的 `CHARGEHUB_MONITOR_PASSWORD`。该变量由 `scripts/dev-env.py` 增量生成；`scripts/monitoring/setup.py` 只生成私有 file mounts，不建立第二套密码源。代理自动续期短期 Bearer token；服务端将该账号限制为 metrics / 当前身份 / 注销。禁用账号或撤销其 token 可立即撤销采样权限。Grafana 管理密码保存在 `.local/monitoring` 私有目录下，不进入 Git 或 CI artifacts。

业务指标来自数据库事实与 Micrometer，Grafana 不含固定造数曲线。HTTP 与 Hikari 连接获取 histograms 在 overlay 中显式启用。12 条规则覆盖真实 HTTP P95 / 5xx、DB 等待与连接获取 P95、Outbox 延迟 / 死信、消息投递死信 / 隔离、退款和启动未知状态、资金差异、券库存不变量。共享业务库 gauge 在 API / Worker 间采用 `max`，避免重复相加。

固定 Prometheus 镜像中的 `promtool check config` 已实际通过，包含 12 条规则；日志 `.local/promtool.log`。这只证明规则语法与加载配置，真实 scrape、仪表盘与告警触发必须另记录运行结果。规则评估可在 Prometheus 本地 Alerts 页面检查；未配置对外通知渠道。

## 隔离压测

入口为 `scripts/performance/run.py`，不会接受任意 API URL。它创建随机 Compose project、独立 MySQL 数据库、独立 Redis / RocketMQ / 支付与设备模拟器卷，以及独立 Python loadgen 容器。没有向宿主发布端口，不访问演示库、演示卷或公共服务。镜像先解析为不可变 image ID，并核对 OCI revision 与 `--sha`。

```bash
# 先在固定候选上执行短 smoke，但数据规模仍为百万历史订单。
python3 scripts/performance/run.py --image <fixed-image-id> --sha <git-sha> \
  --phase smoke --duration 10 --history 1000000 --down

# 正式两个阶段顺序执行；不传 duration 即为 900 秒与 1800 秒。
python3 scripts/performance/run.py --image <fixed-image-id> --sha <git-sha> \
  --phase all --history 1000000 --down
```

应用与依赖容器资源限制合计 4 vCPU / 8 GiB：MySQL 1 CPU / 2 GiB，API 1 / 2，Worker 0.4 / 1，RocketMQ Broker 1 / 1.5，NameServer 0.1 / 0.25，Redis 0.1 / 0.25，两个模拟器各 0.2 / 0.5。loadgen 另限 0.75 CPU / 512 MiB。实际宿主为 5 个逻辑 CPU，内存由报告记录 Docker 字节值；助手进程 cgroup 观测到 CPU quota 为 4 CPU、内存上限 32 GiB。容器配额、宿主容量与父 cgroup 限额是不同事实，不能将其描述成一台独占的 4 核 / 8 GB 物理服务器。正式阶段应停止无关测试和演示 Worker / 监控负载，并保存真实容器配置与机器信息。

- 混合阶段使用固定随机种子、50 RPS、开放到达模型，覆盖站点、订单、权益、报价、建单、取消和领券。调度器不会在服务变慢后自行降低计划到达率；客户端容量不足或错过到达时间均写入 `client_unsent`。
- 会话阶段真实建立 100 笔支付与独立设备会话，每个会话每 5 秒上报一次计量，持续 1800 秒，随后确认停止、完整计量、账单和退款闭合。用户 token 在后续 API 查询前按 `expiresIn` 重新获取，登录与刷新请求同样保留。
- 百万历史订单明确标记为合成的 `CANCELED/CLOSED` 未支付订单，不伪造资金流水或真实车流。执行现行用户 / 站点 `(created_at,id)` 游标查询及实际 `EXPLAIN FORMAT=JSON` / `EXPLAIN ANALYZE`，保留旧 `ORDER BY id` 查询作对照。
- 每条请求保留计划序号、排队时间、耗时、HTTP 状态、错误分类和 requestId，不保存密码、token 或认证头。超时、技术错误、业务拒绝与客户端未发送分别计数，均不从分母中删除。
- 汇总包含逐操作与总体 P50 / P95 / P99；成功查询 P95 < 300 ms、成功建单 P95 < 500 ms 分别检查。所有样本与成功样本分别统计，不能用整体混合 P95 代替建单指标。

证据目录为 `.local/performance/<run-id>/`。`env` 是私有凭据文件，不可作为 artifact；可分享 `run.json`、各阶段 `summary.json` / `requests.jsonl`、容器资源快照、SQL 与执行计划。`--down` 停容器但保留卷与证据，禁止为了清理而覆盖演示库。

当前已记录三次真实失败且没有产生业务负载或百万合成数据：第一轮 Compose 时间值未加引号，第二轮 inline tmpfs 被逗号拆分，第三轮出现磁盘空间不足。对应 run 为 `44dfff832bf1`、`0f9fcbdbdcb1`、`6d08246a1d17`。时间 / tmpfs 与异常清理路径已修正；仅在核对项目标签、确认无容器附着并获维护授权后，清除了这三个未初始化成功的专属卷，所有失败报告与日志仍保留。完整性能结果必须以后续固定候选的实际报告为准。

## A46 升级、应用回滚和恢复

```bash
python3 scripts/deployment/rollback.py \
  --candidate-image <fixed-image-id> \
  --previous-image chargehub-app:baseline-f8dede3
```

演练只使用新 project、随机数据库与独立模拟器卷。流程为旧镜像初始化空库并建单取消，候选执行增量迁移并完成真实签名支付 / 设备 / 退款闭环，然后旧镜像在升级后的同一库中启动并读取既有事实。脚本比对设备原始事件、冲突、支付通知、资金流水、账单、退款、会话和权益预占的逐表行数与内容哈希。

MySQL 使用一致性 dump，恢复目标始终是新数据库。两个独立 SQLite 使用 SQLite backup API，从保留的数据库事实创建快照，再恢复到新卷中的新文件；目标文件已存在即拒绝，恢复前验证文件 SHA-256，恢复后验证 `integrity_check` 与逐表逻辑行哈希。独立 WAL 样本的 SQLite helper 已实际通过；完整 A46 结果由 `.local/reports/rollback.json` 给出。

旧基线镜像保留为 `chargehub-app:baseline-f8dede3`，已知 image ID `sha256:dfcb0aa7130e62892b6e0ef1599d8e45f6a8754c452422b22737ab958a6c7cc7`。备份包含私有业务 / 身份资料，保存在 `.local/rollback`，不进入公开 artifacts。此演练验证 MySQL 与模拟器存储及应用兼容性；外部消息系统的灾备恢复不能据此宣称已验证。

## CI 分层与实际状态

`verify` workflow 将 unit / 独立模拟器 / 源码镜像构建与五个 backend gate 分开：所有真实 MySQL suite、HTTP 与故障恢复、真实 RocketMQ、Redis 实际清空 / 停机 / 恢复、备份与旧镜像回滚。矩阵各有独立 runner / 凭据，不并行修改同一数据库。artifact 白名单仅包含测试 XML 与脱敏 JSON / 不变量结果，不上传 `.local/**` 全目录、env、Maven secret、数据库 dump 或模拟器备份。

30 分钟会话负载不在默认 push / PR workflow 中运行。独立 `isolated-performance-manual` 需要显式触发与专用 `chargehub-perf` self-hosted runner，默认参数为 900 秒混合、1800 秒会话和百万历史订单。

历史基线 Actions run `39135934161` 已通过：<https://github.com/Astrea-296111/ChargeHub/actions/runs/39135934161>。更新后的 workflow 只有在 GitHub 实际执行后才能记录新的成功状态，不能沿用历史绿灯作为新代码验证。
