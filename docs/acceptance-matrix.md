# A01–A47 验收映射与证据状态

本表对应 `docs/spec/06-acceptance.md` 的全部 47 项，记录真实测试入口与证据边界。源码中存在测试不等于验收通过。当前工作区正在增加迁移、Redis、消息和运营功能；早先 `docs/evidence/` 中的通过结果保留为历史证据，不能替代当前候选版本回归。最终应由同一候选源码指纹/镜像、固定依赖版本的完整执行报告更新状态。

状态含义：**待验证**表示已有入口，尚未确认本轮相应场景执行成功；**待回归（历史 PASS）**表示旧基线有实际成功证据、当前变更后须重跑；**部分验证**表示列明的子项有证据，完整场景仍未通过。跳过、未触发 CI、仅 Broker 连通或仅测试工厂断开均不当作完整故障验收 PASS。

测试类缩写均位于 `chargehub-app/src/test/java/com/chargehub/app/`：

- `W`：`WorkflowMysqlTest`，真实 MySQL 迁移、资源竞争、资金及租约。
- `B`：`BenefitConcurrencyTest.inventoryOwnershipQuotaAndExpiryRemainConsistentUnderConcurrency`，真实 MySQL 1000 用户、库存、同用户限领及权益生命周期。
- `C`：`CatalogMysqlRedisTest`，真实 MySQL 与独立 Redis 测试连接；末项才断开 Redis。
- `S`：`SessionAcceptanceTest`，真实 MySQL 设备会话与证据恢复。
- `F`：`FinanceInvariantIntegrationTest.financialFaultsConcurrencyAndAuditedRecoveryPreserveImmutableMoney`，真实 MySQL、独立测试渠道事实、财务回查与审计。
- `R`：`ReconciliationMysqlTest.bidirectionalComparisonDedupAndConcurrentImportsKeepMoneyImmutable`。
- `M`：`MessageMysqlTest.inboxProofRollbackFencingDedupTamperAndAuditedReplayUseOriginalEvent`。

| 编号 | 要观察的完整结果 | 实际入口与精确方法/场景 | 当前状态与可核对证据 |
| --- | --- | --- | --- |
| A01 | 空库迁移和再次启动无重复数据，所有当前迁移可验证 | `W.realMySqlMigrationAndUtcSessionAreRepeatable`；`scripts/mysql-integration.py` | 待回归（历史 PASS）：`docs/evidence/java-tests.json`、`mysql-integration.json` 仅证明旧 V1/V2；当前 V3 及之后需统一验证 |
| A02 | 他人读单/停单被拒，设备无指令 | `scripts/acceptance.py`：`A02 Another user cannot read order`、`A02 Another user cannot stop order` | 待回归（历史 PASS）：`docs/evidence/acceptance.json` 有两项 404 及 WAIT_PAY 保持证据 |
| A03 | 缺口/重叠不能发布有效费率 | `C.A03_A04_publicationCoverageFrozenBillingAndCommitOnlyCacheInvalidation`；`TariffRulesTest.rejectsGapsOverlapsAndMissingEdgesAtPublication` | 待验证真实集成；历史单元通过见 `docs/evidence/java-tests.json`，重叠草稿在创建阶段即拒绝 |
| A04 | 发布新价格后旧单仍按旧快照正式出账 | `C.A03_A04_publicationCoverageFrozenBillingAndCommitOnlyCacheInvalidation` | 待验证；断言旧单电费 1600 / 应付 2600，同时展示新版本为 4 |
| A05 | 20kWh 会员加券应付 2100、退款 7900 | `W.memberCouponDuplicateMoneyAndOutOfOrderMeteringCloseAt2100`；`scripts/demo.py` | 待回归（历史 PASS）：`docs/evidence/demo.json`、`java-tests.json` |
| A06 | 跨费率/午夜按冻结价与实际边界表值分段 | `SessionServiceTest.exactTariffBoundaryUsesCumulativeDifferencesAndFrozenRates`、`midnightRequiresAnExplicitDayBoundaryEvenWithTheSamePrice`；`S.A30_monthBoundaryConsumesTheReservedMonthAndUsesRealMidnightMeter` | 待回归真实集成；历史单元通过，`docs/evidence/faults.json` 另有跨费率原样补传证据 |
| A07 | 1000 人竞争 100 张，恰好发行 100 | `B` 的 1000 个用户、24 并发线程争抢部分 | 待回归（历史 PASS）：`docs/evidence/java-tests.json` 的 B 未跳过；`docs/validation.md` 明确记录成功 100 |
| A08 | 同用户重复并发领券只发一张/只扣一次库存 | `B` 的同用户 32 并发部分 | 待回归（历史 PASS）：同上；强度超过基线 20 次 |
| A09 | 同用户并发建两单最多一单绑定 | `C.A09_A10_A11_A12_A13_resourceContentionIdempotencyAndAllOrNothingBenefits`；`W.sameKeyAndResourceRacesPreserveOneBindingAndRollback` | 待回归（历史 PASS）：旧 W 5 项报告成功；本轮 C 独立断言待运行 |
| A10 | 两人同枪竞争，失败方权益和用户绑定回滚 | C 的 A09–A13 方法；W 的资源竞争方法 | 待回归（历史 PASS）；C 新增两张真实用户券 LOCKED/AVAILABLE 和会员额度核对，待运行 |
| A11 | 同 Key 同请求返回同一订单、无重复副作用 | C 的 A09–A13 方法；`scripts/acceptance.py` 对应 A11 | 待回归（历史 PASS）：`docs/evidence/acceptance.json` 有原订单号 |
| A12 | 同 Key 换枪或换券返回 409 | C 的 A09–A13 方法；`scripts/acceptance.py` 对应 A12 | 待回归（历史 PASS）：历史 HTTP 覆盖换枪；C 新增换券断言待运行 |
| A13 | 锁券后 SQL 故障，订单/枪/额度/券/Outbox/幂等记录全回滚 | C 的 A09–A13 方法中 `rollback-benefits` 注入 | 待验证；异常发生在创建及 Outbox 写入后，检查全部本地事实回滚 |
| A14 | 同通知重发 20 次只入账、启动一次 | W 的会员计费方法；`scripts/acceptance.py` 对应 A14 | 待回归（历史 PASS）：`docs/evidence/acceptance.json` 保存 duplicateNotificationCount=20、单会话及资金净额 |
| A15 | 多通知 ID 同渠道交易不重复入账 | `F` 的 A15/A16 部分；W 的会员计费方法 | **PASS（独立域）**：`.local/reports/finance-integration-latest.log`，真实 MySQL F 逐场景断言；整栈候选仍需统一回归 |
| A16 | 已签名但金额/币种错误保留差异，不启动 | `F` 的 A15/A16 部分；`PaymentServiceTest.mismatchedAmountAndFractionalAmountCannotCaptureMoney` | **PASS（独立域）**：同财务 10/10 报告，错误金额/币种、DIFF 及不启动均断言；HTTP 签名链另行回归 |
| A17 | 错签名、冒用设备/会话被拒且不污染正常会话 | `HmacTest` 具名签名/归属/重放方法；`S.eventIdConflictingPayloadIsAppendOnlyAndRejectedForeignDeviceCannotPoisonBilling`；`scripts/acceptance.py` 未签名回调 | 部分验证：历史未签名 HTTP 401；本轮轮换密钥与异设备污染防护待统一回归 |
| A18 | 支付到期与成功并发争用，捕获事实不丢、超期不启动 | `F` 的 A18 并发部分；`W.canceledAndDeadlinePaymentsAreCapturedAndFullyRefundedWithoutStart`；模拟器 `test_capture_close_race_has_single_channel_winner` | **PASS（独立域）**：F 真实用户/订单行并发到期与付款，精确截止全退且不启动；模拟渠道竞争另有历史证据 |
| A19 | 取消后成功付款仍记 CAPTURE 并全退、不启动 | F 的 A19；W 的取消晚到付款方法 | **PASS（独立域）**：F 断言取消后一次 CAPTURE、一笔全退且 CANCELED；整栈晚到通知待统一回归 |
| A20 | 启动响应超时但设备运行，保占用并恢复 CHARGING | `S.A20_unknownStartDoesNotRefundAndActualStartedRestoresOwnership`；`scripts/faults.py --scenario start-response-lost` | 待回归（历史 PASS）：`docs/evidence/faults.json` 的 start-response-lost；新证据恢复域待运行 |
| A21 | 明确拒绝并权威无会话后取消/全退/释放 | `S.A21_authoritativeNotStartedCancelsAndReservesOneFullRefund`；`test_device_acceptance.test_explicit_start_rejection_is_durable_and_stable_after_fault_disabled` | 部分验证：`.local/reports/device-acceptance.log` 中模拟器 10 项通过；真实 MySQL 全流程待验证 |
| A22 | 同 START commandId 只创建一会话 | `simulators/test_simulators.py::test_demo_device_facts_stable_session_and_query_latest`；`S.A22_duplicateStartedFactKeepsOneSessionAndOneMeterSample` | 待回归（历史模拟器 PASS）；STARTED 事实去重与 commandId 幂等是两个独立断言，均需保留 |
| A23 | 重复/乱序计量留原始事实、不重复累加 | `S.A23_outOfOrderAndDuplicateSamplesNeverAccumulateEnergyTwice`；W 乱序补齐方法 | 待回归（历史 PASS）：旧 W 真实 MySQL 乱序补齐成功 |
| A24 | 同序号不同电量可见冲突，不能覆盖原值 | `S.A24_conflictingSequenceNeedsIndependentEvidenceDecisionAndNeverOverwritesFacts` | 待验证；含独立证据审批及禁止原地改计量 |
| A25 | 缺边界/缺 final_seq 样本不出账，补传后恢复 | `S.A25_confirmedStopWithGapReleasesSafeResourcesAndLateReplayCannotReleaseNewOrder`；`scripts/faults.py --scenario metering-boundary-lost` | 待回归（历史 PASS）：`docs/evidence/faults.json` 实际观察 REVIEW、409、原样补传后出账 |
| A26 | 电表倒退进入 REVIEW，无负费用 | `S.A26_meterRollbackRetainsEvidenceAndCorrectedDeviceFactNeedsResolution`；`SessionServiceTest.gapsRollbackAndReversedTimesCannotGenerateBills` | 待验证真实集成；历史纯计量单元通过不能替代冲突恢复全流程 |
| A27 | 停止成功但回执丢失，权威查会话后恢复 | `S.A27_A28_lostStopAcknowledgementReplayFinalizesExactlyOnce`；`test_device_acceptance.test_lost_stop_ack_and_duplicates_preserve_one_real_stop_and_no_invented_energy` | 部分验证：新模拟器日志通过；平台真实 MySQL 补传/结算待验证 |
| A28 | 结束通知/出账任务重复仅一账单、权益一次 | S 的 A27/A28 方法；W 会员计费方法最后重复 tryFinalize | 待回归（历史 PASS）：旧 W 单账单、券 USED、会员用一次 |
| A29 | 服务费不足券门槛释放券，并按当前有效期返回 AVAILABLE/EXPIRED | `S.A29_couponBelowThresholdIsReleasedAsExpiredAtSettlement`；`C.A29_A30_A31_expiryFrozenMonthZeroDiscountAndPublishedTemplateProtection` | 待验证正式账单场景；历史 B 有取消过期释放，不能等同于新门槛结算测试 |
| A30 | 月末预占、次月结束只扣原月份 | `S.A30_monthBoundaryConsumesTheReservedMonthAndUsesRealMidnightMeter`；C 的 A29–A31 方法；B 月界部分 | 待回归（历史 B PASS）；新增实际午夜样本和正式账单待验证 |
| A31 | 实际会员优惠为零释放、不占次数 | `S.A31_zeroMemberDiscountReleasesReservedQuota`；C 的 A29–A31 方法；BillingCalculator V05 | 待验证真实整单生命周期；历史 V05 金额向量通过 |
| A32 | 无电量/确认未服务应付零、全退、权益释放 | `S.A32_zeroEnergyAtTheSameMillisecondRefundsInFullAndUsesNoBenefits`；S 的 A21；BillingCalculator V05 | 待验证同毫秒真实结算；历史零电量纯函数通过 |
| A33 | 退款成功但响应/通知丢失，UNKNOWN 占额度、原号查单恢复 | `F` 的 A33/A35；`scripts/faults.py --scenario refund-response-and-notification-lost`；模拟器 `test_lost_refund_response_and_notification_still_query_same_success` | **PASS（独立域）**：F 实际保留 7900 退款预占并按原号独立查单恢复；历史 `docs/evidence/faults.json` 另有真实 HTTP 故障证据 |
| A34 | 并发同退款结果只生成一条 REFUND 并转移额度一次 | `F` 的 12 并发退款回调；W 会员计费方法重复退款通知 | **PASS（独立域）**：F 的 12 并发退款回调，仅一笔 REFUND、成功额及预占转移核对；报告同上 |
| A35 | 超预付款退款被拒，不超退 | `F` 的 A33/A35；`PaymentServiceTest.refundReasonReservesOnceAndUnknownContinuesToHoldCapacity` | **PASS（独立域）**：F 保留未知退款占用后再次超额请求返回 REFUND_LIMIT，真实 InnoDB 无超退 |
| A36 | 实际费用超预付保留原账单，SHORTFALL 可见、欠款阻断下单 | `S.A36_actualOvershootIsNeverTruncatedAndDebtBlocksFurtherCharging`；BillingCalculator V07 | 待验证真实债务整单；历史 V07 金额向量通过 |
| A37 | Worker 领取后崩溃，租约到期另一进程接管 | `W.leaseTakeoverRejectsStaleAcknowledgement`；`M`；`scripts/mq-faults.py` 崩溃阶段 | 部分验证：历史 W 有 DB 租约过期接管；真实进程崩溃/消息模式待验证 |
| A38 | 过期租约旧进程不能回写，外部业务号去重 | W 的 leaseTakeover；M 的 proof/fencing/inbox 原子处理 | 待回归（历史 DB fencing PASS）；本轮消息 proof 与并发工作进程待验证 |
| A39 | Redis 清空/停机后降级，DB 库存/额度/退款不越界 | `C.A39_redisDisconnectCannotPreventDatabaseReadsOrdersClaimsOrRefundIntents`；`CatalogCacheTest`；`scripts/redis-acceptance.py` 专用容器停机/恢复 | 待验证；C 仅停止测试连接工厂，不冒充服务端停机；完整容器门禁另报 `.local/reports/redis.json` |
| A40 | MQ 发出后、本地标记前杀进程，重复投递无重复效果 | `scripts/mq-faults.py`；`M` 的 inbox 原子与 proof 子项 | 待验证；`.local/reports/mq-native-smoke.json` 仅证明 5.2.2 客户端/5.5.0 Broker 真收发与 ACK，不证明崩溃故障通过 |
| A41 | 消费 DB 提交后、ACK 前杀进程，重投效果不重复 | `scripts/mq-faults.py`；`M` 的 inbox dedup 子项 | 待验证，必须保留 brokerMessageId、eventId、重投次数及 DB 唯一效果证据 |
| A42 | 持续失败入死信，授权人工重放保留原事件号/原因/审计 | `scripts/mq-faults.py`；`M` 的 audited replay 子项 | 待验证，数据库 READY/DEAD 测试与 Broker 死信需分别验收 |
| A43 | 渠道有成功交易、本地通知缺失，独立查单后统一补录 | F 的 A43/A44；`ReconciliationMysqlTest` | **PASS（独立域）**：F 的渠道账单缺通知经受控独立 gateway 查询后统一入口补录，finance_evidence 已断言；非真实支付机构接入 |
| A44 | 相同渠道账单重复/并发导入只一批、不重复补录 | `R` 的并发 12 导入；F 的幂等导入与 recoverBatch | **PASS（独立域）**：本轮原 R 与 F 均执行，12 并发同批次、相同幂等导入和单次补录已验证；报告同上 |
| A45 | 对账金额不符保留双方证据，DIFF 阻止财务闭合 | F 的 A45；R 的双向差异/冲突方法 | **PASS（独立域）**：F 保留渠道 9999 与本地 10000 双方证据，原现金不变且 financial_status=DIFF；报告同上 |
| A46 | 应用切回旧镜像、迁移兼容，数据库及模拟器事实可恢复 | `scripts/backup-restore-check.py`；`scripts/deployment/rollback.py` | 部分验证：`docs/evidence/backup-restore.json` 仅证明 MySQL 14 表恢复/零违规；旧镜像切换、当前迁移和 SQLite/模拟器卷恢复待新报告 `.local/reports/rollback.json` |
| A47 | 50RPS×15分钟、100会话×30分钟、百万历史、索引计划和P95目标 | `scripts/performance/run.py --phase all --history 1000000`；`scripts/performance/load.py` | 待验证；须使用固定 SHA 镜像和限制资源，报告 `.local/performance/<runId>/run.json`、逐请求数据、索引计划及资源快照；短 smoke 不能代替持续门禁 |


本轮已核对的独立财务域证据：`.local/reports/finance-integration-latest.log` 的 10/10 成功包含 PaymentServiceTest 8 项、F 1 项和 R 1 项；F 内含 A15/A16/A18/A19/A33/A34/A35/A43/A44/A45 的实际断言，使用真实 MySQL 8.4 与隔离空库、加载 V2–V7 模块迁移、受控独立渠道适配器。该结果不表示完整 Compose/HTTP/MQ 候选已通过。其余新增域未取得执行证据前继续标待验证。

本轮统一运行前仍须确认所有环境变量门禁确实启用。`scripts/mysql-integration.py` 为各域创建独立 `_test` 库及专用 Redis，JUnit 报告应检查 **skipped=0**。本表不能用单个命令的 exitCode 代替逐项核对；当前已有失败/未完成的运行记录必须保留，不能用早先的 `docs/evidence/java-tests.json` 覆盖。

更新为 PASS 时至少记录：候选 Git SHA 或源码指纹、构建镜像 ID、实际执行时间、MySQL/Redis/RocketMQ 版本、具名测试/故障步骤、预期及实际值、原始报告位置。模拟支付/设备数据应始终标注模拟环境。当前云端 CI 是否触发是独立事实，提供 workflow 文件不表示远端 CI 已执行。
