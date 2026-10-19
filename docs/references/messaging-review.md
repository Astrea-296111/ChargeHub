# 消息实现参考审阅

审阅日期：2026-10-19。通过官方 GitHub HTTPS API 和 `raw.githubusercontent.com` 实际读取下列文件，保留 TLS 验证；以下链接固定 SHA，避免分支移动造成证据变化。

## 固定来源

| 项目 | 固定版本/提交 | 实际读取 |
| --- | --- | --- |
| Apache RocketMQ 原生 Java 客户端 | tag `java-5.2.2`，commit [`6e2185bb638b639663bda240cf119804a595e099`](https://github.com/apache/rocketmq-clients/commit/6e2185bb638b639663bda240cf119804a595e099) | [ProducerNormalMessageExample](https://github.com/apache/rocketmq-clients/blob/6e2185bb638b639663bda240cf119804a595e099/java/client/src/main/java/org/apache/rocketmq/client/java/example/ProducerNormalMessageExample.java)、[SimpleConsumerExample](https://github.com/apache/rocketmq-clients/blob/6e2185bb638b639663bda240cf119804a595e099/java/client/src/main/java/org/apache/rocketmq/client/java/example/SimpleConsumerExample.java)、[SimpleConsumerImpl](https://github.com/apache/rocketmq-clients/blob/6e2185bb638b639663bda240cf119804a595e099/java/client/src/main/java/org/apache/rocketmq/client/java/impl/consumer/SimpleConsumerImpl.java)、[ClientConfiguration](https://github.com/apache/rocketmq-clients/blob/6e2185bb638b639663bda240cf119804a595e099/java/client-apis/src/main/java/org/apache/rocketmq/client/apis/ClientConfiguration.java) |
| Apache RocketMQ Broker | tag `rocketmq-all-5.5.0`，annotated tag object `2af6bf1dbf656edac866c6b6452e0c227e1ae07d` 指向 commit [`98799686a99c85537cf0a13648b0b521bd16a6b1`](https://github.com/apache/rocketmq/commit/98799686a99c85537cf0a13648b0b521bd16a6b1) | [ProxyConfig](https://github.com/apache/rocketmq/blob/98799686a99c85537cf0a13648b0b521bd16a6b1/proxy/src/main/java/org/apache/rocketmq/proxy/config/ProxyConfig.java)、[UpdateSubGroupSubCommand](https://github.com/apache/rocketmq/blob/98799686a99c85537cf0a13648b0b521bd16a6b1/tools/src/main/java/org/apache/rocketmq/tools/command/consumer/UpdateSubGroupSubCommand.java) |

客户端许可证已实际读取：[LICENSE](https://github.com/apache/rocketmq-clients/blob/6e2185bb638b639663bda240cf119804a595e099/LICENSE)，Apache License 2.0。生产客户端使用发布的 Maven 依赖，没有把 Broker 或 SDK 实现复制到项目；独立 `RocketMqSmoke.java` 使用上述公开示例展示的 builder/receive/Ack API，并在文件中保留来源与许可说明。完整上游安全审计不属于本次读取范围。

## 采用与不直接套用的部分

| 来源发现 | ChargeHub 的处理 |
| --- | --- |
| 原生 producer 用 `ClientServiceProvider`、`ProducerBuilder`、`setKeys`、正文以及 `SendReceipt`。 | 使用逻辑 eventId 作为 key，事件名作 tag；receipt 只证明 Broker 接受，随后按 Outbox lease_token 更新发布状态。不会把一次 send 返回成功解释成 MySQL 与 Broker 原子提交。 |
| SimpleConsumer 显式 `receive(maxNum,invisibleDuration)` 和 `ack(message)`，示例在收到消息后直接 Ack。 | 不照搬示例的立即 Ack：先验证原数据库 Outbox，再执行应用 handler，保存完成证明与 inbox，最后 Ack。正文、业务及完成失败均不 Ack。 |
| SDK 支持长轮询、消息可见性、`changeInvisibleDuration` 和重复投递次数。 | 使用独立消费线程，默认可见性 45 秒、数据库租约 30 秒；专用故障 Worker 缩短到 10/5 秒。长 handler 用独立 heartbeat 按 owner/token 续数据库租约并延长 Broker 可见性，不能复活过期租约；完成前停止 heartbeat。过期网络请求仍由稳定业务号去重。 |
| `ProxyConfig` 支持 LOCAL 模式、`localServeAddr`、gRPC 端口。 | Compose 使用 Broker 内嵌 LOCAL Proxy，明确其服务名，避免把宿主 `127.0.0.1` 广播给其他容器。没有暴露 Broker/NameServer 到公共主机端口。 |
| `mqadmin updateSubGroup -r` 的实际含义是 `retryMaxTimes`。 | 显式初始化消费者组，并分别观察应用 DEAD 与 Broker `%DLQ%`。没有把仅存在于数据库的失败记录称为物理 Broker DLQ。 |
| 官方示例不是持久消费幂等或业务事务实现。 | `consumer_inbox`、原载荷 hash、租约、完成证明和管理员审计由本项目实现；不将这些能力归功于示例，也不宣传端到端 exactly-once。 |

## 实际版本验证

官方 Maven metadata 当次读取显示稳定客户端 `5.2.2`。下载的 shaded 客户端 JAR 与 Maven Central 发布的 SHA-1 `647f07cea007742bab52a8c52eb75723689a1ee9` 相符，本项目额外固定 SHA-256 `71dfe1c545b207a3016919a52be1ab984c385ea94cb8fd622c4e252b5cba37e5`；脚本复跑时校验固定 hash，不跳过验证。

官方 Docker Hub 标签列表实际提供 Broker `5.5.0`，镜像 digest 为 `sha256:7e8f6c9dbd9df742ed26ba69c00d4ad69e2f86a56f3ca7782ff8144dd0798132`。`apache/rocketmq:5.5.1` 拉取实测失败，故没有使用不存在的标签。5.5.0 容器内原生 Java 5.2.2 客户端已真实执行发送、接收、正文逐字节相等和 Ack；原始结果保存在 `.local/reports/mq-native-smoke.json`。

这份文档记录来源、读取范围和取舍。A40–A42 是否通过应读取实际 `.local/reports/mq.json`；没有报告的故障场景不能由示例代码或容器健康状态推断通过。
