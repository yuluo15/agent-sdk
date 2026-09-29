# Agent SDK 变更日志 (CHANGELOG)

本文档记录 Agent SDK 所有正式发布版本的核心特性、架构升级与兼容性声明。
本项目遵循 [Semantic Versioning 2.0.0 (SemVer)](https://semver.org/lang/zh-CN/) 语义化版本规范。

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
* **细粒度风险分级 (`RiskLevel`)**：支持 `LOW`（只读）、`NORMAL`（常规读写）、`HIGH`（高危写/敏感数据导出）、`CRITICAL`（极端操作）；
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
* **原生多轮会话传递**：`RunRequest` 携带 `conversationId`，自动加载并追加会话历史，彻底解决“跨轮次大模型失忆”；
* **语义摘要压缩器 (`RuleBasedConversationCompactor`)**：当对话超出设定的阈值条数时，自动将早期轮次提炼折叠为结构化系统消息 `【前文对话历史背景摘要】`，保留最新轮次；
* **无限轮次保障**：在保证上下文 Token 极低的前提下，大模型永不遗忘最初的关键诉求与约束。

#### 7. 跨会话长期用户偏好画像 (`UserProfileMemory`)
* **长期画像沉淀**：跨会话管理用户的固定偏好（如房型偏好、分析口径偏好、沟通风格）；
* **自动感知注入**：当 `RunRequest` 传入 `userId` 时，SDK 自动在系统提示词末尾注入 `【用户长期画像与偏好设置】`，新开会话即刻生效。

#### 8. 断点防灾与磁盘恢复 (`FileBasedConversationMemory`)
* **原子磁盘落盘**：会话历史除内存 LRU 外，支持以原子写入方式落地为 JSON 存储；
* **重启自愈**：应用服务重启后，根据会话 ID 自动从磁盘沙箱恢复历史上下文。

#### 9. 全双工 SSE 流式打字机响应
* **细粒度实时事件派发**：流式推送 `text`（正文 Token 打字机）、`thinking`（模型思考流）、`tool_started` / `tool_finished`（工具调用动效）、`status`、`complete` 等事件；
* **交互体验升级**：彻底告别长达数十秒的黑盒整轮阻塞等待。

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
