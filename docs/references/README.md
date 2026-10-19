# 开源项目设计参考

这些记录对应实际读取过的公开源码和固定提交。参考检出独立于产品构建，ChargeHub 没有合并上游业务源码；上游许可证、文件链接、采用与拒绝的设计均保留在各评审中。

| 参考 | 实际借鉴与独立实现 | 评审 |
| --- | --- | --- |
| ruoyi-vue-pro | 权限与审计、券归属、条件更新、支付回调/查单统一；补充共享登录限流、最后管理员保护、退款额度预占和稳定业务号 | [支付](payment-review.md)、[权益](benefit-review.md)、[身份说明](../../chargehub-identity/README.md) |
| macrozheng/mall | 模板与领取职责、会员记录及分层；对库存先查后写的竞争风险使用MySQL行锁、条件更新和唯一约束处理 | [权益](benefit-review.md) |
| SteVe | 研究充电设备会话和事件处理边界；本版采用独立模拟设备、原始计量证据和不可变账单，未实现OCPP，也未复制其GPL业务代码 | [设备与计量](device-review.md) |
| Apache RocketMQ 官方示例与原生客户端 | 版本化消息、稳定事件号、发布证据、短租约续期、Inbox与完成证据、最后ACK、物理死信和审计重放 | [消息](messaging-review.md)、[消息ADR](../decisions/ADR-002-messaging.md) |

项目中的并发正确性以实际事务与故障测试证明，不依赖“参考了成熟项目”这一描述。当前回归和验收证据见 [验证记录](../validation.md)。
