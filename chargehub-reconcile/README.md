# 财务对账与恢复

实现基于独立渠道事实、不可变资金流水和业务订单三个来源核对。渠道查询、导出与退款发送在数据库事务外完成；查单成功后通过 `FinancialFactHandler.acceptVerified` 进入订单应用服务，不能绕过支付、权益或取消状态校验。`cash_entry` 与正式账单没有人工修改入口。

来源审阅见 [payment-review.md](../docs/references/payment-review.md)：实读 `YunaiV/ruoyi-vue-pro` 的 `master` 提交 `ac022b15a094cf9cf82903d429b9729e72309da5`，许可证 MIT。采用稳定业务号、状态条件更新、未知结果查原单、逐次通知证据；没有复制其源码或采用仅检查在途笔数的退款额度控制。

## 权限与 HTTP 接口

以下接口只允许服务端认证的 `FINANCE` 或 `ADMIN`。所有写请求需要 `Idempotency-Key`，8–64 位字母、数字、点、下划线、冒号或短横线。相同操作人、作用域和 key 对应不同参数返回 409；执行中的同 key 返回 409；已完成请求重放返回原结果。财务操作的去重和租约单独持久化，不用一个长事务包住外部调用。

| 接口 | 输入与用途 |
| --- | --- |
| `POST /v1/admin/reconciliations` | multipart：`file`、`channel=MOCK`、`businessDate`、`billType=PAYMENT\|REFUND`、`sourceTimezone=Asia/Shanghai\|UTC`、`versionNo`、可选 `reason`。严格 UTF-8，原文件最大 10MiB；未提供 reason 时使用固定导入动作说明。 |
| `GET /v1/admin/reconciliation-items` | `cursor`、`limit=1..100`、可选 `status=OPEN\|PENDING_CONFIRMATION\|RESOLVED`。 |
| `GET /v1/admin/finance/reconciliation-items/{itemId}` | 原始差异及追加的逐次查单、修复和处置证据。 |
| `GET /v1/admin/finance/reconciliations/{batchId}` | 批次元数据与证据；原 CSV 留存在数据库，响应不重复输出完整文件。 |
| `POST /v1/admin/finance/reconciliations/{batchId}/recover` | JSON `{ "reason": "独立查单核验" }`，逐项查询、精确核对后补录；相同文件导入不再补录。 |
| `POST /v1/admin/finance/reconciliation-items/{itemId}/disposition` | JSON `{ "action": "KEEP_OPEN", "reason": "继续调查" }` 或 `{ "action": "CONFIRM_SUPERSEDED", "replacementBatchId": 2, "reason": "采用重新导出的完整账单" }`。 |
| `POST /v1/admin/refunds/{refundNo}/retry` | 与 `/v1/admin/finance/refunds/{refundNo}/retry` 同义。JSON reason；先查原单，成功直接收敛，未知保留原预占，只有明确可重试失败允许原号重新预占。 |
| `POST /v1/admin/finance/refunds/{refundNo}/recover` | JSON reason；只查单和同步已验证事实，不另造退款号。 |

其他角色返回 403。原因不能为空且最大 255 字。支付渠道和设备通知仍使用独立集成认证，不使用上述人员接口。

## 对账状态与自动修复

导入先校验全文件元数据、唯一渠道业务号、平台号、金额、币种与业务日期。原 CSV、哈希和每条差异的渠道快照保留，不能以旧批次哈希掩盖新金额。匹配是双向的：渠道有而本地无、本地有而渠道文件无、金额/币种/渠道号/发生时间不同均分别留痕。

导入事务同时持久化恢复任务。`worker` profile 中的 `FinanceRecoveryWorker` 用短事务、`SKIP LOCKED` 和递增租约 token 领取；外部查询在提交领取事务之后发生。每轮处理最多 100 项，取得进展时继续分批，连续无进展最多重试 6 次，随后保留可见 `DEAD` 任务并由人员带原因重新核查。人工 recover 不受后台失败次数限制。

`CHANNEL_ONLY` 只有在独立查单的业务关系、渠道号、金额、币种、UTC 发生时间和原账单行均一致时，才通过统一事实入口补录。来源为查询的新证据使用独立 query 事件标识，原错误通知及其哈希冲突证据不会被覆盖。结果仍受本地业务资格控制：取消或超期资金记录后退款，不重新启动服务。

`LOCAL_ONLY` 保持 `PENDING_CONFIRMATION`，因为文件中缺少一行不能证明本地实收不存在。查询发现渠道也成功时，原文件仍不完整。需要重新导出完整渠道账单，导入同渠道/日期/类型/时区的更高版本，调用 recover 验证该文件与独立渠道完整导出哈希相同，且该批次为 `MATCHED`，才能引用它执行 `CONFIRM_SUPERSEDED`。处置再次逐项核对本地不可变流水的业务号、渠道号、金额、币种和发生时间，不修改双方原金额。

`KEEP_OPEN` 只添加原因和审阅证据。没有“忽略差异后强行闭合”或“按本地金额修改渠道文件”的入口。没有本地业务关系、查无此单、查询超时、金额不符或最终资金矛盾时继续挂起。原错误通知导致的 `PAYMENT_MISMATCH`/`REFUND_MISMATCH` 不会通过普通对账处置自动清除；需要单独审查来源，当前没有单人解除这类风控挂起的接口。

## 退款状态与额度

可退额度始终为 `captured_fen - refund_reserved_fen - refund_success_fen`。创建与重新预占都锁定支付行；`UNKNOWN`、`INIT`、`PROCESSING` 保留预占，超时不等于失败。成功原子转移预占到成功额并写一笔不可变 REFUND。

渠道 `FAILED + retryable=false` 映射本地 `FAILED_FINAL`：释放原预占但禁止换号或原号再试。渠道 `FAILED + retryable=true` 映射 `FAILED_RETRYABLE`：释放一次；财务再次独立查单确认后，在原退款号上重新检查可退额度、重新预占、增加 `retry_attempt`，再调用 `/mock-gateway/v1/refunds/retry`。该接口仍使用相同 `outRefundNo` 和金额。失败通知携带 `attempt`，旧 attempt 不能释放新预占；成功一旦写入不会回退。

模拟器故障 `refundRetryableFailure`、`rejectRefund`、`dropResponseAfterCommit`、`dropNotification`、`badPaymentNotificationAmountFen` 和 `badPaymentNotificationCurrency` 只用于隔离开发网络。错误通知的金额/币种不改变渠道自身交易或账单。控制路由需要 `CHARGEHUB_CONTROL_KEY`；请求和回调需要渠道签名。

## 数据与运行配置

Flyway `V4__finance_operations.sql` 增加 `finance_operation`、`finance_evidence`、`reconcile_recovery_task`、原始渠道快照/文件与退款 attempt。原有数据及不可变流水不改写。对外连接沿用 `CHARGEHUB_PAYMENT_URL`、`CHARGEHUB_PAYMENT_KEY`、`CHARGEHUB_CALLBACK_URL`；连接/读超时各 2 秒，响应有大小限制，不关闭 TLS 校验。

后台任务间隔由 `chargehub.finance-recovery-delay-ms` 配置，默认 5000ms。应用计划任务线程池至少配置 4 个线程，避免批量财务查单阻塞支付或设备恢复。模拟服务数据库必须与平台 MySQL 分开。

真实 MySQL 测试使用独立新库：`CHARGEHUB_FINANCE_TEST_JDBC_URL`、`CHARGEHUB_FINANCE_TEST_DB_USER`、`CHARGEHUB_FINANCE_TEST_DB_PASSWORD`，库名必须以 `_test` 结束且启动时为空；测试拒绝擦除已有数据。`FinanceInvariantIntegrationTest` 覆盖 A15/A16/A18/A19/A33/A34/A35/A43/A44/A45，包括实际 InnoDB 并发、取消竞争、金额差异不能闭合、按原号恢复及审计证据。测试中的渠道响应是明确标注的独立受控事实，不声称来自真实支付机构。实际执行结果以 `.local/reports/finance-integration-latest.log` 与项目验收证据为准。
