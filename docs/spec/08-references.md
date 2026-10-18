# 企业与开源参考资料

访问日期统一为 2026 年 10 月 18 日。下面区分业务事实、工程方法、开源参考与本项目自行决定的规则。资料描述不代表这些企业使用了本项目的数据表、架构或代码。

## 企业公开实践

**S01 蔚来 扫码充电服务规则**

https://cdn-power-public.nio.com/pe-fe-statics/agreement/charging-services-protocol.html

用于确认预付款、充电服务和第三方设备等业务要素。本项目的100元预付、会员次数、优惠顺序和退款细节为独立设计，不是蔚来收费政策。

**S02 美团 外卖订单中心的演进 2016**

https://tech.meituan.com/2016/09/09/mt-waimai-order-evolution.html

阅读交易资源预占、重试幂等、一致性和运行治理方法。采用其问题拆解思路，不复刻其历史技术栈、部署规模或性能数字。

**S03 美团 基于模式挖掘的可靠性治理探索 2023**

https://tech.meituan.com/2023/10/12/Exploration-of-reliability-governance-based-on-pattern-mining.html

用于理解唯一约束、状态条件和重复执行验证在幂等治理中的作用。本项目要求覆盖重复回调和任务重放。

## 大型开源业务系统

**S04 YunaiV ruoyi-vue-pro**

https://github.com/YunaiV/ruoyi-vue-pro

许可证入口：https://github.com/YunaiV/ruoyi-vue-pro/blob/master/LICENSE

重点阅读会员、营销和支付模块的职责及数据库组织。仓库支持多个Java分支，若研究Java21，应核对master-jdk17分支当时的构建说明。许可证标注MIT；如复制源码，按实际许可证保留所需版权和许可声明，不能依据README宣传语省略文件核对。

**S05 芋道 优惠券官方开发指南**

https://doc.iocoder.cn/mall/promotion-coupon/

阅读券模板和用户券、领取和使用的职责区分。ChargeHub的充电权益预占、时段计费和余额退款需独立实现。

**S06 macrozheng mall**

https://github.com/macrozheng/mall

已核对的订单源码入口：https://github.com/macrozheng/mall/blob/master/mall-portal/src/main/java/com/macro/mall/portal/service/impl/OmsPortalOrderServiceImpl.java

许可证入口：https://github.com/macrozheng/mall/blob/master/LICENSE

可阅读generateConfirmOrder、generateOrder、cancelTimeOutOrder、cancelOrder等流程，研究订单确认、优惠与取消释放。许可证标注Apache 2.0。电商未支付关单不能直接替代充电设备会话确认。

## 充电接入与基础组件

**S07 steve-community SteVe**

https://github.com/steve-community/steve

用于研究充电站管理和OCPP会话边界。仓库标注GPL 3.0，当前主分支README要求JDK25或更新。本项目将其作为协议研究及可选独立联调参考，不合并其源码或声明兼容所有设备。

**S08 Open Charge Alliance OCPP**

https://openchargealliance.org/protocols/open-charge-point-protocol/

OCPP负责充电站与管理系统之间的通信。首版自定义HTTP模拟器不构成OCPP实现；后续接入需选择明确版本并以协议测试验证。

**S09 Spring Boot System Requirements**

https://docs.spring.io/spring-boot/system-requirements.html

访问时官方主线为4.1.1，Java21在其兼容范围内。本项目选择4.1系列，具体补丁在T00锁定并构建；不把框架兼容范围当成所有第三方Starter均已验证。

**S10 Apache RocketMQ 消费重试文档**

https://rocketmq.apache.org/zh/docs/featureBehavior/10consumerretrypolicy/

用于设计消费异常、重试上限和死信处理。重试不能替代正常流程控制，客户端类型与版本需要在T09做实际烟测。

**S11 MySQL 8.4 Locking Reads**

https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html

用于核对FOR UPDATE、NOWAIT与SKIP LOCKED。SKIP LOCKED用于任务领取，不用于忽略竞争中的资金记录而继续计算余额。

**S12 MyBatis Spring Boot Starter Requirements**

https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/

官方兼容表将Starter4.0对应Spring Boot4.0及以上、Java17及以上。用于选择与Boot4.1一致的MyBatis集成，具体依赖仍需执行实际构建。研究旧版开源代码时不能混用Starter3.0配置。

## 阅读与复用记录表

| 日期 | 仓库与commit SHA | 文件或模块 | 学到的约束 | 实际复用与修改 | 许可证保留位置 |
| --- | --- | --- | --- | --- | --- |
| 待实施填写 | 待核实 | 待填写 | 待填写 | 未复用或实际修改 | 待填写 |
