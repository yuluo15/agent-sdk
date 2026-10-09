# Agent SDK 变更日志 (CHANGELOG)

本文档记录 Agent SDK 所有正式发布版本的核心特性、架构升级与兼容性声明。
本项目遵循 [Semantic Versioning 2.0.0 (SemVer)](https://semver.org/lang/zh-CN/) 语义化版本规范。

---

## [1.1.0-SNAPSHOT] - 2026-09-29

> **里程碑定位**：**阶段 3.0 - MCP 标准化与企业能力生态 (Model Context Protocol & Enterprise Skills)**。  
> 标志着 SDK 正式具备与 Anthropic MCP 生态无缝互通的工业级能力，支持接入外部海量生态工具（文件系统、Git、数据库、网络搜索等），并引入企业级标准化作业程序（Skills & SOP）自主加载与前提校验闭环。

### 新增核心模块
```xml
<dependency>
    <groupId>com.agent</groupId>
    <artifactId>agent-mcp-adapter</artifactId>
    <version>1.1.0-SNAPSHOT</version>
</dependency>
```

### 核心特性矩阵

#### 1. 纯净自主可控的 MCP 协议栈 (`agent-mcp-adapter`)
* **零臃肿三方依赖**：完全基于 Java 21 与 Jackson 自研实现 Model Context Protocol (MCP `2024-11-05`) 标准规范，无任何第三方 MCP 框架强绑定；
* **强类型不可变消息帧**：全面采用 Java 21 `sealed interface`、`record` 与模式匹配，封装 JSON-RPC 2.0 请求、响应、通知与错误对象；
* **双模传输层 SPI (`McpTransport`)**：
  * **`StdioProcessTransport`**：生产级子进程传输，利用 Java 21 虚拟线程 (`Thread.ofVirtual()`) 高并发读取标准输入输出，内嵌 JVM 停机钩子与多级子进程递归清理看门狗（防僵尸进程）；
  * **`InMemoryMcpTransport`**：支持快速单测与内存级嵌入式模拟；
* **高可用客户端 (`McpClient` / `DefaultMcpClient`)**：管理 `CREATED` -> `INITIALIZING` -> `READY` -> `CLOSED` 严格状态流转，支持握手初始化、工具枚举、工具执行与 `ping` 心跳探测。

#### 2. 无缝工具桥接与上下文沙箱外溢
* **标准工具适配器 (`McpToolAdapter`)**：自动将远端 MCP 工具转换为原生 SDK `Tool` 合约；规范化工具入参校验，保障工具命名规范与合规性；
* **工具前缀隔离与过滤**：支持指定服务名前缀（如 `sqlite_`、`git_`）防止不同 MCP Server 工具命名冲突；支持白名单/黑名单过滤；
* **风险等级映射 (`RiskLevel`)**：可根据工具名称前缀或规则自动标定高危操作（如写表、删除、执行脚本），无缝接入 SDK 既有的人机协同 (HITL) 审批流水线；
* **沙箱大结果集安全外溢**：远端工具返回海量结构体或超大文本时，自动触发 `ToolExecutor` + `WorkspaceSession` 存储外溢机制，将大结果保存为工作区条目并提供增量切片读取，彻底杜绝 Token 撑爆。

#### 3. 企业级能力生态与动态 SOP 体系 (`com.agent.agentcorelab.mcp.skill`)
* **标准化企业技能实体 (`SkillDefinition`)**：将企业复杂业务逻辑规范化为可插拔的技能资产，内含业务描述、标签分类、前置依赖工具列表 (`prerequisiteTools`) 与标准操作指导书 (`sop`)；
* **企业技能注册中心 (`SkillRegistry` / `DefaultSkillRegistry`)**：线程安全、并发友好的中央技能池，支持按标签聚合、模糊搜索与实时查询；
* **精简 Prompt 技能引流 (`SkillPromptContributor`)**：仅在系统提示词末尾注入紧凑的技能索引与一句话导读，零 Token 负担；
* **智能体自主咨询系统工具 (`SkillConsultantTool` / `consult_skill`)**：大模型可在识别到特定任务需求时，主动调用 `consult_skill` 查询对应技能的完整 SOP，并自动获得运行环境中前置工具依赖的完备性诊断。

#### 4. Spring Boot 声明式自动装配 (`agent-spring-boot-starter`)
* **开箱即用属性配置 (`AgentMcpProperties`)**：在 `application.yml` 中即可配置多个远端 MCP 进程；
* **集中化生命周期管理 (`McpServerManager`)**：随 Spring 上下文容器启动自动探活并枚举工具，优雅停机时统一回收全部子进程资源。

---

## [1.0.0] - 2026-09-29

> **里程碑定位**：**首个企业级生产商用就绪基线 (Production-Ready Baseline)**。  
> 标志着 SDK 已完成单智能体确定性执行底座、声明式 DX 开发体验、上下文安全防爆沙箱、长短期语义记忆管理与人机协同审批闭环，并通过了多行业复杂实战检验。

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
* **超大结果集自动外溢**：当工具输出（如海量 SQL 结果）超过安全阈值（>2KB）时，自动持久化至沙箱外溢文件，仅向上层上下文返回摘要与相对路径，彻底杜绝 Token 撑爆。

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
