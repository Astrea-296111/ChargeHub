# ChargeHub 充电交易与会员权益平台

本目录是后端开发实施资料包，版本 1.0，基准日期 2026 年 10 月 18 日。目标是让一名具备 Java 和 SQL 基础的开发者，在 Codex 协助下完成可部署、可演示、可测试的自研项目。预计 12 至 16 周，每周 15 至 20 小时；这是排期假设，需按每个里程碑的实际进度调整。

先读 `01-project-plan.md`，再将整个目录交给 Codex。第一轮只执行 `02-codex-tasks.md` 中的 T00 和 T01，完成后按验收结果继续。文档、SQL 和接口定义是实施基线，资料包不包含已运行的后端应用。

## 文件入口

| 文件 | 用途 |
| --- | --- |
| 01-project-plan.md | 完整项目书，包含业务、架构、规则、状态、一致性、上线与验收 |
| 02-codex-tasks.md | 分阶段开发任务、首轮提示词和每轮交付要求 |
| 03-schema.sql | MySQL 8.4 建表基线，需在 T01 的真实 MySQL 容器中验证 |
| 04-openapi.yaml | 核心 HTTP 契约，OpenAPI 3.0.3 |
| 05-events.schema.json | 异步事件与模拟设备上报的 JSON Schema |
| 06-acceptance.md | 故障注入、并发和业务验收清单 |
| 07-billing-vectors.json | 可复算计费示例及优惠、退款期望值 |
| 08-references.md | 企业公开资料、开源仓库、源码入口与借鉴边界 |
| 09-interview-evidence.md | 实施证据记录表、复盘模板和简历表述方法 |
| 10-design-review.md | 本次设计核验结果及首次开发必须验证的事项 |
| 11-integration-contracts.md | 两个独立模拟服务、签名、领域事件和渠道CSV契约 |
| 12-design-validation.json | 本次设计静态检查与金额复算的机器可读结果 |
| tools/check_spec.py | 复算与结构检查脚本，Python3加PyYAML可运行 |

`03-schema.sql` 不含可直接对外使用的账号或密码。模拟支付和模拟设备只在 dev/test 配置中启用。演示站应始终展示其模拟属性。

## 建议实施起点

建立新 Git 仓库并复制本目录到 `docs/spec/`，先实现模块化单体。每个阶段提交可运行的增量、测试结果、迁移脚本和一份简短复盘。使用开源代码时记录来源与实际修改，保留许可证及署名文件。没有实际完成的模块、线上规模或任职经历，不写成已完成成果。
