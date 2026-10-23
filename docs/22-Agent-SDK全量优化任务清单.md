# Agent SDK 全量优化与演进任务清单（17 项）

> **文档状态**：实施中（长期基准文档）  
> **制定日期**：2026-10-22  
> **适用版本**：Agent SDK 1.1.0+  
> **核心原则**：代码质量极高、职责边界清晰、零隐藏死循环、无臃肿三方依赖、保持向后兼容

---

## 0. 任务全局进度看板

| 编号 | 梯次 | 任务名称 | 涉及模块 / 文件 | 当前状态 | 核心验收标志 |
| :--- | :--- | :--- | :--- | :---: | :--- |
| **01** | **P0** | **MCP 外部工具异常弹性化** | `McpToolAdapter.java:107` | **✅ 已完成** | 外部异常不致命崩溃，错误回填模型，连续失败由 `maxConsecutiveToolErrors` 统一兜底。 |
| **02** | **P0** | **Token 估算算法优化** | `TokenEstimator.java:10`<br>`ModelConfiguration.java` | **✅ 已完成** | 中英文字符加权，消除中文 3 倍虚高，支持配置与防御性说明。 |
| **03** | **P0** | **模型瞬时抖动有限重试** | `DefaultAgentRunner.java:742`<br>`SpringAiModelGateway.java:175` | **✅ 已完成** | 只在未推流时针对 429/5xx/超时重试 1 次，严格消耗请求预算，记录 `MODEL_RETRY`。 |
| **04** | **P0** | **状态机事件语义规范化与死代码清理** | `DefaultAgentRunner.java:704, 1034, 342` | **✅ 已完成** | 拆解被滥用的 `RUN_STARTED`，引入专用事件类型，删除无用私有方法 `estimateTokens`。 |
| **05** | **P0** | **交付物验收精确匹配** | `DefaultAgentRunner.java:1070` | **✅ 已完成** | 废除子串包含（substring match），改为产物唯一 ID 或精确文件名强匹配与非空检查。 |
| **06** | **P1** | **解耦 Starter 单例 Runner，提供会话管理器** | `AgentConfiguration.java:101`<br>`SessionRunnerManager.java`<br>`DefaultSessionRunnerManager.java` | **✅ 已完成** | 废除全局单例 Runner 强制模式，提供基于 LRU 与 idleTimeout 闲置驱逐的轻量多会话管理器。 |
| **07** | **P1** | **等待超时（waitingTimeout）配置化与全链路联动** | `AgentProperties.java`<br>`RunLimits.java`<br>`DefaultAgentRunner.java:274` | **✅ 已完成** | 在 application.yml 中开放挂起等待超时阈值，并在执行器超时检查中联动触发 RUN_TIMEOUT 事件与指标。 |
| **08** | **P1** | **目标动态修订与取消机制安全加固** | `DefaultAgentRunner.java:563, 660`<br>`TaskPlan.java:37`<br>`OneShotHttpClient.java:525` | **✅ 已完成** | `reviseGoal` 强制校验版本单调递增并同步重建计划；`cancel` 改为非阻塞原子状态位与底层 OkHttp Call.cancel 协同即时中断。 |
| **09** | **P2** | **MCP 协议升级与官方 Java SDK 替换** | `OfficialMcpClient.java`<br>`McpProtocolConstants.java`<br>`McpAutoConfiguration.java` | **✅ 已完成** | 引入官方 0.17.x SDK；支持本地 Stdio 子进程与远程 HTTP/SSE 传输规范及 Bearer/OAuth 鉴权；保留沙箱外溢落盘。 |
| **10** | **P2** | **企业技能生态标准化（支持 SKILL.md）** | `SkillDefinition.java`<br>`SkillConsultantTool.java` | **⏳ 待开始** | 支持开源规范的 `SKILL.md`（Prompt/SOP 元数据与正文解析），保留按需咨询工具。 |
| **11** | **P2** | **Spring 容器内 MCP 与 @AgentTool 自动发现合并** | `McpAutoConfiguration.java`<br>`AgentConfiguration.java` | **⏳ 待开始** | 自动将容器中的 MCP 适配工具与 `@AgentComponent` 本地工具聚合进统一 ToolCatalog。 |
| **12** | **P3** | **可信上下文（TrustedContext）注入** | `RunRequest.java:10`<br>`ToolContext.java:12` | **⏳ 待开始** | 宿主安全注入租户、调用人身份、授权范围与链路 traceId，模型无法越权篡改。 |
| **13** | **P3** | **审批绑定防重放与参数防篡改** | `ApprovalRequest.java`<br>`DefaultAgentRunner.java:910` | **⏳ 待开始** | 审批绑定主体、SHA-256 参数哈希、策略版本与有效期，执行前二次复核参数一致性。 |
| **14** | **P3** | **挂起点持久化 SPI（进程重启不丢失审批）** | `DefaultAgentRunner.java:1291`<br>`CheckpointSpi.java` | **⏳ 待开始** | 提供状态挂起与恢复 SPI，宿主服务重启后挂起的任务与审批可从外部存储恢复。 |
| **15** | **P3** | **只追加企业审计日志（Audit SPI）** | `RunEventSink.java`<br>`AuditTrailSpi.java` | **⏳ 待开始** | 提供不可篡改的只追加审计模型，记录高危操作谁审批、何时批准、参数哈希与依据。 |
| **16** | **P4** | **文档事实对齐与勘误（去除夸大词汇）** | `README.md`<br>`CHANGELOG.md` | **⏳ 待开始** | 修正“毫秒级冷启动”等夸大表述，如实标注外溢阈值（16384 字节）与流式状态。 |
| **17** | **P4** | **建立本地与持续集成（CI）构建门禁** | `.github/workflows/ci.yml` | **⏳ 待开始** | 配置标准 GitHub Actions / 本地构建门禁，执行 `mvn -B verify`（JDK 21）。 |

---

## 🔴 P0 梯次：核心缺陷修复与运行时容错（立即可做，零架构风险）

**定位与目标**：解决运行中会导致任务直接崩溃、预算失真或状态错乱的纯代码级缺陷。严格禁止引入任何重量级第三方外部依赖。

---

### 任务 01：MCP 外部工具异常弹性化（从致命崩溃改为自我纠偏）

* **状态**：`✅ 已完成`
* **涉及代码**：[`McpToolAdapter.java:107`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/adapter/McpToolAdapter.java#L107)
* **现状缺陷**：外部 MCP 进程只要抛出未捕获异常，适配层直接将 `ToolOutcome.Failure` 的 `terminal` 属性设为 `true`，导致整个 Agent 执行器立即将任务判定为 `FAILED` 并中断退出，模型完全失去自我纠错与使用备用工具的机会。
* **优化实施方案**：
  1. 将异常捕获时的 `terminal` 设为 `false`；
  2. 防御性处理 null 或空白异常消息，回退为稳定错误码及安全可读描述；
  3. 将失败作为观察（Observation）回填给大模型，允许模型换参重试或选择替代方案；
  4. 若外部工具反复异常，由执行器核心的 `maxConsecutiveToolErrors` 统一实施阈值熔断拦截。
* **测试与验证**：
  * [`McpClientAndAdapterTests.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/test/java/com/agent/agentcorelab/mcp/adapter/McpClientAndAdapterTests.java)
  * [`McpResilienceIntegrationTests.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/test/java/com/agent/agentcorelab/mcp/adapter/McpResilienceIntegrationTests.java)（验证自我恢复与连续超限熔断双边界）。

---

### 任务 02：Token 估算算法优化（中英文字符加权，消除中文严重虚高）

* **状态**：`✅ 已完成`
* **涉及代码**：[`TokenEstimator.java`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/main/java/com/agent/agentcorelab/model/TokenEstimator.java)、[`ModelProperties.java`](file:///D:/agent/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/ModelProperties.java)、[`ModelConfiguration.java`](file:///D:/agent/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/ModelConfiguration.java)
* **现状缺陷**：纯按 UTF-8 字节数粗暴估算（中文一个字 3 字节算 3 个 Token），导致中文长任务的上下文预算被严重虚高 3 倍，过早错误触发外溢落盘或上下文超限中断。
* **优化实施方案**：
  1. 在 `TokenEstimator` 中新增 `characterWeighted(asciiWeight, nonAsciiWeight, safetyBuffer)` 策略（推荐权重：ASCII 0.25，非 ASCII 0.70，安全余量 1.15）；
  2. 保留原 `conservativeUtf8()` 方法实现向后兼容；
  3. 在 `agent.model.token-estimator` 中提供配置项支持自由调优；
  4. 明确文档声明：“TokenEstimator 仅用于前置防御性预算控制，不是计量或账单，实际计费以模型返回的 usage 为准”。
* **测试与验证**：
  * [`TokenEstimatorTests.java`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/test/java/com/agent/agentcorelab/model/TokenEstimatorTests.java)（15 组中英混合、纯中文、纯英文、Emoji 及高低位代理对用例覆盖）；
  * [`ModelConfigurationTests.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/test/java/com/agent/agentcorelab/model/ModelConfigurationTests.java)。

---

### 任务 03：模型瞬时抖动有限重试（429 / 5xx / 网络超时）

* **状态**：`✅ 已完成`
* **涉及代码**：[`DefaultAgentRunner.java:714-770`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java)、[`RunLimits.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/run/RunLimits.java)、[`RunEvent.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/trace/RunEvent.java)、[`SpringAiModelGateway.java`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/main/java/com/agent/agentcorelab/model/SpringAiModelGateway.java)、[`ModelCallException.java`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/main/java/com/agent/agentcorelab/model/ModelCallException.java)
* **现状缺陷**：网关内部 `maxRetries(0)` 禁止了框架隐藏重试，但执行器层未提供受控重试逻辑；公网网络微抖动、连接超时或模型服务商 429 瞬间限流时，任务直接报错终止。
* **优化实施方案**：
  1. 在 `RunLimits` 中增加 `maxModelRetries` 配置（默认 1 次）；
  2. **绝对防重红线**：流式监听器一旦已输出任何文本/思考 Chunk，绝不发起重试，避免客户端内容重复拼接；
  3. **严格错误类型划分**：仅超时（`HttpTimeoutException`）、连接中断（`ConnectException` 等）、HTTP 429、HTTP 5xx 允许重试，400/401/协议格式错误严禁重试；
  4. **预算与可观测性闭环**：重试计入 `state.modelRequests`，严格受 `maxModelRequests` 配额控制；每次重试发出 `MODEL_RETRY` 事件并记录日志和指标。
* **测试与验证**：
  * [`DefaultAgentRunnerTests.java`](file:///D:/agent/agent-sdk/agent-core/src/test/java/com/agent/agentcorelab/agent/DefaultAgentRunnerTests.java)（40 组核心测试全过）；
  * [`SpringAiModelGatewayTests.java`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/test/java/com/agent/agentcorelab/model/SpringAiModelGatewayTests.java)（覆盖 408/429/5xx 重试与 3xx/400/401/403 拒绝重置）。

---

### 任务 04：状态机事件语义规范化与死代码清理

* **状态**：`✅ 已完成`
* **涉及代码**：[`DefaultAgentRunner.java:704, 1058, 342`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java)、[`RunEvent.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/trace/RunEvent.java)、[`AgentLoggingRunEventListener.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/logging/AgentLoggingRunEventListener.java)、[`AgentMetricsRunEventListener.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/metrics/AgentMetricsRunEventListener.java)
* **现状缺陷**：
  1. `RUN_STARTED` 事件被滥用于内部两个次级阶段：行 704 用于“上下文压缩触发重新发起”，行 1058 用于“交付物验收不通过时的修复尝试”，严重误导外部监听器和审计指标；更隐蔽的是，由于 `AgentMetricsRunEventListener` 监听 `RUN_STARTED` 累加 `activeRuns`，重用 `RUN_STARTED` 会导致活跃任务数虚增、指标泄露漂移；
  2. 行 342 存在未被引用的冗余私有方法 `estimateTokens`。
* **优化实施方案**：
  1. 在 `RunEvent.Type` 中引入专门的独立事件类型：`COMPACTION_TRIGGERED` 与 `DELIVERABLE_RETRY`；
  2. 确保 `RUN_STARTED` 语义唯一，仅在任务最初启动阶段发出一次；
  3. 删除冗余的 `estimateTokens` 死代码；
  4. 同步更新 starter 中的结构化日志输出以及度量指标：分别增加 `agent.run.context.compactions` 与 `agent.run.deliverable.retries` 计数器，彻底修复 `activeRuns` 误累加漏洞。
* **验收标准**：
  * 单测覆盖生命周期全事件，断言 `RUN_STARTED` 在单次 Run 中严格且仅出现一次；
  * 断言 `COMPACTION_TRIGGERED` 与 `DELIVERABLE_RETRY` 正确触发，且不污染 `activeRuns` 指标；
  * 全工程 6 个子模块测试 100% 通过。

---

### 任务 05：交付物验收（Deliverable Check）精确匹配

* **状态**：`✅ 已完成`
* **涉及代码**：[`DefaultAgentRunner.java:1070-1200`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java#L1070)
* **现状缺陷**：当前检查交付物是否产生采用文件名或 ID 的子串包含匹配（`filename.contains(expected) || id.contains(expected)`），导致包含相似前后缀的文件（如要求 `report.pdf`，而工作区存在 `my-report.pdf.bak` 或 `super_report.pdf`）被错误判定为交付物已完成；且缺失文件物理存在性与非空（> 0 字节）校验。
* **优化实施方案**：
  1. 彻底废除子串 `contains` 匹配，引入精确多维匹配算法 `matchesDeliverable`：
     - 产物唯一逻辑 ID 精确比对（大小写不敏感）；
     - 文件名精确比对（如 `report.pdf` 与 `report.pdf`）；
     - 文件主干名（Base Name）精确比对（如声明 `comparison_report`，精确匹配产物 `comparison_report.md`，但不匹配 `comparison_report_v2.md`）；
     - 预期文件名主干名与逻辑 ID 精确比对；
     - 规范化多级子路径后缀比对（如 `exports/summary.csv`）；
  2. 增加文件物理存在性校验（`Files.exists(path) && Files.isRegularFile(path)`）；
  3. 增加文件有效非空大小校验（`Files.size(path) > 0`）；
  4. 丰富结构化失败反馈机制：
     - 若完全未生成：反馈 `MISSING_DELIVERABLE: Required deliverable '...' is not yet generated in the workspace.`；
     - 若引用了产物但物理文件缺失：反馈 `MISSING_DELIVERABLE: Required deliverable '...' was referenced as artifact '...', but physical file does not exist at '...'.`；
     - 若文件存在但为空：反馈 `EMPTY_DELIVERABLE: Required deliverable '...' (artifact '...') is empty (0 bytes). Please generate non-empty content before completing.`。
* **验收标准**：
  * [`ExplicitPlanningAndTaskBriefTests.java`](file:///D:/agent/agent-sdk/agent-core/src/test/java/com/agent/agentcorelab/agent/ExplicitPlanningAndTaskBriefTests.java) 包含专门单测：
    - `matchesDeliverableUnitTests`：正向覆盖 ID/文件名/主干名/路径，负向隔离前后缀与子串误判；
    - `substringSimilarityRejectedByExactDeliverableMatching`：相似命名前缀在执行器运行中被严格阻断；
    - `emptyDeliverableFileRejectedWithClearFeedback`：0 字节空文件被精确拦截并给出结构化反馈。
  * Reactor 全模块测试 100% 通过。

---

## 🟡 P1 梯次：并发架构与会话隔离治理（解决多租户/多会话痛点）

**定位与目标**：打破当前应用级只能单任务串行执行的枷锁，让 SDK 具备在企业 Spring 服务中支撑多并发会话的能力。

---

### 任务 06：解耦 Starter 单例 Runner，提供标准轻量会话管理器

* **状态**：`✅ 已完成`
* **涉及代码**：[`SessionRunnerManager.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/SessionRunnerManager.java)、[`DefaultSessionRunnerManager.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultSessionRunnerManager.java)、[`AgentConfiguration.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/AgentConfiguration.java)、[`AgentProperties.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/AgentProperties.java)
* **现状缺陷**：Starter 默认注册了全局单例 `agentRunner` Bean，而 `DefaultAgentRunner` 内部持有全局互斥排他锁（单 Runner 只能跑一个任务）。下游应用为了支撑多个并发用户会话，各自手写了数百行重复且脆弱的会话管理代码。
* **优化实施方案**：
  1. 在 `agent-core` 中引入标准 [`SessionRunnerManager`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/SessionRunnerManager.java) 接口及其开箱即用实现 [`DefaultSessionRunnerManager`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultSessionRunnerManager.java)；
  2. 支持按 `sessionId` 独立获取/创建隔离的 `AgentRunner` 实例，提供便捷的 `getOrCreateConversation` 快速接入多轮对话；
  3. 基于访问顺序（Access-Order）`LinkedHashMap` 实现 LRU 容量管理（默认上限 1000 会话，可配置）；
  4. **运行态会话绝对保护**：处于 `RUNNING` 状态的活跃任务即使成为最久未访问条目，也严禁被 LRU 驱逐，自动顺延寻找非运行态候选；
  5. 支持基于 `idleTimeout`（默认 30m）的主动闲置驱逐机制，且在锁外安全执行 `runner.close()`，避免长连接/清理阻塞主锁；
  6. 在 Starter 的 [`AgentConfiguration`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/AgentConfiguration.java) 中将 `SessionRunnerManager` 注册为核心 Bean，并自动绑定 Micrometer 度量指标 `agent.sessions.active` 与 `agent.sessions.evicted`；
  7. 原全局单例 `agentRunner` 标记为 `@Deprecated(since = "1.1.0")`，兼顾旧应用向后兼容。
* **验收标准**：
  * [`SessionRunnerManagerTests.java`](file:///D:/agent/agent-sdk/agent-core/src/test/java/com/agent/agentcorelab/agent/SessionRunnerManagerTests.java)（8 组单测通过）：
    - 多会话并发执行互不阻塞、无任务冲突异常；
    - LRU 满载时淘汰最老非运行态会话；
    - 运行中会话防误驱逐保护；
    - 全员 RUNNING 时容量保护防爆抛出受控异常；
    - `evictIdle` 超时安全驱逐与资源释放。
  * [`SessionRunnerManagerSpringBootTests.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/test/java/com/agent/agentcorelab/bootstrap/SessionRunnerManagerSpringBootTests.java)（3 组 Spring 环境测试通过）：
    - Starter 自动装配、配置属性绑定（`agent.session.*`）与指标监控全链路闭环。
  * Reactor 全模块构建及测试 100% 通过。

---

### 任务 07：等待超时（waitingTimeout）配置化与全链路联动

* **状态**：`✅ 已完成`
* **涉及代码**：[`AgentProperties.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/AgentProperties.java)、[`RunLimits.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/run/RunLimits.java)、[`DefaultAgentRunner.java:274-285`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java)、[`SessionRunnerManager.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/SessionRunnerManager.java)、[`AgentLoggingRunEventListener.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/logging/AgentLoggingRunEventListener.java)、[`AgentMetricsRunEventListener.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/metrics/AgentMetricsRunEventListener.java)
* **现状缺陷**：挂起等待用户输入（`WAITING_INPUT`）或等待审批（`WAITING_APPROVAL`）的超时时间在配置类中缺乏清晰的 YAML 暴露与单元测试断言；挂起超时被清理时此前未发出专用的 `RUN_TIMEOUT` 跟踪事件，导致运维与监控侧无法感知任务为何超时终止。
* **优化实施方案**：
  1. 在 `AgentProperties.LimitsSettings` 中正式开放 `waitingTimeout`（默认 15 分钟，支持 `15m`、`45m`、`1h` 等标准 Duration 格式），并与 `RunLimits` 无缝绑定；
  2. 在 `DefaultAgentRunner.checkWaitingTimeout()` 中，当挂起等待时间超过 `waitingTimeout` 时，正式触发 `RunEvent.Type.RUN_TIMEOUT` 事件，携带 `timeoutType="WAITING_TIMEOUT"`、`waitingSeconds` 与 `timeoutSeconds` 元数据；
  3. 将终止状态更新为 `STOPPED`，`reason` 明确设为 `WAITING_TIMEOUT`；
  4. 在 `SessionRunnerManager` 中打通 `pruneExpiredRuns()` 批量扫描接口，允许宿主系统或调度器一键批量扫描并淘汰多会话中的过期挂起任务；
  5. 补充日志监听器（输出告警日志 `Run timed out (WAITING_TIMEOUT) after ...s of waiting`）与度量监控（递增 `agent.run.timeouts` 指标）。
* **验收标准**：
  * [`AgentConfigurationTests.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/test/java/com/agent/agentcorelab/bootstrap/AgentConfigurationTests.java)：单测覆盖 `agent.limits.waiting-timeout=45m` 正确绑定；
  * [`SessionLifecycleAndApprovalTests.java`](file:///D:/agent/agent-sdk/agent-core/src/test/java/com/agent/agentcorelab/agent/SessionLifecycleAndApprovalTests.java)：单测验证挂起超时被扫描清理并触发 `RUN_TIMEOUT` 事件；
  * [`SessionRunnerManagerTests.java`](file:///D:/agent/agent-sdk/agent-core/src/test/java/com/agent/agentcorelab/agent/SessionRunnerManagerTests.java)：单测验证多会话批量超时扫描与清理；
  * [`AgentLoggingRunEventListenerTests.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/test/java/com/agent/agentcorelab/bootstrap/logging/AgentLoggingRunEventListenerTests.java) 与 [`AgentMetricsRunEventListenerTests.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/test/java/com/agent/agentcorelab/bootstrap/metrics/AgentMetricsRunEventListenerTests.java)：单测覆盖超时日志与监控指标。
  * Reactor 全模块测试 100% 通过。

---

### 任务 08：目标动态修订（reviseGoal）与取消机制安全加固

* **状态**：`✅ 已完成`
* **涉及代码**：
  * [`DefaultAgentRunner.java:563, 660`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java#L563)
  * [`TaskPlan.java:37`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/plan/TaskPlan.java#L37)
  * [`ModelSession.java:85`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/model/ModelSession.java#L85)
  * [`OneShotHttpClient.java:144, 525`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/main/java/com/agent/agentcorelab/model/OneShotHttpClient.java#L144)
  * [`SpringAiModelGateway.java:399`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/main/java/com/agent/agentcorelab/model/SpringAiModelGateway.java#L399)
* **现状缺陷**：
  1. `reviseGoal` 未严格校验版本单调递增，网络乱序可能导致旧版本覆盖新目标；且修订后未同步更新或重建 `TaskPlan`，导致后续模型调用 `updatePlan` 发生 `GOAL_VERSION_MISMATCH` 校验失败；
  2. `cancel` 中断在长模型请求或工具执行时受 `owner` 排他锁阻塞或 `tryLock()` 失败被静默跳过，且无法快速通知底层活跃网络连接与工作线程，导致长时间无法流转为 `CANCELLED`。
* **优化实施方案**：
  1. **严格版本递增校验**：在 `reviseGoal` 入口强制断言 `revisedBrief.goalVersion() > currentVersion`，对相同版本、旧版本及回滚修订统一抛出具象 `IllegalArgumentException`；
  2. **计划原子化同步重建**：引入 `TaskPlan.forRevisedGoal(newGoalVersion)`，在修订目标时将任务计划重置为对应版本的初始空计划（revision=0），确保后续大模型 `updatePlan` 顺利按新版本推进；
  3. **非阻塞原子取消通知**：重构 `cancel(UUID runId)`，彻底移除对 `owner` 排他锁的依赖；在 `synchronized (state)` 保护下原子更新 `cancelled` 标志并将等待态或运行态任务即时流转为 `CANCELLED`，确保 `snapshot().status()` 毫秒级立即可见；
  4. **底层传输协同中断**：
     * `ModelSession` 扩展默认 `cancel()` 接口方法；
     * `OneShotHttpClient` 引入 `AtomicReference<Call> activeCall`，在 `cancel()` 时联动触发 OkHttp 底层 `Call.cancel()` 与 `dispatcher().cancelAll()`，瞬间打断在途网络阻塞；
     * 执行器在 `drive()` 期间记录活跃工作线程并在取消时触发线程中断，工具抛出 `CancellationException` 或被中断时安全退出并记录 `INTERRUPTED_CANCELLED`；
  5. **幂等终态防护**：在 `finish`、`release` 与 `emitFinished` 中加入锁与状态门禁，一旦任务流转为 `CANCELLED`，禁止被后续异步异常或延迟结果覆盖为 `FAILED` 或 `COMPLETED`，且资源释放与指标递减保证严格只执行一次。
* **验收测试**：
  * [`ExplicitPlanningAndTaskBriefTests.java`](file:///D:/agent/agent-sdk/agent-core/src/test/java/com/agent/agentcorelab/agent/ExplicitPlanningAndTaskBriefTests.java)：
    * `reviseGoal_strictlyMonotonicVersionEnforced`：覆盖同版本、过期旧版本乱序请求被安全拒绝；
    * `reviseGoal_synchronizesAndRebuildsTaskPlan`：验证目标修订后计划原子重建并成功衔接后续 `updatePlan`。
  * [`SessionLifecycleAndApprovalTests.java`](file:///D:/agent/agent-sdk/agent-core/src/test/java/com/agent/agentcorelab/agent/SessionLifecycleAndApprovalTests.java)：
    * `cancelWaitingInputTask_immediatelyCancelledAndReleasesResources`：等待输入任务取消后即时流转为 `CANCELLED` 并拒绝后续 resume；
    * `cancelWaitingApprovalTask_immediatelyCancelledAndReleasesResources`：等待审批任务取消后即时流转为 `CANCELLED` 并阻止高危工具执行；
    * `cancelRunningTask_nonBlockingImmediateInterruption`：长时间工具阻塞时调用 `cancel`，验证其毫秒级非阻塞返回、工作线程迅速中断并产生 `RUN_FINISHED` 终态事件。
  * Reactor 全模块编译构建与全量单元测试 100% 通过（JDK 21）。

---

## 🟢 P2 梯次：协议标准件替换与生态升级（消除维护包袱）

**定位与目标**：落实“管道用标准件”的路线决策，不再自行手写维护底层协议通信，全面对接官方成熟生态。

---

### 任务 09：MCP 协议升级与官方 MCP Java SDK 替换

* **状态**：`✅ 已完成`
* **涉及代码**：
  * [`OfficialMcpClient.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/client/OfficialMcpClient.java)
  * [`McpProtocolConstants.java:11`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/protocol/McpProtocolConstants.java#L11)
  * [`McpClient.java:67`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/client/McpClient.java#L67)
  * [`DefaultMcpClient.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/client/DefaultMcpClient.java)
  * [`McpServerBridge.java:58`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/adapter/McpServerBridge.java#L58)
  * [`AgentMcpProperties.java:40`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/mcp/AgentMcpProperties.java#L40)
  * [`McpAutoConfiguration.java:70`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/mcp/McpAutoConfiguration.java#L70)
* **现状缺陷**：当前协议版本陈旧（硬编码 `2024-11-05`），自研手写简易 JSON-RPC，且仅支持本地进程 Stdio 管道，无法对接远程企业级 MCP 服务（HTTP / SSE 传输模式缺失）。
* **优化实施方案**：
  1. **引入官方 MCP Java SDK 标准件**：在 `agent-mcp-adapter/pom.xml` 中引入 `io.modelcontextprotocol.sdk:mcp` (0.17.1)，涵盖核心协议模型、`McpSyncClient`、`StdioClientTransport` 与 `HttpClientSseClientTransport`；
  2. **实现企业级 `OfficialMcpClient`**：
     * 完整实现 `com.agent.agentcorelab.mcp.client.McpClient` SPI 契约，支持双向同步与异步执行（`initialize`、`listTools`、`callTool`、`ping`）；
     * 提供开箱即用的链式构建器 `OfficialMcpClient.builder(serverName)` 及快捷工厂方法（`.stdio(...)`、`.sse(...)`、`.from(...)`）；
     * 支持远程 HTTP/SSE 协议端点发现握手、`Bearer / OAuth` 授权令牌自动注入以及自定义 HTTP 头传递；
     * Stdio 模式下支持底层操作系统子进程 `ProcessHandle` 与 `pid()` 级联感知与优雅停机销毁；
  3. **升级协议版本兼容与协商体系**：
     * 在 `McpProtocolConstants` 中扩展支持 `2025-06-18` 与 `2025-11-25` 等现代规范常量，与标准件版本自动协商对齐，并保持对历史 `2024-11-05` 版本的 100% 向后兼容；
  4. **全兼容生态契约与防腐隔离**：
     * 保持 `McpToolAdapter`、`McpServerBridge` 和 `McpServerManager` 接口与机制完全不变；
     * 原生保留工具名前缀定制、黑白名单精准过滤、`RiskLevel` 风险标注控制及超大产物沙箱外溢落盘（Safe Spillover）核心能力；
     * 将旧版自研 `DefaultMcpClient` 标记为 `@Deprecated(since = "1.1.0")`，保障历史代码平滑升级；
  5. **Spring Boot 自动装配全面扩展**：
     * `AgentMcpProperties.ServerConfig` 扩展 `url`、`sseEndpoint`、`token`、`headers` 与 `useOfficialSdk` 配置字段；
     * `McpAutoConfiguration` 智能识别 `stdio`、`sse` / `http` 与 `in_memory` 模式，默认启用官方 SDK 客户端并注册进 `McpServerManager`。
* **验收测试**：
  * [`OfficialMcpClientSseTests.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/test/java/com/agent/agentcorelab/mcp/client/OfficialMcpClientSseTests.java)（3/3 全部通过）：
    * `testRemoteSseHandshakeAndTools`：验证远程 HTTP/SSE 建立连接、Bearer 鉴权头传输、租户请求头传递、端点重定向解析、工具枚举与心跳延迟检测；
    * `testRemoteSseToolExecutionAndBridge`：验证官方客户端桥接到 `McpServerBridge` 后的白名单过滤、风险等级映射、正常工具执行与错误结果弹性包装；
    * `testRemoteSseAsyncOperations`：验证 `initializeAsync`、`listToolsAsync` 与 `callToolAsync` 异步全链路。
  * [`OfficialMcpClientStdioTests.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/test/java/com/agent/agentcorelab/mcp/client/OfficialMcpClientStdioTests.java)（1/1 通过）：
    * 启动真实 Java 子进程（[`MockMcpStdioServerMain.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/test/java/com/agent/agentcorelab/mcp/client/MockMcpStdioServerMain.java)），验证双向标准流 JSON-RPC 通信、工具调用、`ProcessHandle` 存活检测及 client 关闭时的子进程退出。
  * 历史测试套件 100% 绿色兼容通过：
    * `McpClientAndAdapterTests`（6/6 全部通过）；
    * `McpSafeSpilloverTests`（1/1 通过）；
    * `McpJsonRpcCodecTests`（5/5 通过）；
    * Reactor 6 模块全量单元测试（294/294 全部通过，0 failure, 0 error）。

---

### 任务 10：企业技能生态标准化（支持业界标准 SKILL.md）

* **状态**：`⏳ 待开始`
* **涉及代码**：[`SkillDefinition.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/skill/SkillDefinition.java)、[`SkillConsultantTool.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/skill/SkillConsultantTool.java)
* **现状缺陷**：当前使用自定义的 Java 类/JSON 格式定义技能，无法直接复用开源社区和企业沉淀的 Markdown Prompt 技能文件。
* **优化实施方案**：
  1. 按照开源规范（agentskills.io）支持解析标准 `SKILL.md`（解析 YAML Frontmatter 元数据与 Markdown SOP 正文）；
  2. 保持 Agent 通过 `consult_skill` 工具按需咨询 SOP 的机制不变。
* **验收标准**：
  * 读取合法与非法 `SKILL.md` 样本的单元测试通过；
  * Agent 能够正确按需调用 `consult_skill` 获取 SOP 细节。

---

### 任务 11：Spring 容器内 MCP 与 @AgentTool 自动发现合并

* **状态**：`⏳ 待开始`
* **涉及代码**：[`McpAutoConfiguration.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/autoconfigure/McpAutoConfiguration.java)、[`AgentConfiguration.java`](file:///D:/agent/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/AgentConfiguration.java)
* **现状缺陷**：容器中声明的 MCP 适配工具与 `@AgentComponent` 扫描出的本地注解工具存在割裂，未能自动聚合到同一个 `ToolCatalog` 中。
* **优化实施方案**：
  1. 实现统一工具聚合扩展点，在自动装配阶段统一收集 Spring 上下文中的 `Tool` Bean、`@AgentTool` 注解方法以及启用的 MCP 工具；
  2. 形成全局完备的 `ToolCatalog`。
* **验收标准**：
  * 在同一个测试应用中同时声明本地注解工具与 MCP 客户端工具，验证两者均能被 Agent 识别并调用。

---

## 🔵 P3 梯次：企业级治理与合规安全加固（构筑差异化护城河）

**定位与目标**：补齐面向合规监管、授权范围约束与高危操作审批的核心能力。

---

### 任务 12：可信上下文（TrustedContext）注入

* **状态**：`⏳ 待开始`
* **涉及代码**：[`RunRequest.java:10`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/run/RunRequest.java#L10)、[`ToolContext.java:12`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/ToolContext.java#L12)
* **现状缺陷**：上下文中缺少调用人身份（Principal）、租户标识（TenantId）、授权权限范围（Scopes）以及链路 traceId，工具执行时无法校验当前用户是否有权执行该动作。
* **优化实施方案**：
  1. 在 `RunRequest` 中引入只读载体 `TrustedContext`（向后兼容重载）；
  2. 运行时原样安全下传至 `ToolContext`；
  3. **模型防注入屏障**：`TrustedContext` 仅由宿主系统直接注入，模型生成的文本或参数绝对无法修改或伪造该上下文。
* **验收标准**：
  * 工具能准确拿到宿主传入的租户与用户身份；
  * 模拟 Prompt 注入伪造租户参数被工具侧阻断。

---

### 任务 13：审批绑定防重放与参数防篡改

* **状态**：`⏳ 待开始`
* **涉及代码**：[`ApprovalRequest.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/run/ApprovalRequest.java)、[`DefaultAgentRunner.java:910`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java#L910)
* **现状缺陷**：当前审批 ID 仅是一个随机 UUID，未记录审批人身份、参数哈希、策略版本与有效期，无法防御审批重放攻击与批准后参数被篡改的风险。
* **优化实施方案**：
  1. 审批记录强制绑定申请人、参数规范化哈希（SHA-256）、生效时限（TTL）与策略版本；
  2. 人工审批通过后，执行器在实际触发执行前再次计算当前参数的哈希并进行二次一致性复核，不匹配则拒绝执行。
* **验收标准**：
  * 篡改审批参数后调用 `approve` 抛出篡改异常；
  * 超时后的审批调用被安全拒绝。

---

### 任务 14：挂起点持久化 SPI（进程重启不丢失审批）

* **状态**：`⏳ 待开始`
* **涉及代码**：[`DefaultAgentRunner.java:1291`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java#L1291)
* **现状缺陷**：等待审批与等待用户输入的挂起任务仅保存在内存 Map 中，宿主服务一旦重启或滚动发布，所有待审批任务全部丢失。
* **优化实施方案**：
  1. 定义轻量 `CheckpointSpi`（状态快照保存与恢复协议）；
  2. 任务挂起（`WAITING_INPUT` / `WAITING_APPROVAL`）时持久化快照到存储（如 JDBC / 文件 / Redis）；
  3. 宿主服务启动时或收到审批决策时，支持从持久化快照重新恢复会话状态机继续推进。
* **验收标准**：
  * 模拟任务挂起后强制释放内存中的 Runner，通过 Checkpoint 恢复并完成审批继续执行。

---

### 任务 15：只追加企业审计日志（Audit SPI）

* **状态**：`⏳ 待开始`
* **涉及代码**：[`RunEventSink.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/trace/RunEventSink.java)
* **优化实施方案**：
  1. 提供不可篡改的只追加审计日志模型；
  2. 专用于记录：“谁、在何时、依据何种策略版本、批准或拒绝了什么高危工具参数，最终执行状态为何”；
  3. 杜绝全量提示词/私有数据打印，专注于操作合规留痕。
* **验收标准**：
  * 高危工具完整生命周期产生标准只追加审计事件记录，包含完整主体与哈希签名。

---

## ⚪ P4 梯次：工程基线清理与文档如实修正

**定位与目标**：消除文档夸大，确保文档、配置与代码实现 100% 对齐，建立自动化构建门禁。

---

### 任务 16：文档事实对齐与勘误（去除夸大词汇）

* **状态**：`⏳ 待开始`
* **涉及文档**：[`README.md`](file:///D:/agent/agent-sdk/README.md)、`CHANGELOG.md`
* **现状缺陷**：文档中存在“毫秒级冷启动”、“彻底阻断 Token 爆炸”等口号式夸大表述；且部分数字与代码实际不一致（如外溢阈值实测为 16384 字节而非文档写的 2KB）。
* **优化实施方案**：
  1. 全文排查并修正夸大表述，如实陈述机制与阈值；
  2. 标注流式增量输出在不同网关下的真实行为；
  3. 明确各项控制参数的默认值与代码物理位置。
* **验收标准**：
  * README 与核心配置及代码常数完全吻合。

---

### 任务 17：建立本地与持续集成（CI）构建门禁

* **状态**：`⏳ 待开始`
* **优化实施方案**：
  1. 配置 GitHub Actions 工作流（或本地预提交验证脚本）；
  2. 强制在 JDK 21 环境下执行 `mvn -B clean verify`；
  3. 门禁要求：全模块 100% 测试通过、0 密钥泄露检查。
* **验收标准**：
  * CI 工作流配置完成且单次运行全绿通过。

---

## 维护指引与多会话协作约定

> [!IMPORTANT]
> 1. **单项递进原则**：无论何时重启对话或由不同开发者/AI 接手，必须对照本清单中的当前状态，严格按照 **P0-4 -> P0-5 -> P1-6 -> ... -> P4-17** 的顺序推进，严禁跳步；
> 2. **质量门禁红线**：每完成一项任务，必须在 JDK 21 环境下执行 `mvn clean test` 确保 6 个模块全部测试通过，且必须为新逻辑补充正负向单元测试，方可将清单状态更新为 `✅ 已完成`；
> 3. **极简约束**：拒绝为 SDK 引入与核心治理无关的外部框架依赖（严禁把 SDK 做成重量级云平台或工作流引擎），专注单 Agent 的安全、受控、可信治理内核。
