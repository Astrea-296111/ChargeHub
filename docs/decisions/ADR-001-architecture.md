# ADR-001：v1 技术基线、事务边界与模拟接入

状态：采用；后续演进见 ADR-002 与各模块 README。日期：2026-10-18。版本对应仓库 `1.0.0-SNAPSHOT`。

## 背景

首版需要证明充电交易、权益预占、独立渠道资金事实、设备会话和退款能够在重复通知与调用超时后恢复。上传资料中的企业案例和开源仓库用于设计参考，未将第三方业务源码复制或合并进产品构建；独立参考检出、固定提交和设计取舍记录在 `docs/references/`。当前设备接入是自定义 HTTP 协议，不声明实现 OCPP；支付渠道为独立模拟器。

## 决策

采用 Java 21、Spring Boot 4.1.1 和 Maven 3.9.16。八个 Maven 模块分别负责基础设施、身份、站点、权益、支付、充电、对账与应用组装。API 和 Worker 共用一个应用制品；`worker` profile 启用任务和恢复调度，Compose 分为 API 与 Worker 进程。开发默认 profile 同时启用两者，便于单进程调试。

平台事实使用 MySQL 8.4 InnoDB，Compose 固定为 `mysql:8.4.7` 并锁定镜像摘要。迁移由 Flyway 管理，应用连接初始化为 UTC，连接池隔离级别明确使用 `READ COMMITTED`。日期归属和费率边界按照站点时区计算；金额使用整数分，累计电量使用 Wh。

核心持久化使用 Spring JDBC 的 `JdbcTemplate`，经 `com.chargehub.infrastructure.Db` 执行参数化 SQL。事务中的行锁、条件更新和唯一约束在代码中明确可见，便于核对资金与资源竞争路径。项目保留并接入 MyBatis Spring Boot Starter 4.0.1，作为后续映射层演进的依赖基线；本版没有 MyBatis Mapper、XML 映射或基于 Mapper 的业务持久化，不能将依赖存在描述为已经实现完整 MyBatis 映射层。

HTTP JSON 使用 Spring Boot 管理的默认 Jackson 版本；领域快照、签名正文、审计与 CSV 周边数据通过隔离的 Jackson 2 `Json` 工具处理，`jackson-databind` 明确固定为 2.20.1。该工具自行注册时间序列化，不与 HTTP 层对象映射器混用。

Redis 7.4 仅缓存站点与展示费率，短 TTL 加随机抖动，事务提交后替换随机命名空间；缓存清空与不可用均可回源。登录尝试限流改为共享数据库窗口。Redis 不是订单、券、次数或退款额度的事实来源。Outbox 默认数据库轮询；可选 RocketMQ 5.5.0 与原生客户端 5.2.2，消息消费后持久化完成证据与 Inbox，最后确认 Broker。

## 一致性与恢复

- 订单、用户活动单、枪占用与权益变更使用同一本地数据库事务。关键路径按用户、订单及对应资源行加锁，资源释放使用活动订单号条件，防止旧任务清除新绑定。
- 创建支付、启动、停止和退款的意图与业务变更一起写入 Outbox。外部 HTTP 在领取事务提交后执行，不在持有订单事务锁期间等待网络。
- Outbox 使用短租约、`SKIP LOCKED` 和 `lease_token` 条件确认。业务唯一约束、稳定支付号、退款号、命令号和事件号承担重复执行去重；`db` 模式由 Worker 直接处理；`rocketmq` 模式先发布，消费时用独立租约、`consumer_inbox` 与完成证据处理重投，两种模式使用相同领域业务入口。
- 支付、设备模拟器分别使用自己的 SQLite 文件持久化事实，不读取平台业务库。超时保留未知状态，通过原业务号查单或查会话确认。渠道 CSV 来源是独立模拟器，不由平台资金表伪造渠道账单。
- 正式账单要求从起点到终点的计量序列完整，费率边界具备实际样本。计费保留真实超额应付并进入 `SHORTFALL`，不会将应付截断到预付金额。

## 身份与接入边界

用户登录使用 BCrypt，Bearer 令牌为 30 分钟有效的随机 opaque token，数据库仅保存 SHA-256 摘要。角色来自 `iam_user_role`，订单所有权由服务端身份核对。开发用户仅由 `dev` 初始化，密码由环境提供；应用不包含可复用的生产默认账号。

服务集成使用独立 HMAC-SHA256 身份，签名覆盖方法、无查询串路径、Unix 秒、UUID nonce 和原始正文 SHA-256。时间窗口依据系统真实 UTC 时钟，nonce 经独立事务写入共享数据库；演示业务 Clock 的固定时间不改变认证窗口。支付、设备和开发控制使用独立 keyId 与环境密钥。开发控制端点和 Compose 暴露端口仅用于模拟环境。

## 云环境 JDK 修复记录

本次云镜像最初只有 Debian Java 21.0.12.1 运行时，缺少 `bin/javac` 与 `lib/ct.sym`，导致 Maven 的 `--release 21` 编译失败。已经在 `/workspace/tools/jdk-21` 组装与运行时相同构建的完整 JDK，正常执行 `javac --release 21` 和模块测试。

补充包来自 Debian 官方仓库：

```text
openjdk-21-jdk-headless_21.0.12.1+1-1~deb13u1_amd64.deb
https://deb.debian.org/debian/pool/main/o/openjdk-21/openjdk-21-jdk-headless_21.0.12.1+1-1~deb13u1_amd64.deb
SHA-256: f3abafb6c644b03df042824e707cd211ea33254761d7f7b75be3f8dc0df97c7a
```

验证顺序为：使用系统 Debian archive keyring 验证 `InRelease` 签名，再核对 `Packages.xz` 的 SHA-256 和大小，最后核对 `.deb` 的 SHA-256 和大小。全程保持 HTTPS 和包完整性验证。实际 JDK 内保存 `chargehub-verification.json`；[setup-jdk.py](../../scripts/setup-jdk.py) 提供相同的无 root 修复流程，仅针对匹配的 Debian amd64 运行时。若已有完整可信 JDK 21，直接设置 `JAVA_HOME` 即可，无需运行修复。

## 验证边界

以上决策描述代码和配置基线。单元测试、真实 MySQL 迁移、进程启动、端到端故障演练和容器构建是不同的验证层级，结果应以对应验收记录为准。Compose 文件、迁移脚本、恢复手册或镜像摘要的存在，均不表示相关运行演练已经执行完成。
