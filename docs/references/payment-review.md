# 支付与退款实现参考审阅

审阅日期：2026-10-18。通过 GitHub 官方 HTTPS API 获取分支提交及文件树，通过 `raw.githubusercontent.com` 读取以下源码；保留 TLS 验证。本文记录实际读取范围和设计取舍，不代表执行或完整审计上游项目。ChargeHub 没有引入上游源码或运行时依赖。

## 可复查来源

- 仓库：[YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro)
- 审阅分支：`master`。
- 固定提交：[`ac022b15a094cf9cf82903d429b9729e72309da5`](https://github.com/YunaiV/ruoyi-vue-pro/commit/ac022b15a094cf9cf82903d429b9729e72309da5)。分支可能前移，本文链接均固定到该 SHA。
- 许可证：根目录 [`LICENSE`](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/LICENSE)，MIT，`Copyright (c) 2021 ruoyi-vue-pro`。如后续复制软件或实质片段，应保留版权及许可声明；本次仅参考设计，没有复制大段代码。

| 编号 | 已读取源码 | 关注范围 |
| --- | --- | --- |
| P1 | [`PayOrderServiceImpl.java`](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/service/order/PayOrderServiceImpl.java) | 支付创建、成功回调、关闭、查单补偿、退款累计 |
| P2 | [`PayRefundServiceImpl.java`](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/service/refund/PayRefundServiceImpl.java) | 退款创建、额度检查、超时、成功与失败终态、查询恢复 |
| P3 | [`PayNotifyServiceImpl.java`](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/service/notify/PayNotifyServiceImpl.java) | 业务通知任务、事务提交后执行、并发去重、逐次日志、有限重试 |
| P4 | [`PayOrderMapper.java`](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/dal/mysql/order/PayOrderMapper.java) | 商户订单查询及 `id + status` 条件更新 |
| P5 | [`PayRefundMapper.java`](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-pay/src/main/java/cn/iocoder/yudao/module/pay/dal/mysql/refund/PayRefundMapper.java) | 商户退款号查询、处理中退款计数及条件更新 |

本次没有读取 promotion、mall、渠道 SDK 验签或完整建表脚本，不能以本文证明这些模块或数据库约束的行为。

## 实际发现与采用方式

| 主题 | 上游源码证据 | ChargeHub 的采用与边界 |
| --- | --- | --- |
| 支付创建幂等 | P1 `createOrder` 第 116–127 行，以应用和商户订单号查询，存在则返回原支付单编号；P4 第 46–48 行对应查询。 | 采用稳定业务单号关联支付单的原则。当前 `PaymentService.create` 按 `order_no` 查已有支付单，数据库约束和事务仍是并发正确性的必要条件。单凭先查询不能证明并发创建安全。 |
| 支付回调重复与并发 | P1 第 292–373 行对已成功记录直接复用，并通过状态条件更新；P4 第 55–57 行在 SQL 条件中匹配原状态。 | 采用事务内确认业务状态、重复结果不重复记账。ChargeHub 额外使用渠道事件标识去重，并核对金额、币种、渠道业务号、发生时间；支付事实只生成一笔资金流水。没有将“签名正确”当作金额正确的替代品。 |
| 退款创建的语义差异 | P2 第 105–109 行发现相同商户退款号后抛 `REFUND_EXISTS`；不是直接返回已存在退款。第 165–172 行检查已退金额及是否已有 WAITING 退款。 | 不照搬重复退款报错语义。ChargeHub 以支付单和退款原因形成稳定业务键，同键同金额返回已有退款，同键不同金额报冲突。 |
| 退款额度与并发 | P2 第 165–172 行使用累计已退金额和 WAITING 数量检查；P1 第 410–429 行成功退款后增加已退金额。读取范围未证明创建退款时存在原子额度预占。 | 不直接采用“先检查 WAITING 数量再插入”的额度控制。ChargeHub 在支付行锁内预占退款额度，可用额度为已实收减已成功退款减已预占；成功转入已退，明确失败释放预占。未知结果继续保留预占，避免重复退款。 |
| HTTP 超时不是最终失败 | P2 第 125–142 行捕获渠道异常并保留退款记录，注释说明超时时渠道可能已经成功，后续通过回调或轮询确认。 | 采用该语义。网络超时进入未知/待查询状态，使用原业务号查单；不能因为没有收到响应就创建另一笔退款或释放额度。 |
| 退款失败终态 | P2 第 203–277 行，SUCCESS 和 FAILURE 分别处理；两者都要求原状态为 WAITING，重复相同终态返回，反向终态被拒绝。 | 采用终态保护，映射为 `SUCCESS` / `FAILED_FINAL`。明确失败后到达的矛盾成功事件不能直接复活原退款并再次扣减额度，应保留冲突供对账审查。有限次通知重试耗尽不等同于渠道退款最终失败。 |
| 查单与回调共用入口 | P1 第 493–517 行查单后调用 `notifyOrder`；P2 第 301–318 行查退款后调用 `notifyRefund`。 | 采用统一的业务事实校验与状态处理，防止查单路径绕过回调的金额、币种和业务号约束。恢复作业应重复执行仍不增加资金流水。 |
| “查无此单”不必然可关单 | P1 第 501–508 行解释渠道短暂未找到会映射 CLOSED，但立即关闭本地单会阻止后续成功回调，因此同步时暂不按该状态关单。 | 采用保守确认原则。未知、短暂不可见、请求错误和经过验证的最终关闭必须区分。ChargeHub 模拟器的确定 CLOSED 不能被泛化成所有真实渠道的查询失败语义；接入真实渠道时需要专用适配和关单协议。 |
| 持久通知及审计 | P3 第 95–128 行先持久化通知任务，事务提交后异步执行；第 187–202 行用 Redis 锁及通知次数复查避免并发重复；第 205–223 行逐次记录结果；第 269–295 行控制重试与最终通知失败。 | 采用持久任务、受限重试与逐次审计的设计原则。ChargeHub 支付回调收件记录、资金流水和业务 Outbox 分别承担接收、记账和后续业务推进职责。没有移植上游 Redis 锁、线程池或 120 秒超时参数；这些参数不适合直接作为本项目默认值。 |

## 对账与后续接入的明确边界

已读文件提供支付状态恢复和通知日志，并不足以证明上游具有完整的渠道账单对账、不可变会计分录或账务调平功能。本文不把查单轮询称为日账对账，也不声称 ChargeHub 的资金台账来自上游实现。

ChargeHub 的资金流水、渠道账单和业务订单分别保存；对账应按渠道业务号、金额、币种和事实时间定位差异，保留差异与处置记录，不通过改写成功流水“调平”。真实支付接入仍需实现渠道官方签名验证、原始响应验证、账单下载及金额核对，并覆盖超时成功、重复回调、查询不一致、失败后矛盾成功等场景。模拟器的 HMAC 和独立 SQLite 仅验证本地集成行为，不构成真实支付渠道认证。

本文件是来源与设计审阅记录，不是测试通过证明。实际可运行范围与验证结果以仓库的 `docs/validation.md` 及对应证据文件为准。
