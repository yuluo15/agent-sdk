# Agent SDK 变更日志 (CHANGELOG)

本文档记录 Agent SDK 所有正式发布版本的核心特性、架构升级与兼容性声明。
本项目遵循 [Semantic Versioning 2.0.0 (SemVer)](https://semver.org/lang/zh-CN/) 语义化版本规范。

---

## [1.1.0-SNAPSHOT] - 2026-10-23

> **里程碑定位**：**工程基线加固、官方 MCP 标准件替换与标准化 SKILL.md 生态接入**。  
> 标志着 SDK 摆脱了自研底层协议的维护负担，全面接入 Model Context Protocol 官方 Java SDK 0.17.1，支持远程 HTTP/SSE 传输与企业级 agentskills.io 规范的 SKILL.md 技能资产加载，并完成了核心运行时容错与多会话隔离治理。

### 核心演进与加固矩阵

#### 1. 官方 MCP Java SDK 替换与远程传输 (`OfficialMcpClient`)
* **全面接入官方 SDK 0.17.1**：底层引入 `io.modelcontextprotocol.sdk:mcp` 标准件，废除自研手写 JSON-RPC；
* **双模传输支持**：支持本地子进程 Stdio 通信，以及生产级远程 HTTP/SSE 传输（带 Bearer/OAuth 鉴权头注入与端点重定向协商）；
* **操作系统子进程生命周期深度感知**：Stdio 模式下基于 Java 原生 `ProcessHandle` 实时感知子进程 `pid()` 与健康状态，停机优雅级联销毁，杜绝僵尸进程；
* **协议多版本协商兼容**：支持 modern 规范 `2025-06-18` 与 `2025-11-25`，同时 100% 兼容历史 `2024-11-05` 标准。

#### 2. 标准化企业技能生态 (agentskills.io `SKILL.md`)
* **标准 Markdown SOP 解析器 (`SkillParser`)**：遵循开源规范解析以 `---` 包裹的 YAML Frontmatter 与 Markdown SOP 正文；
* **多层级目录与文件智能扫描 (`SkillLoader`)**：递归扫描目录中的 `SKILL.md` 与 `*.skill.md` 文件，自动忽略无关 Markdown（如 `README.md`）；
* **双向无损序列化 (`toMarkdown()`)**：支持从 Java 技能定义无损逆向生成合规 `SKILL.md`，正文内嵌水平线、代码块与表格均能无损保护；
* **Spring Boot 自动装配**：支持通过 `agent.mcp.skills-directory` 启动时自动扫描并注册全量企业技能。

#### 3. 并发治理与多会话管理器 (`SessionRunnerManager`)
* **解耦全局单例 Runner 瓶颈**：提供开箱即用的 `DefaultSessionRunnerManager`，支持按 `sessionId` 独立隔离 Runner 实例；
* **LRU 容量管理与活跃任务绝对保护**：处于 `RUNNING` 状态的活跃任务严禁被 LRU 驱逐，自动顺延寻找闲置候选；
* **空闲会话超时安全驱逐**：基于 `waitingTimeout` 阈值自动清理超时挂起的等待任务，并产生 `RUN_TIMEOUT` 事件与度量指标。

#### 4. 核心运行时容错与安全加固
* **MCP 异常弹性化**：外部 MCP 抛错不再导致 Agent 进程致命失败（`terminal=false`），异常转为 Observation 回填大模型自纠，连续失败由 `maxConsecutiveToolErrors` 统一兜底；
* **中英文加权 Token 估算算法**：区分 ASCII（0.25~0.3）与中文字符（0.6~0.8），彻底消除中文长任务 Token 虚高；
* **模型瞬时网络抖动安全重试**：流式推流前针对 429/5xx 与超时提供有限 1 次受控退避重试，计入预算并产生 `MODEL_RETRY`；
* **状态机事件语义规范化**：分离内部重跑逻辑，新增 `COMPACTION_TRIGGERED` 与 `DELIVERABLE_RETRY` 事件类型；
* **交付物强类型精确物理校验**：废除子串模糊包含，改为基于 Artifact ID 或精确文件名强匹配并校验文件真实存在且非空；
* **目标修订单调版本递增**：`reviseGoal` 强制版本单调递增并原子重建 `TaskPlan`；取消操作使用独立原子状态与 OkHttp 协同即时非阻塞中断。

---

## [1.0.0] - 2026-09-29

> **里程碑定位**：**单智能体确定性执行核心基线**。  
> 标志着 SDK 已完成单智能体确定性执行底座、声明式 DX 开发体验、上下文安全外溢沙箱、语义记忆管理与人机协同审批闭环。

### 核心模块坐标
```xml
<dependency>
    <groupId>com.agent</groupId>
    <artifactId>agent-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 九大企业级核心能力矩阵

#### 1. 确定性 ReAct 执行循环与状态机
* **死循环防护与熔断**：严格受控的思维-行动-观察（ReAct）循环，内嵌工具调用频率与轮次硬上限 (`RunLimits`)；
* **生命周期全状态覆盖**：完整流转并严格约束 `RUNNING`、`WAITING_INPUT`（意图追问澄清）、`WAITING_APPROVAL`（高危审批挂起）、`COMPLETED`、`FAILED`、`CANCELLED`、`STOPPED` 等关键状态；
* **超时与资源驱逐**：等待输入超时自动熔断，LRU 淘汰过期任务，坚决杜绝内存泄漏。

#### 2. 上下文工程与沙箱外溢 (Safe Spillover)
* **Token 预算防爆**：前置 Token 估算器与上下文截断保护；
* **工作区文件隔离沙箱 (`WorkspaceSession`)**：所有模型交互与中间过程产物严格隔离在独立的工作区沙箱中；
* **超大结果集自动外溢**：当工具输出（如海量 SQL 结果）超过安全阈值（默认 16384 字节，`RunLimits.java` 可配置）时，自动持久化至沙箱外溢文件，仅向上层上下文返回摘要与相对路径，有效防御模型 Token 超支。

#### 3. 人机协同高危操作拦截审批 (HITL)
* **受控风险分级 (`RiskLevel`)**：支持 `READ_ONLY`（只读安全调用自动放行）与 `HIGH_RISK`（高危写操作/敏感变更自动拦截挂起）；
* **受控挂起与恢复**：高危工具执行前自动截断并挂起为 `WAITING_APPROVAL`；
* **审批 API 闭环**：通过 `agentRunner.approve()` 恢复执行或 `agentRunner.reject()` 反馈驳回理由并合规收敛。

#### 4. 结构化目标管理与交付物审查 (`TaskBrief`)
* **动态约束演进**：支持意图解析与口径澄清后动态细化时间范围、指标口径与分析维度（`goalVersion` 演进）；
* **硬性准出防线 (`CompletionCheck`)**：任务完成前严格审查交付物（如多章节 Markdown 研报、ECharts 图表配置），严禁未对齐即退出的幻觉。

#### 5. `@AgentTool` 声明式工具体系
* **极简 Spring Bean 注解**：告别底层手写 JSON Schema，直接在 Spring 组件方法上标注 `@AgentTool`；
* **Java 21 Record 参数打平**：单 Record DTO 自动展开为顶层 Schema，完全对齐主流大模型 Tool Calling 标准；
* **Bean Validation 自动校验**：通过无三方强依赖的反射探测机制，自动执行 `@NotBlank`、`@Size`、`@Min` 等数据合法性校验，非法入参直接引导模型自我纠错。

#### 6. 短期对话记忆与语义自动摘要压缩 (Summary Compaction)
* **原生多轮会话传递**：`RunRequest` 携带 `conversationId`，自动加载并追加会话历史，避免跨轮次对话失忆；
* **语义摘要压缩器 (`RuleBasedConversationCompactor`)**：当对话超出设定的阈值条数时，自动将早期轮次提炼折叠为结构化系统消息 `【前文对话历史背景摘要】`，保留最新轮次；
* **紧凑上下文保障**：有效压缩历史 Token 占用，提炼保留关键背景约束（注：规则压缩不保证关键细节绝对不遗失，仍需根据业务设置保留轮次与完成检查）。

#### 7. 跨会话长期用户偏好画像 (`UserProfileMemory`)
* **长期画像沉淀**：跨会话管理用户的固定偏好（如房型偏好、分析口径偏好、沟通风格）；
* **自动感知注入**：当 `RunRequest` 传入 `userId` 时，SDK 自动在系统提示词末尾注入 `【用户长期画像与偏好设置】`，新开会话即刻生效。

#### 8. 会话历史磁盘持久化 (`FileBasedConversationMemory`)
* **原子磁盘落盘**：会话历史除内存 LRU 外，支持以原子写入方式落地为 JSON 存储；
* **历史上下文恢复**：应用服务重启后，可根据会话 ID 从磁盘沙箱恢复已完成对话历史（注：仅恢复已完成对话历史，不恢复原任务的未决执行状态或待审批挂起）。

#### 9. 细粒度运行事件与流式分块 (`RunStreamChunk`)
* **细粒度运行事件派发**：支持推送 `text`（文本响应块）、`thinking`（思考内容）、`tool_started` / `tool_finished`（工具生命周期与耗时）、`status`、`complete` 等分块；便于应用层按需接入 SSE / WebSocket（注：内置 Spring AI 适配器当前文本路径为获取完整响应后整段送出，非逐 Token 增量输出）。

---

### 生产场景验证矩阵 (100% 通过)

| 落地工程 | 业务场景特征 | 验证范围 |
| :--- | :--- | :--- |
| **`it-support-agent`** | 企业 IT 智能运维与 SOP 排障 | 状态机、工具重试、向后兼容性 |
| **`hotel-concierge-agent`** | 智慧酒店管家消费服务 | `@AgentTool` 声明式、多轮追问、HITL 退款审批、流式打字机 |
| **`dataAnalyst-agent`** | 商业数据智能体与指标归因 | JSqlParser AST、大数据沙箱外溢、TaskBrief 研报严审、脱敏审批 |

---

### 技术基线与环境依赖
* **JDK Baseline**: Java 21 (强制使用 Record, Pattern Matching, Text Blocks)
* **Spring Boot**: 4.1.1
* **Spring AI**: 2.0.1
* **Core 依赖控制**: `agent-core` 保持 0 多余外部三方依赖（仅 Jackson + Schema Validator）
