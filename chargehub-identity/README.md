# 身份与授权

身份数据保存在 MySQL，用户令牌为随机32字节的 opaque Bearer token，数据库只存 SHA-256 摘要。令牌30分钟过期；有效期、撤销状态、用户 enabled 与角色在每次请求重新读取。角色修改后，无需重发 token 即在下一请求生效。没有用户名或密码日志，也不将凭证写入审计。

## 接口

| 接口 | 权限与内容 |
| --- | --- |
| `POST /v1/auth/token` | `{"username":"...","password":"..."}`，不需要幂等键；返回 accessToken/tokenType/expiresIn/roles |
| `GET /v1/auth/me` | 当前用户的 id、username、roles |
| `POST /v1/auth/logout` | 身份来自 Bearer；正文 `{"allSessions":false,"reason":"用户主动退出当前会话"}`；撤销当前令牌，true 撤销自己全部令牌 |
| `GET /v1/admin/users?limit=20&cursor=...` | ADMIN；按用户ID升序分页，只有 id/username/enabled/roles/createdAt，无密码或活动订单信息 |
| `POST` 或 `PUT /v1/admin/users/{id}/roles` | ADMIN；`{"roles":["CUSTOMER","OPS"],"reason":"运营岗位授权调整"}`，替换完整角色集合 |
| `POST /v1/admin/users/{id}/tokens/revoke` | ADMIN；`{"reason":"账号安全处置撤销会话"}`，撤销目标用户全部有效令牌 |

除登录外的写请求需要 `Idempotency-Key` 和5至255字符的 reason。幂等键为8至64字符，仅接受字母、数字及 `._:-`，可使用随机 UUID；同一业务重试沿用原键和原正文。可用角色只有 CUSTOMER、OPS、FINANCE、ADMIN。角色集合不接受未知值、空集合或重复值。角色变化、用户撤销令牌和用户登出均保存操作人、原因、结果及 requestId；幂等重放不重复审计。成功登出后该 Bearer 已失效，以它重试 HTTP 请求返回401；服务层仍保存幂等结果。

管理员操作先锁 `iam_admin_guard` 单例，再按用户ID升序预锁操作者与目标（同一用户去重），随后进入幂等操作；此用户锁顺序与会员授权协调器一致，避免跨领域互相授权形成反向等待。持锁后重新核对操作者 ADMIN，避免并发撤权后的旧请求继续授权。禁止移除最后一个 enabled 管理员。保留监控账号 `__monitor__` 不能通过角色接口变更，也不计为最后一个可用管理员；无论其角色如何，安全过滤器只允许它 GET 指标、GET 当前身份、POST 自己登出。

## 配置

| 配置 | 当前行为 |
| --- | --- |
| `CHARGEHUB_DEMO_PASSWORD` | `dev` 种子账号必需，至少12字符；`scripts/dev-env.py` 在私有 `.local/dev.env` 增量生成随机值 |
| `CHARGEHUB_MONITOR_PASSWORD` | 独立监控密码，提供时至少16字符；本地 Compose 要求提供，由同一脚本生成 |
| `CHARGEHUB_AUTH_LOGIN_ACCOUNT_LIMIT` | 每个账号每60秒最多尝试次数，默认10，允许1至100000 |
| `CHARGEHUB_AUTH_LOGIN_ADDRESS_LIMIT` | 每个源地址每60秒最多尝试次数，默认60，允许1至100000 |
| `chargehub.payment-key-id` / `device-key-id` / `control-key-id` | Spring 当前发送方 keyId，默认分别为 `payment`、`device`、`control` |
| `chargehub.integration-keys.<keyId>.owner` / `.secret` | 显式注册重叠轮换 keyId 的所属集成与密钥，见下例 |

种子账号只在 `dev` profile 创建，使用 `INSERT IGNORE` 保留已有用户。改变环境密码不会替换数据库中既存账号的密码摘要；监控代理的密码必须与已创建账号一致。`__monitor__` 是受限的 OPS 角色账号，不增加第五种角色；只允许 `GET /actuator/prometheus`、`GET /v1/auth/me`、`POST /v1/auth/logout`。它仍使用普通短期令牌和共享登录限流。演示凭证不应用于真实账号，配置示例不包含真实密钥。

## 共享限流与边界

登录限流按数据库 UTC 时钟的60秒窗口执行，默认同账号10次、同源地址60次，成功和失败尝试都计数。两级预算在独立事务提交，不会被密码校验失败回滚。总是先锁地址桶再锁账号桶；地址耗尽后不再为随机用户名创建桶。已知账号按数据库用户ID绑定预算，数据库排序规则视为同名的登录值无法绕过；未知账号键作大小写和规范化处理，数据库保存的桶键是摘要。所有实例共享预算；不依赖 Redis，也不接受来源头绕过。

服务端可通过 `CHARGEHUB_AUTH_LOGIN_ACCOUNT_LIMIT`、`CHARGEHUB_AUTH_LOGIN_ADDRESS_LIMIT` 配置预算，范围1至100000。性能环境可以显式调高独立 load generator 的地址额度；生产应按网关和共享出口设计预算，当前源地址直接来自 Servlet 的 remoteAddr，没有信任 X-Forwarded-For。

登录原始 JSON 最大4KiB，身份管理及登出16KiB，集成回调和其余 `/v1/` JSON写请求64KiB；读取未知长度请求同样受限。限制在 MVC 反序列化前执行，正文原始字节被缓存后供签名和业务解析读取。拒绝重复JSON字段、尾随内容、非对象或错误 Content-Type，既有无正文命令保持可用。财务 multipart 导入不经过此身份限长过滤器，沿用文件10MiB/总请求11MiB契约。HTTP响应带 nosniff、禁止 frame、Referrer/Permissions/CSP 策略，身份与业务数据禁止缓存。

HMAC 支持 keyId 重叠轮换，旧的 payment/device/control 配置继续兼容。新增受信任 key 可在环境对应的 Spring 配置里注册：

```properties
chargehub.payment-key-id=payment-2026-10
chargehub.payment-key=${CHARGEHUB_PAYMENT_KEY}
chargehub.integration-keys.payment-previous.owner=payment
chargehub.integration-keys.payment-previous.secret=${CHARGEHUB_PREVIOUS_PAYMENT_KEY}
```

应用接收端允许显式注册的旧/新 keyId，但 owner 必须与支付、设备或控制入口一致。应用发送端逻辑身份 payment/device/control 会使用其当前配置 keyId。当前独立模拟器仍只支持默认 payment/device/control ID 及每类单密钥，尚未实现或验证双端在线重叠轮换；默认演示不改变其ID。需要先为真实渠道或模拟器实现并验证同一轮换映射，再切换发送方，等待旧请求窗口结束后移除旧 key。密钥不得写入仓库或日志；建议使用独立生成的32字节以上随机值。应用轮换能力不跳过 nonce、防重放、时间窗口或原始正文验签。

## 公开实现研究记录

参考仓库：[YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro)，实际读取分支 `master-jdk17`，提交 `7c397bbfae3e768b26e3ee962c5aa7064e8d7c96`。根目录 LICENSE 为 MIT，版权 `Copyright (c) 2021 ruoyi-vue-pro`。本模块借鉴职责划分和校验边界，没有复制其业务源码或引入其模拟登录功能。

实际读取文件均以该提交为准：

- `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/permission/PermissionServiceImpl.java`：`assignUserRole` 的事务角色差量与角色缓存失效边界。本版采用本地守卫串行化管理员变化并直接回源角色，没有角色缓存。
- `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/permission/PermissionController.java`：授权入口先做服务端权限检查。本版进一步要求 reason、幂等键和最后管理员保护。
- `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/oauth2/OAuth2TokenServiceImpl.java`：`checkAccessToken`、`removeAccessToken` 的有效期和撤销职责。本版不实现刷新令牌，使用摘要与 revoked_at。
- `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/auth/AdminAuthServiceImpl.java`：账号/密码失败统一响应、登出与审计职责。本版额外为未知账号执行 dummy BCrypt 验证，使用数据库共享限流。
- `yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/filter/TokenAuthenticationFilter.java`：令牌过滤和当前身份边界。本版只支持真实数据库令牌，不接受模拟账号令牌或查询参数令牌。

## 验证

单元测试覆盖令牌摘要、禁用账号、每请求角色刷新、监控账号限制、重复认证头、原始正文保真、重复JSON/尾随内容、chunked超限与轮换owner隔离。真实数据库测试位于 `chargehub-app/src/test/java/com/chargehub/app/IdentityMysqlTest.java`。

MySQL测试须使用全新且名称以 `_test` 结束的专用库，并设置 `CHARGEHUB_IDENTITY_TEST_JDBC_URL`、`CHARGEHUB_IDENTITY_TEST_DB_USER`、`CHARGEHUB_IDENTITY_TEST_DB_PASSWORD`。它拒绝非MySQL、非测试库和已有表，不清空或删除已有数据。测试覆盖并发最后管理员保护、审计/幂等、令牌撤销、角色实时生效及两个实例的共享限流。
