# 隔离性能验收证据

实际候选源码为 `9d61589bf96ab8cd0cd12b6de3fe3a936c6092a1`，固定本地镜像为 `sha256:f02d5d40f034582d85637cc0c9ff1aa353b09489c0f4cdbd1dcdd1b2446cc714`，OCI revision 已核对。运行器源码提交为 `aaa2c265037249044c174abe3f5fc4f997b2d0e6`；业务镜像未因工具修复而替换。运行时间为 `2026-10-19T16:45:23.274824+00:00` 至 `2026-10-19T17:33:32.606328+00:00`（UTC，另有容器停止收尾）。所有交易均为模拟渠道和模拟设备，无真实资金。

- [完整摘要](performance.json)、[全部尝试索引](evidence-index.json)、[独立渠道逐笔核验](channel-proof.json)。
- 真实插入100万条无资金的历史 CANCELED/CLOSED 订单；后续尝试复用同一隔离数据库。应用及依赖容器限制合计4 vCPU /8 GiB；独立 loadgen 另限0.75 vCPU /512 MiB。实际宿主能力与cgroup记录见摘要，不能把资源上限当作专属物理机器。
- 混合负载实际 `900.057` 秒，固定50RPS，45000/45000请求发出；42461成功、2539业务拒绝，技术错误/超时/认证失败/漏发均0。含排队时间的成功查询P95为49.63ms，成功建单P95为56.44ms；完整逐操作P50/P95/P99见摘要，业务拒绝保留在分母与原始记录。
- 100会话每5秒一次，实际 `1800.056` 秒，20RPS合计36000/36000计量成功。全部100会话COMPLETED/CLOSED，每笔3600Wh，100次登录刷新与每笔账单、捕获、退款核验通过。独立只读SQLite核对捕获1000000分、退款953200分、渠道净收46800分=平台应收46800分，100笔支付及退款均唯一；源main/WAL/SHM字节哈希不变。
- 本轮共82569条原始请求，79848成功、2721业务拒绝（其中182是渠道交易异步创建前capture轮询404）。`cleanup`原始标签也包含建会话阶段的轮询；索引保留所有阶段。最终业务不变量0。

## 保留的失败与边界

共保留12次尝试：4个早期配置/磁盘失败、当前候选的NameServer内存/种子SQL/文件权限失败、原发生器完整900秒FAIL、冷启动10秒FAIL，以及原短测/暖短测/新完整测试3个PASS。前述准备失败没有发送业务负载，不能算查询SLO失败。每个实际负载attempt的逐请求记录均公开为gzip；没有制造不存在的负载记录。

原发生器900秒有22条 `scheduler_lag` 漏发，完整失败未改写。修复后有容量时迟到请求补发，容量耗尽才记漏发，SLO使用queue+wire总时间；4项最小回归真实通过。冷态短测29条站点慢响应集中在前2.2秒，该次FAIL也原样保留。新完整窗口没有裁掉任何冷态或迟到样本。

运营端站点订单SQL不在本次客户混合GET负载内；本次准备期ANALYZE虽使用索引，仍扫描569569行并耗时1307ms。客户历史游标扫描10000行约33.3ms。不能据此声称所有列表都达300ms，也不能把“使用了索引”等同于优化完成。SQL及原始执行计划在 [current/queries](current/queries/) 中。

本次已测 `load.py` 副本为 [harness/measured-load.py](harness/measured-load.py)，SHA256已与run.json记录核对一致；旧原发生器为 [harness/original-load.py](harness/original-load.py)。测试结束后仅修正未来报告的effectiveArrivalRate及迟到间隔展示：sessions真实20RPS、间隔50ms、超过50ms共8条；原始summary仍保留默认CLI rate=50、20ms阈值/13条，未回写测量或SLO。此展示修正仅执行py_compile，无额外45分钟重测。

## 复现

需要Docker Compose、已保留的固定候选镜像及模拟器镜像。先确认镜像OCI revision，使用仓库安装说明生成开发工具；不要使用演示数据库。以下运行器自行创建唯一项目、随机私有凭据和独立卷，不接受公共目标URL。

```bash
python3 scripts/performance/run.py \
  --image sha256:f02d5d40f034582d85637cc0c9ff1aa353b09489c0f4cdbd1dcdd1b2446cc714 \
  --sha 9d61589bf96ab8cd0cd12b6de3fe3a936c6092a1 \
  --phase smoke --duration 10 --history 1000000
# 使用输出的12字符runId复用同一隔离库；不要并发两个性能栈。
python3 scripts/performance/run.py \
  --image sha256:f02d5d40f034582d85637cc0c9ff1aa353b09489c0f4cdbd1dcdd1b2446cc714 \
  --sha 9d61589bf96ab8cd0cd12b6de3fe3a936c6092a1 \
  --run-id <runId> --phase all --history 1000000 --down
```

精确测得的发生器源码以harness副本及工具提交为准。独立渠道核验需在计量结束后、down前从隔离MySQL只读导出orderNo/payNo映射；down后使用 `scripts/performance/verify-channel.py`，在network none、根fs及渠道卷只读、mode=ro的临时容器中核对。该步骤读取保留卷，不重新启动整栈。每笔映射与金额已在公开channel-proof中给出。

`manifest.json`列出公开文件SHA256；每个gzip额外列出解压后的SHA256、字节数及请求行数，并已逐字节验证解压结果等于原始记录。私密env、未脱敏日志、完整Docker inspect、数据库dump与合成INSERT脚本均未公开。
