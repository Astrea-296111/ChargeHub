# A01–A47 验收映射与证据状态

本表对应 `docs/spec/06-acceptance.md` 的全部 47 项，更新日期为 **2026-10-19 UTC**。最终应用静态候选为 `9d61589bf96ab8cd0cd12b6de3fe3a936c6092a1`，镜像 `sha256:f02d5d40f034582d85637cc0c9ff1aa353b09489c0f4cdbd1dcdd1b2446cc714`；业务 Java、迁移及 POM 仍与已验证的 `302507165a4496ded25ac6ff0505a93869bec47c` 指纹一致。全量 Maven reactor 于 **2026-10-19 19:03:49（Asia/Shanghai；UTC 11:03:49）**结束：26 个 Java 测试类、128 项测试、0 失败、0 错误、0 跳过；其中 9 个真实依赖集成类共 38 项，另 90 项为单元/域测试。独立模拟器 21 项 Python 测试也已通过。8 个 Flyway 迁移在真实 MySQL 8.4 空库验证；Redis 测试使用专用 7.4 容器。

可复核的去敏汇总为 [`evidence/enterprise/java-tests.json`](evidence/enterprise/java-tests.json)、[`evidence/enterprise/mysql-integration.json`](evidence/enterprise/mysql-integration.json)、[`evidence/enterprise/python-tests.json`](evidence/enterprise/python-tests.json)，包含逐方法结果、JUnit XML 摘要哈希、日志哈希及候选源码指纹，不复制 JVM 属性、环境变量或私密日志。旧 `docs/evidence/` 根目录的记录仍保留为历史，未用旧报告覆盖新候选。运行镜像 ID 由整栈门禁另行记录。

远端 [9d61589 工作流](https://github.com/Astrea-296111/ChargeHub/actions/runs/39282899907) 的单元/镜像与五组后端任务共6组全部通过，见 [CI汇总](evidence/enterprise/github-ci-9d61589.json)。新增监控门禁、最终镜像无覆盖浏览器及严格响应契约复验仍待执行；A47尚未通过。

**PASS**均注明实际验证层：真实 InnoDB/本地 Tomcat HTTP、纯计费函数或独立模拟器契约；不将这些结果冒充 Compose 进程崩溃、真实 Broker 故障、Redis 服务端停机、完整应用回滚或性能目标通过。**部分验证**表示已完成的子项有证据，完整场景仍待验证；**待验证**表示对应门禁尚无当前候选成功证据。跳过、仅 Broker 连通和仅断开测试连接工厂不等于完整故障验收通过。

测试类缩写均位于 `chargehub-app/src/test/java/com/chargehub/app/`：

- `W`：`WorkflowMysqlTest`，真实 MySQL 迁移、资源竞争、资金及租约。
- `B`：`BenefitConcurrencyTest.inventoryOwnershipQuotaAndExpiryRemainConsistentUnderConcurrency`，真实 MySQL 1000 用户、库存、同用户限领及权益生命周期。
- `C`：`CatalogMysqlRedisTest`，真实 MySQL 与独立 Redis 测试连接；末项才断开 Redis。
- `S`：`SessionAcceptanceTest`，真实 MySQL 设备会话与证据恢复。
- `F`：`FinanceInvariantIntegrationTest.financialFaultsConcurrencyAndAuditedRecoveryPreserveImmutableMoney`，真实 MySQL、独立测试渠道事实、财务回查与审计。
- `R`：`ReconciliationMysqlTest.bidirectionalComparisonDedupAndConcurrentImportsKeepMoneyImmutable`。
- `M`：`MessageMysqlTest.inboxProofRollbackFencingDedupTamperAndAuditedReplayUseOriginalEvent`。
- `H`：`HttpAcceptanceTest`，实际 Tomcat 随机端口、JDK HTTP、BCrypt、HMAC 与 InnoDB，6 项未跳过。
- `J` / `P`：上述 enterprise Java / Python 逐方法汇总；所有本轮 PASS 可从这里定位类名、方法及原始报告摘要。
- `Q`：[`evidence/enterprise/mq.json`](evidence/enterprise/mq.json)，2026-10-19 UTC 11:13:16–11:21:31 单次全量真实 MQ 故障运行；原始 `.local/reports/mq.json` 的摘要保留在报告中，不复制私密 Worker 日志。Broker 5.5.0、原生客户端 5.2.2、MySQL 8.4，支付和设备为独立模拟器。部署候选 `a212a8b4ffb91444c1170478995786c1f2780d02`、镜像 `sha256:52bdaff20c1a57f6c0e518c5592779d64a31b202cf80d8a9ff36932dddc082a2`；业务 Java 与已通过 128 项测试的 `3025071` 一致。独立原生收发/正文比对/ACK 见 [`evidence/enterprise/mq-native-smoke.json`](evidence/enterprise/mq-native-smoke.json)。

| 编号 | 要观察的完整结果 | 实际入口与精确方法/场景 | 当前状态与可核对证据 |
| --- | --- | --- | --- |
| A01 | 空库迁移和再次启动无重复数据，所有当前迁移可验证 | `W.realMySqlMigrationAndUtcSessionAreRepeatable`；`scripts/mysql-integration.py` | **PASS（真实 MySQL）**：J 的 W 方法通过，V1–V8 空库迁移/validate/再次 migrate=0，连接 UTC；9 个隔离库见 enterprise/mysql-integration.json |
| A02 | 他人读单/停单被拒，设备无指令 | `H.anotherCustomersReadStopFundsAndBillAreInvisibleAndMeterOperationsAreForbidden`；历史 `scripts/acceptance.py` 对应 A02 | **PASS（真实 HTTP）**：`H.anotherCustomersReadStopFundsAndBillAreInvisibleAndMeterOperationsAreForbidden`，他人读/停/资金/账单不可见且无设备副作用，J 有具名结果 |
| A03 | 缺口/重叠不能发布有效费率 | `C.A03_A04_publicationCoverageFrozenBillingAndCommitOnlyCacheInvalidation`；`TariffRulesTest.rejectsGapsOverlapsAndMissingEdgesAtPublication` | **PASS（真实 MySQL）**：C 的 A03/A04 及 TariffRulesTest 均通过；缺一分钟不能发布，重叠草稿在创建阶段即拒绝 |
| A04 | 发布新价格后旧单仍按旧快照正式出账 | `C.A03_A04_publicationCoverageFrozenBillingAndCommitOnlyCacheInvalidation` | **PASS（真实 MySQL）**：C 断言展示已到版本 4，旧订单仍按冻结 80 分电价出账，电费 1600 / 应付 2600，且回滚不失效缓存 |
| A05 | 20kWh 会员加券应付 2100、退款 7900 | `W.memberCouponDuplicateMoneyAndOutOfOrderMeteringCloseAt2100`；`scripts/demo.py` | **PASS（真实 MySQL）**：W 正式出账电费 1600、服务费 1000、会员 200、券 300、应付 2100、退款 7900，重复结算不增加副作用 |
| A06 | 跨费率/午夜按冻结价与实际边界表值分段 | `SessionServiceTest.exactTariffBoundaryUsesCumulativeDifferencesAndFrozenRates`、`midnightRequiresAnExplicitDayBoundaryEvenWithTheSamePrice`；`S.A30_monthBoundaryConsumesTheReservedMonthAndUsesRealMidnightMeter` | **PASS（计费函数＋真实午夜结算）**：J 的跨费率/午夜函数与 S.A30 均通过，实际午夜样本生成分段账单；Compose 跨时段 HTTP 故障重跑另报 |
| A07 | 1000 人竞争 100 张，恰好发行 100 | `B` 的 1000 个用户、24 并发线程争抢部分 | **PASS（真实 MySQL 并发）**：B 未跳过，1000 用户争抢 100 张恰好发行 100，无库存/重复券违规 |
| A08 | 同用户重复并发领券只发一张/只扣一次库存 | `B` 的同用户 32 并发部分 | **PASS（真实 MySQL 并发）**：B 同用户 32 并发恰好一张用户券，issued_count 只增加一次，超过基线 20 次强度 |
| A09 | 同用户并发建两单最多一单绑定 | `C.A09_A10_A11_A12_A13_resourceContentionIdempotencyAndAllOrNothingBenefits`；`W.sameKeyAndResourceRacesPreserveOneBindingAndRollback` | **PASS（真实 MySQL 并发）**：C 与 W 均通过，同用户仅一单绑定；C 另覆盖停用站点与下单竞争，失败方无权益占用 |
| A10 | 两人同枪竞争，失败方权益和用户绑定回滚 | C 的 A09–A13 方法；W 的资源竞争方法 | **PASS（真实 MySQL 并发）**：C 两用户同枪仅一单成功，另一张券仍 AVAILABLE，用户绑定/会员预占未泄露，W 亦通过 |
| A11 | 同 Key 同请求返回同一订单、无重复副作用 | C 的 A09–A13 方法；`scripts/acceptance.py` 对应 A11 | **PASS（真实 HTTP＋MySQL）**：H 连续 20 次重放响应业务数据一致，C/W 校验同单及副作用不增加 |
| A12 | 同 Key 换枪或换券返回 409 | C 的 A09–A13 方法；`scripts/acceptance.py` 对应 A12 | **PASS（真实 HTTP＋MySQL）**：H 换枪与 C 换枪/换券均返回 409 IDEMPOTENCY_CONFLICT，不沿用错误请求 |
| A13 | 锁券后 SQL 故障，订单/枪/额度/券/Outbox/幂等记录全回滚 | C 的 A09–A13 方法中 `rollback-benefits` 注入 | **PASS（真实 MySQL 注入）**：C 在锁券及 Outbox 写入后注入不存在列异常，订单/枪/额度/券/Outbox/幂等记录全回滚 |
| A14 | 同通知重发 20 次只入账、启动一次 | W 的会员计费方法；`scripts/acceptance.py` 对应 A14 | **PASS（真实 HTTP＋MySQL）**：H 的 nonce/通知去重与 W 的 20 次资金通知通过，一笔 CAPTURE、一个启动业务效果 |
| A15 | 多通知 ID 同渠道交易不重复入账 | `F` 的 A15/A16 部分；W 的会员计费方法 | **PASS（真实 MySQL）**：F 与 W 逐场景断言通过，多通知 ID 同渠道资金仅一笔不可变入账，J 有正式结果 |
| A16 | 已签名但金额/币种错误保留差异，不启动 | `F` 的 A15/A16 部分；`PaymentServiceTest.mismatchedAmountAndFractionalAmountCannotCaptureMoney` | **PASS（真实签名 HTTP＋MySQL）**：`H.signedAmountAndCurrencyMismatchesRemainDifferenceFactsWithoutStartingOrCapturing` 与 F 通过，错误金额/币种保持 DIFF，无 CAPTURE/启动 |
| A17 | 错签名、冒用设备/会话被拒且不污染正常会话 | `HmacTest` 具名签名/归属/重放方法；`S.eventIdConflictingPayloadIsAppendOnlyAndRejectedForeignDeviceCannotPoisonBilling`；`scripts/acceptance.py` 未签名回调 | **PASS（真实 HTTP＋域隔离）**：H 错签名/篡改/nonce 重放、HmacTest 轮换归属及 S 异设备事件防污染均通过 |
| A18 | 支付到期与成功并发争用，捕获事实不丢、超期不启动 | `F` 的 A18 并发部分；`W.canceledAndDeadlinePaymentsAreCapturedAndFullyRefundedWithoutStart`；模拟器 `test_capture_close_race_has_single_channel_winner` | **PASS（真实 MySQL 并发＋模拟渠道契约）**：F 到期/成功竞争与 W 精确截止全退通过；P 的 capture/close 并发仅单一渠道胜者 |
| A19 | 取消后成功付款仍记 CAPTURE 并全退、不启动 | F 的 A19；W 的取消晚到付款方法 | **PASS（真实 MySQL）**：F/W 取消晚到付款仍记录一次 CAPTURE 与一笔全退，保持 CANCELED 且无首次启动 |
| A20 | 启动响应超时但设备运行，保占用并恢复 CHARGING | `S.A20_unknownStartDoesNotRefundAndActualStartedRestoresOwnership`；`scripts/faults.py --scenario start-response-lost` | **PASS（真实 MySQL 恢复＋模拟器契约）**：S 保留未知启动资源占用并由实际 STARTED 恢复，P 的 UNKNOWN/运行会话不误判为未开始 |
| A21 | 明确拒绝并权威无会话后取消/全退/释放 | `S.A21_authoritativeNotStartedCancelsAndReservesOneFullRefund`；`test_device_acceptance.test_explicit_start_rejection_is_durable_and_stable_after_fault_disabled` | **PASS（真实 MySQL＋模拟器契约）**：S 确认 NOT_STARTED 后一笔全退、权益/资源释放；P 的明确拒绝为持久稳定事实 |
| A22 | 同 START commandId 只创建一会话 | `simulators/test_simulators.py::test_demo_device_facts_stable_session_and_query_latest`；`S.A22_duplicateStartedFactKeepsOneSessionAndOneMeterSample` | **PASS（平台去重＋独立设备事实）**：S 单会话/单样本断言通过；P 的同 START commandId 重试实际只建一个 SQLite 会话 |
| A23 | 重复/乱序计量留原始事实、不重复累加 | `S.A23_outOfOrderAndDuplicateSamplesNeverAccumulateEnergyTwice`；W 乱序补齐方法 | **PASS（真实 MySQL）**：S/W 乱序、重复样本归并通过，累计电量不重复累加，原始事实保留 |
| A24 | 同序号不同电量可见冲突，不能覆盖原值 | `S.A24_conflictingSequenceNeedsIndependentEvidenceDecisionAndNeverOverwritesFacts` | **PASS（真实 MySQL）**：S 同序号冲突不覆盖原样本，独立第二审核人决策后才出账，原冲突仍保留 |
| A25 | 缺边界/缺 final_seq 样本不出账，补传后恢复 | `S.A25_confirmedStopWithGapReleasesSafeResourcesAndLateReplayCannotReleaseNewOrder`、`A25_completeSequenceWithoutAnActualTariffBoundaryNeverInterpolatesAFormalBill`；历史 `scripts/faults.py --scenario metering-boundary-lost` | **PASS（真实 MySQL）**：S 的缺序列、缺真实费率边界及原样补传恢复方法均通过；未插值出账，旧补传不释放新订单资源 |
| A26 | 电表倒退进入 REVIEW，无负费用 | `S.A26_meterRollbackRetainsEvidenceAndCorrectedDeviceFactNeedsResolution`；`SessionServiceTest.gapsRollbackAndReversedTimesCannotGenerateBills` | **PASS（真实 MySQL）**：S 电表回退保持 REVIEW 与原始错误事实，修正证据经审核后产生正费用账单 |
| A27 | 停止成功但回执丢失，权威查会话后恢复 | `S.A27_A28_lostStopAcknowledgementReplayFinalizesExactlyOnce`；`test_device_acceptance.test_lost_stop_ack_and_duplicates_preserve_one_real_stop_and_no_invented_energy` | **PASS（真实 MySQL 恢复＋模拟器契约）**：S 停止回放/最终出账及 P 丢失停止 ACK 场景通过，持久设备事实不重建会话或电量 |
| A28 | 结束通知/出账任务重复仅一账单、权益一次 | S 的 A27/A28 方法；W 会员计费方法最后重复 tryFinalize | **PASS（真实 MySQL）**：S 20 次同停止事实/tryFinalize 仅一张账单、一笔退款、会员一次、券 USED；W 亦重复校验 |
| A29 | 服务费不足券门槛释放券，并按当前有效期返回 AVAILABLE/EXPIRED | `S.A29_couponBelowThresholdIsReleasedAsExpiredAtSettlement`；`C.A29_A30_A31_expiryFrozenMonthZeroDiscountAndPublishedTemplateProtection` | **PASS（真实 MySQL）**：S 正式低服务费结算释放过期券为 EXPIRED，C 校验未过期 AVAILABLE 和过期 EXPIRED，发行库存不回补 |
| A30 | 月末预占、次月结束只扣原月份 | `S.A30_monthBoundaryConsumesTheReservedMonthAndUsesRealMidnightMeter`；C 的 A29–A31 方法；B 月界部分 | **PASS（真实 MySQL）**：S 实际午夜计量正式出账与 C/B 预占跨月通过，只消耗 2026-10 账户，不误建/扣 2026-11 账户 |
| A31 | 实际会员优惠为零释放、不占次数 | `S.A31_zeroMemberDiscountReleasesReservedQuota`；C 的 A29–A31 方法；BillingCalculator V05 | **PASS（真实 MySQL）**：S 正式零服务费折扣为零，会员预占转 RELEASED、used_count=0，C 与 V05 亦通过 |
| A32 | 无电量/确认未服务应付零、全退、权益释放 | `S.A32_zeroEnergyAtTheSameMillisecondRefundsInFullAndUsesNoBenefits`；S 的 A21；BillingCalculator V05 | **PASS（真实 MySQL）**：S 同毫秒零电量正式账单应付 0、全退 10000、无账单计费段、券 AVAILABLE、会员不消耗 |
| A33 | 退款成功但响应/通知丢失，UNKNOWN 占额度、原号查单恢复 | `F` 的 A33/A35；`scripts/faults.py --scenario refund-response-and-notification-lost`；模拟器 `test_lost_refund_response_and_notification_still_query_same_success` | **PASS（真实 MySQL 恢复＋独立渠道事实）**：F UNKNOWN 保留 7900 退款预占后按原号查单恢复，P 的丢响应/通知不删除渠道成功事实 |
| A34 | 并发同退款结果只生成一条 REFUND 并转移额度一次 | `F` 的 12 并发退款回调；W 会员计费方法重复退款通知 | **PASS（真实 MySQL 并发）**：F 12 个并发退款通知仅一笔 REFUND，成功额/预占转移一次，W 串行重复亦通过 |
| A35 | 超预付款退款被拒，不超退 | `F` 的 A33/A35；`PaymentServiceTest.refundReasonReservesOnceAndUnknownContinuesToHoldCapacity` | **PASS（真实 MySQL）**：F 未知退款仍占额度时超额请求被 REFUND_LIMIT 拒绝，退款预占＋成功额不超过捕获金额 |
| A36 | 实际费用超预付保留原账单，SHORTFALL 可见、欠款阻断下单 | `S.A36_actualOvershootIsNeverTruncatedAndDebtBlocksFurtherCharging`；BillingCalculator V07 | **PASS（真实 MySQL）**：S 实际应付 20000 保留不截断、SHORTFALL 可见、无假退款且欠款用户不能继续建单 |
| A37 | Worker 领取后崩溃，租约到期另一进程接管 | `W.leaseTakeoverRejectsStaleAcknowledgement`；`M`；`scripts/mq-faults.py::a40/a41` | **PASS（真实进程 SIGKILL＋MySQL）**：Q 在实际发布/消费 barrier 后执行外部 SIGKILL，再启动新进程；A40 发布领取次数 1→2，A41 业务提交后、inbox 前的消费 token 1→2、处理次数 1→2，最终 DONE/inbox=1/真实 ACK |
| A38 | 过期租约旧进程不能回写，外部业务号去重 | W 的 leaseTakeover；M 的 proof/fencing/inbox 原子处理；Q 的 A40/A41 | **PASS（真实 MySQL fencing＋跨进程业务幂等）**：W/M 精确证明旧 token 续租/完成/失败被拒；Q 的实际 SIGKILL/新进程重投证明外部稳定业务号保持渠道及本地捕获/退款、会话、账单各一。已杀进程不会再回写，旧 owner 拒写结论来自 DB 并发测试，不以 KILL 替代该断言；HeartbeatTest 另证丢 token 不续 Broker 隐藏时间 |
| A39 | Redis 清空/停机后降级，DB 库存/额度/退款不越界 | `C.A39_redisDisconnectCannotPreventDatabaseReadsOrdersClaimsOrRefundIntents`；`CatalogCacheTest`；实际开发栈 Redis 进程故障 | **PASS（真实 Redis＋MySQL＋HTTP）**：C 的事务正确性测试通过；[服务端报告](evidence/enterprise/redis.json) 记录实际 FLUSHDB、停止 Redis、0.1809秒HTTP回源、重启并回填2个key。未清理数据库或独立渠道事实；物理故障与客户端断开分别保留证据 |
| A40 | MQ 发出后、本地标记前杀进程，重复投递无重复效果 | `scripts/mq-faults.py::a40`，`brokerAcceptedBeforeLocalPublicationMark`；M | **PASS（真实 Broker＋外部 SIGKILL）**：Q 的事件 `acae760b-2ee5-47ae-b84e-8ab37f0aead5` 在 Broker send 成功、Outbox 仍 PROCESSING 时被杀；同事件再发后 publishAttempts=2、真实 ACK=2（重复 ACK=1）、inbox=1，渠道及本地资金/会话/账单单笔 |
| A41 | 消费 DB 提交后、ACK 前杀进程，重投效果不重复 | `scripts/mq-faults.py::a41`，`AFTER_BUSINESS_BEFORE_INBOX` 与 `AFTER_INBOX_BEFORE_ACK`；M | **PASS（两笔新交易、两个实际崩溃窗口）**：Q 首窗口 `63369436…` 首笔退款已提交而 inbox/ACK=0，杀后业务重试 2 次、native deliveryAttempt=2、最终单笔；后窗口 `2372ce40…` inbox=1/ACK=0 后被杀，重投 deliveryAttempt=2、duplicateAck=1，业务处理保持一次。完整事件号、hash 和资金前后快照见 Q；后窗口保留磁盘拒写造成的 publishAttempts=7 |
| A42 | 持续失败入死信，授权人工重放保留原事件号/原因/审计 | `scripts/mq-faults.py::a42`，`boundedRetryBrokerDlqAndAuditedSameEventReplay`；M | **PASS（真实物理 DLQ＋ADMIN 审计重放）**：Q 事件 `1c6fb792-ff82-476c-9c47-4b9c403a061f` 实际失败 3 次后 Outbox/delivery 双 DEAD、inbox=0；查询 `%DLQ%chargehub-workflow-v1` 得物理 messageId `0122A95816CCA300010AE796DE00000023`。ADMIN 以原因重放保留原 eventId/payloadHash，audit=1，最终 DONE/inbox=1/ACK=1，财务效果未增加 |
| A43 | 渠道有成功交易、本地通知缺失，独立查单后统一补录 | F 的 A43/A44；`ReconciliationMysqlTest` | **PASS（真实 MySQL＋独立渠道适配）**：本次全量 F 通过，缺通知经核对原号/金额/币种/时间后统一补录并保存 finance_evidence；非真实支付机构接入 |
| A44 | 相同渠道账单重复/并发导入只一批、不重复补录 | `R` 的并发 12 导入；F 的幂等导入与 recoverBatch | **PASS（真实 MySQL）**：本次 R/F 均通过，12 并发导入一个批次，相同幂等导入/补录无二次现金效果 |
| A45 | 对账金额不符保留双方证据，DIFF 阻止财务闭合 | F 的 A45；R 的双向差异/冲突方法 | **PASS（真实 MySQL）**：F 保留渠道 9999 与本地 10000 证据，原现金不变且 financial_status=DIFF，财务不能错误闭合 |
| A46 | 应用切回旧镜像、迁移兼容，数据库及模拟器事实可恢复 | `scripts/deployment/rollback.py`；`sqlite-snapshot.py` | **PASS（真实旧镜像＋全新恢复副本）**：[完整报告](evidence/enterprise/rollback.json) 记录旧版空库、新版V1–V8与签名交易、旧镜像读取升级数据的11表逐行hash不变，再恢复MySQL到新库和双SQLite到新卷后由候选读取；零不变量违规。2项WAL/校验和工具回归通过，失败历史保留 |
| A47 | 50RPS×15分钟、100会话×30分钟、百万历史、索引计划和P95目标 | `scripts/performance/run.py --phase all --history 1000000`；`scripts/performance/load.py` | **FAIL，尚未通过**：既有15分钟轮次计划45000次，22次scheduler_lag漏发，实际发出44978次，其中42531成功、2447业务拒绝，技术错误/超时均0。新的完整重跑尚未完成；固定镜像、逐请求数据、索引计划及资源快照仍须随完整结果核验 |


本轮全量执行的 `selectedTests=null`，9 个隔离测试库全部建立，128 项 Java 测试均未跳过，专用 Redis 测试容器在完成后清理。JUnit XML 与日志汇总类集合一致（26 类），逐方法结果及摘要见 J；同一运行的数据库名和完成标记见 enterprise/mysql-integration.json。源码审计确认本次 120 个 Java/迁移/POM 文件与候选 Git SHA 一致。

Q 的同一次运行还遇到宿主磁盘空间不足，Broker 原日志显示 `message store is not writeable, so putMessage is forbidden 16`。释放空间后原 Outbox 自动退避重试成功，没有重置计数、改写资金事实或降低 Broker 磁盘保护阈值；A41 后窗口的发布次数 7 如实保留。该环境中断及原始证据路径记录在 Q 的 `environmentIncident`，没有通过重新运行隐藏中断。

A39 的真实 Redis 服务端故障与 A46 的完整回滚/卷恢复已有上述 PASS 证据。A47 保留既有失败结果，新的完整持续负载重跑尚未完成；历史失败日志保留。

更新为 PASS 时至少记录：候选 Git SHA 或源码指纹、构建镜像 ID、实际执行时间、MySQL/Redis/RocketMQ 版本、具名测试/故障步骤、预期及实际值、原始报告位置。模拟支付/设备数据应始终标注模拟环境。远端 CI 已执行的范围以对应提交的实际任务结果为准，后续新增门禁须另行验证。
