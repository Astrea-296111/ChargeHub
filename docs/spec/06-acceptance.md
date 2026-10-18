# 业务与故障验收清单

所有场景记录执行日期、Git SHA、容器版本、输入、实际结果和证据路径。下表是待执行的验收计划，不能默认勾选通过。

| 编号 | 场景与操作 | 必须观察到的结果 | 阶段 |
| --- | --- | --- | --- |
| A01 | 全新 MySQL 启动并再次启动 | Flyway 成功且不重复初始化业务数据 | P0 |
| A02 | 用户读取他人订单并尝试停止 | 403 或一致的不可见响应，设备无指令 | P0 |
| A03 | 费率缺一分钟或相互重叠 | 发布失败，不能产生可用费率 | P0 |
| A04 | 建单后发布不同单价的新版本 | 原单仍按旧快照计费 | P0 |
| A05 | 单时段 20 kWh 会员加券 | 电费1600 服务费1000 会员200 券300 应付2100 退款7900分 | P0 |
| A06 | 跨时段与跨午夜充电 | 每段按对应版本费率及边界电量计算 | P0 |
| A07 | 1000 用户同时争抢 100 张券 | 发行100，无超发，无重复用户券 | P0 |
| A08 | 同一用户同时领取同一模板20次 | 一张用户券，发行额度只增加一次 | P0 |
| A09 | 一位用户并发创建两单 | 至多一笔订单绑定资源 | P0 |
| A10 | 两用户同时占一把枪 | 一单成功，失败方权益和用户绑定均回滚 | P0 |
| A11 | 相同 Idempotency Key 重复请求 | 返回同业务单，副作用不增加 | P0 |
| A12 | 同 Key 换连接器或换券 | 409，不沿用错误参数 | P0 |
| A13 | 锁券后注入数据库异常 | 订单、枪、月额度、券和 Outbox 全回滚 | P0 |
| A14 | 同支付成功通知重发20次 | 一笔资金入账，一个有效启动业务效果 | P0 |
| A15 | 不同通知ID描述同一渠道交易 | 资金业务唯一约束阻止重复入账 | P0 |
| A16 | 改支付金额或币种并签名 | 差异挂起，不启动 | P0 |
| A17 | 错误签名或其他设备冒用会话 | 请求拒绝，无业务成功效果 | P0 |
| A18 | 未支付5分钟到期与渠道成功竞争 | 查关单后确定事实；成功则按实际状态启动或全退，无丢账 | P0 |
| A19 | 取消后收到付款成功通知 | 记录CAPTURE并退款，不重新启动 | P0 |
| A20 | 启动请求超时但设备已运行 | 保留占用，经查询恢复CHARGING，不提前全退 | P0 |
| A21 | 设备明确拒绝并查无会话 | 取消、全退，正确释放权益与资源 | P0 |
| A22 | 重复START commandId | 模拟设备只建一个会话 | P0 |
| A23 | 计量事件重复或先后颠倒 | 原始事件保留，可归并排序，电量不重复累加 | P0 |
| A24 | 同一会话同序号不同累计值 | 冲突可见，不能覆盖原值继续静默计费 | P0 |
| A25 | 缺失费率边界或final_seq中的样本 | 不出正式账单，补传后恢复 | P0 |
| A26 | 累计表值下降 | REVIEW，不能产生负电费 | P0 |
| A27 | 停止已执行但回执丢失 | QUERY_SESSION确认结束，恢复计费，不重复启动 | P0 |
| A28 | 同停止事件或出账任务重复执行 | 一张账单，权益各确认一次 | P0 |
| A29 | 服务费不足券门槛 | 券释放；已过期则EXPIRED，未过期则AVAILABLE | P0 |
| A30 | 月末预占、次月结束 | 使用创建时月份额度，不误扣新月额度 | P0 |
| A31 | 会员优惠最终为零 | 释放会员预占，不占用次数 | P0 |
| A32 | 无电量启动失败或确认未服务 | 应付零，全退，权益释放 | P0 |
| A33 | 退款渠道成功但响应和通知均丢失 | UNKNOWN占用额度，查原refund_no补录，禁止新号再退 | P0 |
| A34 | 并发处理同一退款结果 | 一笔REFUND流水，成功额与预占额正确转移 | P0 |
| A35 | 注入退款总额超过预付款 | 事务拒绝，无超退 | P0 |
| A36 | 停止延迟导致实际费用超预付 | SHORTFALL差异可见，不将应付强行截断 | P0 |
| A37 | Worker领取任务后崩溃 | 租约到期后接管，稳定业务号重试 | P0 |
| A38 | Worker租约过期后旧进程回写 | lease_token不匹配拒绝确认，外部重复由业务号去重 | P0 |
| A39 | Redis清空或停机 | 可降级查询；库存、退款与额度不突破DB约束 | P0 |
| A40 | MQ发送后标记前杀生产者 | 可重复投递，效果不重复 | P1 |
| A41 | 本地消费提交后Ack前杀消费者 | 消费去重和业务唯一约束保护效果 | P1 |
| A42 | 持续失败直到死信，再人工重放 | 原事件号、原因和审计完整，不生成新效果 | P1 |
| A43 | 渠道账单有交易而本地通知缺失 | 查单验证后统一入口补录 | P1 |
| A44 | 导入相同账单两次 | 返回已有批次，不重复补录 | P1 |
| A45 | 账单金额与本地不符 | DIFF并阻止财务闭合，保留双方原记录 | P1 |
| A46 | 应用回滚与数据库恢复演练 | 回调事实保留，迁移兼容或有明确恢复验证 | P1 |
| A47 | 核心场景压测与合成百万单查询 | 保存原始请求、超时与错误分类，执行计划可复现 | P1 |

## 压测记录模板

记录机器型号或vCPU、内存、API和Worker实例数、JVM参数、DB及连接池、依赖是否同机、压测端配置、数据规模、随机种子、预热时间、持续时间、目标速率、实际吞吐、P50/P95/P99、业务拒绝、技术错误和超时。保留负载脚本和原始结果，不能只保存截图。

固定速率测试使用开放式到达模型，另报客户端排队或无法发出的请求，避免系统变慢后压测端自行降速掩盖问题。正常售罄和枪被占是业务拒绝，单独统计，但不能从总请求数删除。

## 不变量核对 SQL 基线

```sql
-- 额度不应越界，返回零行
SELECT id FROM member_quota
WHERE used_count + reserved_count > total_count
   OR used_count < 0 OR reserved_count < 0;

-- 券发行不应超发，返回零行
SELECT id FROM coupon_template WHERE issued_count > total_count;

-- 退款占用与成功不能超过捕获，返回零行
SELECT id FROM payment_order
WHERE refund_reserved_fen + refund_success_fen > captured_fen;

-- 账单算术必须成立，返回零行
SELECT id FROM charge_bill
WHERE payable_fen <> energy_fen + service_fen - member_discount_fen - coupon_discount_fen
   OR payable_fen < 0;

-- 实际会话必须唯一，返回零行
SELECT order_no FROM charge_session GROUP BY order_no HAVING COUNT(*) > 1;

-- 渠道资金事实必须唯一，返回零行
SELECT biz_type,biz_no FROM cash_entry GROUP BY biz_type,biz_no HAVING COUNT(*) > 1;

-- 声称财务闭合的订单现金净额应等于应付，返回零行
SELECT o.order_no
FROM charge_order o
LEFT JOIN charge_bill b ON b.order_no=o.order_no
LEFT JOIN (
  SELECT order_no,SUM(CASE WHEN biz_type='CAPTURE' THEN amount_fen ELSE -amount_fen END) net_fen
  FROM cash_entry GROUP BY order_no
) c ON c.order_no=o.order_no
WHERE o.financial_status='CLOSED'
  AND COALESCE(c.net_fen,0)<>COALESCE(b.payable_fen,0);
```

这些 SQL 是补充检查，不代替逐交易核对。特别是资源绑定一致、已取消单不能开充以及设备状态未知不放枪，需要检查订单、会话、命令、用户和连接器之间的关联，并由集成测试验证。
