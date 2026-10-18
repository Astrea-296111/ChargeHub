# 独立模拟服务与事件处理契约

## 一 接入拓扑与事实归属

后端计划使用8080端口，支付模拟器使用18081，设备模拟器使用18082。模拟器使用自己的SQLite文件或独立数据库保存事实，不连接ChargeHub业务库。服务间HTTP均设置连接、读取超时和响应大小限制，默认2秒读取超时只表示本次调用未取得结果。

开发模式可提供独立的模拟操作台，操作付款成功、设备断线和通知丢失。该操作台必须局限dev/test网络，不能作为普通用户可调用的支付成功接口。仓库示例配置不带真实商户、真实车辆或真实支付密钥。

## 二 统一签名

服务间约定头：X-Key-Id、X-Timestamp、X-Nonce、X-Signature。时间戳为UTC Unix秒，nonce为新的UUID。签名原文按下面五段用换行连接，最后不加换行：

```text
UPPERCASE_HTTP_METHOD
PATH_WITHOUT_QUERY
UNIX_SECONDS
NONCE
LOWERCASE_HEX_SHA256_OF_RAW_REQUEST_BODY
```

X-Signature为该原文的HMAC SHA256结果的Base64编码。空请求体按空字节计算摘要。计划中的服务间请求均用POST与JSON，避免查询参数签名歧义。验签同时核对keyId到设备或渠道的归属、5分钟窗口和nonce；重试沿用业务编号，重新生成签名头。HTTP重试不能更换commandId、payNo、refundNo或eventId。

GET业务查询使用用户Bearer令牌；上述签名不适用于用户登录。回调body中的occurredAt是业务发生时间，不作为签名窗口依据。记录非法请求时不回显密钥和完整认证头。

## 三 支付模拟器

所有端点位于模拟器进程，返回结构为code、message、data、requestId。业务请求都支持同业务编号与同参数重试；同编号不同金额返回409。

平台payNo映射到渠道outTradeNo，平台refundNo映射到渠道outRefundNo；providerTradeNo和providerRefundNo由渠道独立生成。平台不能把自己的业务编号当作渠道成功证据。

| 端点 | 请求核心字段 | 结果与不变量 |
| --- | --- | --- |
| POST /mock-gateway/v1/payments | outTradeNo UUID、amountFen 10000、currency CNY、expiresAt、notificationUrl | 创建PENDING，返回providerTradeNo与checkoutUrl；重复编号返回原交易 |
| POST /mock-gateway/v1/payments/query | outTradeNo | 返回PENDING、SUCCESS或CLOSED及渠道金额、发生时间 |
| POST /mock-gateway/v1/payments/close | outTradeNo | PENDING原子变CLOSED；SUCCESS返回已支付事实，不伪装关闭成功 |
| POST /mock-gateway/v1/refunds | outRefundNo UUID、outTradeNo、amountFen、currency | 原子检查渠道可退额度，返回PROCESSING或SUCCESS |
| POST /mock-gateway/v1/refunds/query | outRefundNo | 返回原退款事实，未知编号返回NOT_FOUND |
| POST /mock-gateway/v1/bills/export | businessDate、billType PAYMENT或REFUND、timezone | 返回独立渠道账单内容与哈希，不能从平台业务表导出 |

开发操作台另有POST /dev/payments/{outTradeNo}/capture，只有dev控制密钥可以调用。它先原子保存渠道SUCCESS，再按故障配置决定是否响应、是否通知。关闭后的交易不能再capture；模拟器的支付成功和关闭竞争必须在自己的存储中决定胜者。

expiresAt等于平台paymentDeadline，模拟器通常拒绝截止后付款。平台仍独立校验渠道付款发生时间和启动窗口，防止渠道异常或延迟通知造成超期开充；迟到资金只补录并按未发生服务的规则退款。

退款先锁渠道交易检查累计退款，按outRefundNo唯一保存。可注入“渠道成功但HTTP响应丢失”“渠道成功但通知丢失”“暂时失败”和“确定性拒绝”。通知丢失不能删除渠道事实。

### 通知字段

type为PAYMENT_SUCCESS、PAYMENT_CLOSED、REFUND_SUCCESS或REFUND_FAILED；包含channelEventId、payNo、providerBizNo、amountFen、currency、occurredAt。退款通知必须包含refundNo。通知请求示例：

```json
{
  "channelEventId": "mock-event-0001",
  "type": "PAYMENT_SUCCESS",
  "payNo": "00000000-0000-4000-8000-000000000001",
  "providerBizNo": "mock-pay-0001",
  "amountFen": 10000,
  "currency": "CNY",
  "occurredAt": "2026-10-18T07:00:00Z"
}
```

平台按通知类型使用payNo或refundNo查本地关系，客户端提交的orderNo不作为资金归属依据。通知失败按原channelEventId重试；超过演示重试次数后保留待发送记录，允许开发操作台再次投递。

## 四 设备模拟器

| 端点 | 请求核心字段 | 结果与不变量 |
| --- | --- | --- |
| POST /mock-devices/v1/commands/start | commandId、orderNo、sessionNo、deviceId、connectorId、prepaidFen、tariffSnapshot、expiresAt | 同commandId同内容重试返回原结果；枪占用或过期明确拒绝 |
| POST /mock-devices/v1/commands/stop | commandId、sessionNo、deviceId、connectorId | 同会话停止可重复执行，不创建新会话 |
| POST /mock-devices/v1/sessions/query | deviceId、connectorId、sessionNo | 返回NOT_STARTED、CHARGING、STOPPED、UNKNOWN及权威会话信息 |

设备模拟器以connectorId串行处理开始和停止，保存会话开始表值、累计表值、连续seqNo与最终finalSeq。STARTED固定seqNo为0；STARTED、METER与STOPPED都携带累计Wh。STOPPED的finalSeq等于该结束事件的seqNo，平台要求从0到finalSeq的计量序列完整且所有费率边界存在。

即使commandId不同，同一个sessionNo也不得产生两个会话；相同会话关联不同订单应拒绝并记录冲突。QUERY_SESSION可以重复查询最新事实，不能因重复query的commandId而永久返回首次查询的旧快照。

设备模拟时间由可控Clock驱动，可用加速时钟执行跨日、跨月案例，但上报的发生时间仍为合法UTC。设备按优惠前费用计算剩余预付款，在下一次采样可能越界前结束会话，保留限额停止的边界电量；平台同时具备服务端止损任务。实际超额注入场景用于验证SHORTFALL处理，不伪装正常结束已付清。

### 设备事件示例

```json
{
  "eventId": "00000000-0000-4000-8000-000000000010",
  "deviceId": "sim-device-01",
  "connectorId": "1",
  "eventType": "STOPPED",
  "sessionNo": "00000000-0000-4000-8000-000000000020",
  "seqNo": 5,
  "meterWh": 120000,
  "finalSeq": 5,
  "occurredAt": "2026-10-18T07:30:00Z",
  "sampleKind": "END"
}
```

事件在05-events.schema.json通过结构验证后，还需通过业务校验。deviceId与connectorId归属、meterWh单调性、事件哈希冲突、费率边界完整性和状态转换不能仅由JSON Schema判断。

## 五 领域事件的消费者

| 事件 | 主要消费者 | 事务或外部调用边界 |
| --- | --- | --- |
| PaymentCreateRequested | 支付适配器 | 按payNo幂等创建渠道交易，结果持久化；超时查单 |
| PaymentCaptured | 订单流程 | 本地事务确认资格并创建启动或全退意图 |
| PaymentCloseRequested | 支付适配器 | 按payNo关单，已支付转统一成功入口 |
| ChargeStartRequested | 设备适配器 | 先检查订单仍允许启动，再按commandId发送；结果未知查会话 |
| ChargeStopRequested | 设备适配器 | 同一停止编号可重试，确认终止后补足计量 |
| SessionStopped | 计费模块 | 本地事务校验完整计量并生成账单与权益确认 |
| BillFinalized | 财务展示和通知 | 构建展示状态；退款意图已由出账事务保存，不重复创建 |
| RefundRequested | 支付适配器 | 按refundNo退款和查单，未知保持额度占用 |
| RefundSucceeded | 订单财务流程 | 本地事务核对现金净额，满足闭合条件才关账 |

PaymentCaptured可由资金事实事务发出，随后订单消费者创建启动意图；也可在同事务直接完成本地启动意图创建。首版选直接完成，事件用于审计或其他订阅，消费者重复执行必须检查状态与唯一约束。这两种实现不能同时各自无条件发送一条启动命令。

同理，出账事务直接持久化退款单与RefundRequested，BillFinalized不再次创建退款。事件schemaVersion为1；aggregateVersion属于具体聚合，不构成跨聚合全局时钟。没有依赖全局顺序的隐含流程。

外部调用消费者依赖渠道业务编号确保重复请求安全，consumer_inbox与本地结果同事务提交；不能在调用外部之前先记为消费完成。外部成功后本地提交前崩溃，重试外部会返回同一事实。永久业务错误进入可见失败处理，临时技术错误退避重试。

## 六 渠道CSV格式

编码UTF-8，首行为字段名，支付和退款使用相同字段结构但分别导出：

```csv
channel,bill_type,business_date,source_timezone,provider_biz_no,out_trade_no,out_refund_no,amount_fen,currency,occurred_at,status
MOCK,PAYMENT,2026-10-18,Asia/Shanghai,mock-pay-0001,00000000-0000-4000-8000-000000000001,,10000,CNY,2026-10-18T07:00:00Z,SUCCESS
```

支付行out_refund_no为空，退款行必须带原out_trade_no和out_refund_no。amount_fen必须为正整数分。批次导入核对所有行的渠道、账单类型和业务日期，并按source_timezone验证occurred_at归档日期；同文件不同金额不能用旧批次哈希掩盖。首版单文件上限10MiB，超过上限拒绝并要求分业务日期导出；文件大小限制同时配置在反向代理与应用层。

对账使用本地流水与渠道行的双向匹配，不能只遍历渠道文件遗漏“本地有渠道无”的记录。账单文件自身缺失或未完成时先标为批次不完整，不将缺失行自动当成资金不存在。

## 七 故障配置

开发配置至少包含dropResponseAfterCommit、dropNotification、duplicateNotificationCount、notificationDelayMs、deviceStartTimeoutButRunning、deviceStopAckLost、meterReorderWindow、dropBoundarySample、injectMeterRollback。故障配置与测试随机种子进入验收记录，默认全部关闭。能够复现问题的模拟器比随机返回成功更能形成可解释的项目经历。

## 八 种子数据与首个演示剧本

dev种子创建2个站点、每站3把枪、普通用户与会员用户各一名，以及独立的OPS、FINANCE、ADMIN账号。密码由环境注入，不能把演示密码作为生产默认值。会员用户当月有2次额度，券模板为服务费满500分减300分，发行100张，适用首版所有站点。并发测试另建隔离数据库生成1000个测试用户，不污染演示账单。

站点每日费率固定为00:00至08:00电费50服务费30，08:00至18:00电费80服务费50，18:00至22:00电费120服务费60，22:00至24:00电费50服务费30，单位均为分每kWh。版本发布前验证完整覆盖。

第一遍正常演示固定业务时间为2026年10月18日15:00至15:30上海时间。会员领券后预付10000分，设备从100000Wh开始，seq0开始，seq1至4依次上报104000、108000、112000、116000Wh，seq5停止上报120000Wh且finalSeq为5。发生时间等间隔6分钟，全部处于80加50的时段。结果必须为应付2100分、差额退款7900分、会员已用1次、券USED、渠道净收2100分。

第二遍使用新的用户或恢复隔离测试库，开启支付成功后丢失通知。先展示渠道SUCCESS而平台PENDING，再运行平台查单恢复，最终只生成一笔CAPTURE和一个设备会话。第三遍开启退款成功后响应和通知均丢失，展示UNKNOWN继续占用退款额度，再按原退款号查询恢复。演示视频和截图必须标注模拟环境，不把合成数据解释为真实车流。
