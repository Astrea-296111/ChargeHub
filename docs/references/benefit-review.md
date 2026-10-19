# 会员、优惠券与站点展示缓存源码评审

评审日期：2026-10-18。以下为实际通过已有 HTTPS Git 代理读取的公开源码，未使用私有实现。参考检出位于 `/workspace/reference/ruoyi-benefit-review` 与 `/workspace/reference/mall-benefit-review`，不进入产品构建。只参考设计取舍，ChargeHub 的状态机、SQL、DTO、缓存及测试独立实现，未复制上游代码块。

| 项目 | 实际分支与固定 SHA | 许可证 |
| --- | --- | --- |
| [YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro) | `master`，`ac022b15a094cf9cf82903d429b9729e72309da5`，提交日期 2026-10-05 | 根目录 `LICENSE`：MIT，Copyright (c) 2021 ruoyi-vue-pro |
| [macrozheng/mall](https://github.com/macrozheng/mall) | `master`，`dcaa93b3150352e5044708d7211b5bed0af4509f`，提交日期 2026-09-15 | 根目录 `LICENSE`：Apache License 2.0 |

固定版本源码链接与实际检查：

- ruoyi [CouponServiceImpl.java](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-mall/yudao-module-promotion/src/main/java/cn/iocoder/yudao/module/promotion/service/coupon/CouponServiceImpl.java)：`useCoupon` 按用户归属和旧状态条件更新；`returnUsedCoupon` 根据当前有效期决定退回可用还是过期；`takeCoupon` 先筛用户限领再保存券和更新模板数量；`deleteCoupon`/`invalidateCoupon` 减少模板领取数。
- ruoyi [CouponTemplateMapper.java](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-mall/yudao-module-promotion/src/main/java/cn/iocoder/yudao/module/promotion/dal/mysql/coupon/CouponTemplateMapper.java)：`updateTakeCount` 在数据库更新中检查领取数量上界。ChargeHub 使用参数化 SQL 和更新行数检查，并额外锁用户、锁模板及唯一键保护每人一张。
- ruoyi [CouponTemplateServiceImpl.java](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-mall/yudao-module-promotion/src/main/java/cn/iocoder/yudao/module/promotion/service/coupon/CouponTemplateServiceImpl.java)：模板管理服务、状态校验与持久层职责分离。ChargeHub 对应 typed DTO → operations service → repository/参数化 JDBC，且已发布模板不能覆盖。
- ruoyi [CouponStatusEnum.java](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-mall/yudao-module-promotion/src/main/java/cn/iocoder/yudao/module/promotion/enums/coupon/CouponStatusEnum.java)：上游 `UNUSED/USED/EXPIRE` 不包含本项目支付前锁券阶段，因此不能直接套用。
- ruoyi [MemberLevelRecordServiceImpl.java](https://github.com/YunaiV/ruoyi-vue-pro/blob/ac022b15a094cf9cf82903d429b9729e72309da5/yudao-module-member/src/main/java/cn/iocoder/yudao/module/member/service/level/MemberLevelRecordServiceImpl.java) 与相邻 `MemberLevelRecordDO`：会员等级变化保留独立记录。ChargeHub 采用操作审计与不可变 quota_ledger，首版不引入经验值等级体系。
- mall [UmsMemberCouponServiceImpl.java](https://github.com/macrozheng/mall/blob/dcaa93b3150352e5044708d7211b5bed0af4509f/mall-portal/src/main/java/com/macro/mall/portal/service/impl/UmsMemberCouponServiceImpl.java)：`add` 检查领取时间、库存和用户领取次数，保存领取历史；优惠券选择区分全场、品类、商品范围。其所读版本中 `countByExample → insert → coupon.count-1 → updateByPrimaryKey` 不足以证明并发领券安全，ChargeHub 不照搬该读改写算法。

| 主题 | 采纳 | 拒绝或补充 |
| --- | --- | --- |
| 券归属、旧状态条件更新、到期释放 | 在用户锁下预占/核销，过期释放转 EXPIRED | 不将过期检查仅交给后台定时器；锁定券自然到期后仍按订单冻结规则核销 |
| 库存及限领 | 数据库条件更新、唯一用户模板键、事务内审计流水 | 不使用 Redis 扣库存；不采用先查数量再无条件写回 |
| 模板数量回补 | 无 | 不采纳上游删除券回减模板领取数；本项目 issued_count 只增不减，取消/过期不补库存 |
| 会员 | 资格记录与独立变化记录思路 | 不引入商城等级/积分/余额；上海自然月两次，月份在预占时冻结；续期不重置已用次数 |
| 适用范围 | 明确枚举并冻结规则 | 首版仅 ALL_STATIONS，其他商品/品类/站点范围明确拒绝，避免误扩大适用权益 |
| 缓存 | 只缓存站点、展示费率及列表，30 秒 + 0–10 秒随机 TTL | 不缓存建单资格、库存、退款额度；订单 `tariff()` 始终读 MySQL |
| 发布失效 | MySQL 提交后替换随机命名空间 generation | 不使用事务提交前删缓存；旧读者写回旧 generation，不会污染新版本缓存；清空 Redis 后也不重用旧命名空间 |

Redis 失联时读路径绕回 MySQL，使用短连接/命令超时与一秒失败冷却；缓存异常不向写事务抛出。回滚不改变 generation。Redis 在提交后失效失败的极端情况下，其他实例的展示可能保留至短 TTL 到期；正式下单与正式计费始终读取并冻结数据库版本，价格与资金事实不受影响。连接密码既不进入 key，也不写日志或审计。

运营 API 新增站点/枪 GET、POST、PUT、status；券模板后台 GET/PUT；会员 GET、PUT 授权与 status。所有运营写入均经服务端角色校验、Idempotency、必填原因及同事务 ops_audit。活动连接器不能被停用、迁移或改设备，站点停用也不能释放活动订单绑定。`operational_enabled` 与设备状态分离，设备上报 IDLE 无法重新启用被停用的枪。

验证：`BillingCalculatorTest` 使用资料包全部八组向量；`TariffRulesTest` 覆盖缺口/重叠；`BenefitConcurrencyTest` 为真实 MySQL 1000 用户争抢 100 张券及并发限领；`CatalogCacheTest` 证明提交后失效、回滚不失效和旧读者不能倒灌；`CatalogMysqlRedisTest` 使用独立测试数据库和真实 Redis，覆盖版本冻结、事务回滚、资源竞争、权益跨月/过期及 Redis 不可用降级。执行结果以实际测试报告为准，不以本评审文字代替通过证据。
