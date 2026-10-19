# 设备会话与订单生命周期源码复核

复核日期：2026-10-18。通过 HTTPS 实际执行 `git clone --depth 1`，读取下列固定提交的源码和许可证；本文仅记录独立设计分析，没有复制第三方实现。分支会继续变化，以下 SHA 和源码链接是本次证据。

| 项目 | 仓库／实际分支 | 实际 HEAD SHA | 许可证证据 |
| --- | --- | --- | --- |
| SteVe | https://github.com/steve-community/steve ，`master` | `28e326edd64c18542e713523a5fcdb44b68df18f` | `LICENSE.txt` 为 GPL v3；所读 Java 文件头声明 GPL-3.0-or-later |
| mall | https://github.com/macrozheng/mall ，`master` | `dcaa93b3150352e5044708d7211b5bed0af4509f` | `LICENSE` 为 Apache License 2.0 |

## SteVe：实际阅读路径与发现

源码链接前缀：<https://github.com/steve-community/steve/tree/28e326edd64c18542e713523a5fcdb44b68df18f>。

- `src/main/java/de/rwth/idsg/steve/service/CentralSystemService16_Service.java`：`startTransaction` 将设备起始时间和平台 `eventTimestamp` 分别保存；`meterValues` 按设备、连接器、交易号取归属；`stopTransaction` 校验失败时调用 `updateTransactionAsFailed`，成功后才发布结束事件。
- `src/main/java/de/rwth/idsg/steve/service/CentralSystemService16_ServiceValidator.java`：`validateStop` 检查交易存在、设备是否已结束、结束时间不早于开始、未来时间容差、结束表值不小于开始、授权标识关系；关联交易的 `validateMeterValues` 要求交易存在且未由设备结束。
- `src/main/java/de/rwth/idsg/steve/repository/impl/OcppServerRepositoryImpl.java`：`getTransaction` 限定 `chargeBoxId` 和可选连接器；`insertIgnoreTransaction` 针对响应丢失后重发的相同起始消息，以 EVSE、idTag、起始时间、起始表值查重，并使用按设备分组的进程内锁；重复时返回同一交易号。`updateTransactionAsFailed` 单独记录失败停止事实、设备、事件来源及原因。`updateTransaction` 的停止写入失败后仍尝试写入连接器状态。
- `src/main/java/de/rwth/idsg/steve/repository/impl/TransactionRepositoryImpl.java`：`getDetails` 优先用显式交易关联的计量值；对没有交易号的计量才使用设备与时间窗回退，下一笔开始时间为排他上界，避免缺失停止的旧会话吞入下一笔数据。已有明确其他交易号的数据不会进入时间窗回退。
- `src/main/java/de/rwth/idsg/steve/service/TransactionService.java`：人工 `stop` 对已停止交易提前返回；结束值依次取最后有效计量、下一笔开始、当前开始值；`findLastMeterValueFromMonotonicStreak` 会在表值回退后寻找新的单调段。
- `src/main/java/de/rwth/idsg/steve/utils/TransactionStopServiceHelper.java`：能源计量筛选为可解析数值、RAW 格式、累计输入电量、Wh/kWh；排除 SignedData；数值处理使用 double，人工结束表值向上取整。
- `src/main/java/de/rwth/idsg/steve/ocpp/ws/ocpp16/Ocpp16Handler.java`：将协议请求交给中心服务，说明协议适配与会话应用服务可以分离。
- `src/main/java/de/rwth/idsg/steve/ocpp/ws/FutureResponseContextStore.java`：响应上下文键包含 WebSocket 会话号及消息号。仅阅读接口，未把它当作持久重试或断线恢复实现的证据。

## mall：实际阅读路径与发现

源码链接前缀：<https://github.com/macrozheng/mall/tree/dcaa93b3150352e5044708d7211b5bed0af4509f>。

- `mall-portal/src/main/java/com/macro/mall/portal/service/impl/OmsPortalOrderServiceImpl.java`：创建订单锁定库存、占用优惠券和积分并发送延迟取消消息；`paySuccess` 通过主键直接改已付款状态并扣减实际库存；`cancelOrder` 先查询未付款且未删除订单，再取消、解锁库存、返券、返积分；`cancelTimeOutOrder` 批量扫描未支付超时订单并释放资源；`confirmReceiveOrder` 校验当前用户归属及已发货状态。
- `mall-portal/src/main/java/com/macro/mall/portal/service/OmsPortalOrderService.java`：创建、付款、取消等方法声明事务边界。
- `mall-portal/src/main/java/com/macro/mall/portal/component/CancelOrderSender.java`：发送带 TTL 的订单取消消息。
- `mall-portal/src/main/java/com/macro/mall/portal/component/CancelOrderReceiver.java`：接收消息后调用同一个取消服务。
- `mall-portal/src/main/java/com/macro/mall/portal/component/OrderTimeOutCancelTask.java`：定义每 10 分钟扫描超时订单，但该固定提交中 `@Component` 被注释，因此不能声称定时补偿默认已运行。
- `mall-portal/src/main/resources/dao/PortalOrderDao.xml`：超时查询限定 `status=0`；批量更新订单状态及库存释放 SQL 未携带旧状态条件。源码中的事务本身不足以证明重复付款和付款／取消竞态安全。

## ChargeHub 取舍与对应实现

| 取舍 | 原因及 ChargeHub 对应 |
| --- | --- |
| 采用设备事实时间与接收时间分离、归属校验、失败留证 | `SessionService.ingest` 保留原始事件，检查 device/connector/session 关系；`DeviceEventRejected` 提交拒绝证据；冲突写审计后进入 REVIEW。设备返回成功只代表通信结果，STOPPED 完整事实才可结算。 |
| 采用重发返回同一业务身份的原则，增强为数据库约束和稳定命令 ID | `device_event` 按设备事件号去重，冲突内容留证；`device_command` 持久化请求，`requestStop` 复用已有 commandId，`WorkflowWorker` 结果未知后查设备。不能依赖单进程锁实现跨实例幂等，也不能把网络超时解释为设备未启动。 |
| 采用资源预留／确认／释放的生命周期 | mall 的库存／券／积分对应 ChargeHub 的连接器／用户活动订单／会员权益／券。ChargeHub 在同一事务中锁用户、订单及会话，最终只释放仍绑定当前订单的资源，避免旧会话回包释放新订单。 |
| 采用补偿扫描调用同一应用服务的方向 | `WorkflowWorker` 查询不确定会话并重放事件、恢复退款和尝试结算；此项是 ChargeHub 本地代码检查结果，不是对 SteVe 完整重试机制的推断。 |
| 拒绝人工推测结束、表值回退后选择新单调段、无交易号按时间归账 | 充电交易计费需要可追溯的完整序列和实际费率边界。`SessionService.finalizeLocked` 缺序列或边界时保留 REVIEW；`segments` 仅按实际样本计费，不能用下一笔起点或插值制造正式账单。 |
| 拒绝 double 与向上取整用于财务边界 | ChargeHub 使用整数 Wh、整数分和明确的计费舍入；能源单位转换需要在协议边界显式校验。当前适配是签名 HTTP 模拟设备，尚不是 OCPP 网关。 |
| 拒绝停止写入失败仍将连接器视为空闲，以及直接按主键覆盖订单状态 | 应保留未知物理状态并继续停止／查询；付款、取消、退款的业务转换须由本地锁、状态前置条件和唯一业务编号保护。mall 的示例更新方式不能直接作为 ChargeHub 并发安全证明。 |
| 不引入第三方应用源码 | SteVe GPL 源码只用于独立阅读及概念核对；mall 同样没有被复制。本文不改变 ChargeHub 的许可证，不新增第三方运行依赖。 |

本次范围是源码复核，未构建、运行或认证上述两个项目；未审计完整 OCPP 通信重试、TLS、固件、设备离线计费或 mall 的整个支付系统。ChargeHub 当前的签名 HTTP 设备契约与 OCPP 标准接入应明确区分，不能宣称通过 OCPP 一致性认证。
