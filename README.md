# Agent SDK

面向企业高控场景的确定性 AI Agent 运行时引擎与开发套件。

## 核心特性

- **纯粹执行内核 (`agent-core`)**：基于 Java 21 原生特性（Records、Pattern Matching），无重量级第三方框架依赖，启动轻量、内存占用低。
- **上下文工程与沙箱外溢**：内置中英文加权 Token 预算前置防御；超大工具输出达到阈值（默认 16384 字节，可配置）自动外溢落盘到 `WorkspaceSession` 沙箱，生成紧凑索引，有效防御长文本超出模型上下文预算。
- **细粒度运行分块与流式感知**：支持通过 `RunStreamChunk` 监听器实时捕获思考过程（Thought）、工具调用生命周期（`TOOL_STARTED` / `TOOL_FINISHED`）、文本增量分块与执行状态，便于宿主应用对接 SSE / WebSocket。
- **结构化目标与动态纠偏**：引入 `TaskBrief` 管理多约束目标，支持在多轮交互中动态单调递增修正目标与约束（`reviseGoal`），并同步原子重建任务规划（`TaskPlan`）。
- **人机协同安全网关 (HITL)**：工具原生支持 `RiskLevel.READ_ONLY` 与 `RiskLevel.HIGH_RISK`，高危写操作自动拦截并挂起为 `WAITING_APPROVAL`，支持审批通过与拒绝。
- **多会话管理与超时治理**：提供开箱即用的 `SessionRunnerManager`，支持按会话隔离 Runner；支持闲置会话超时驱逐（`waitingTimeout`）与 LRU 最大容量控制，活跃任务绝对保护；会话结束自动安全擦除 `.temp/` 临时文件。
- **MCP 与企业技能生态 (`agent-mcp-adapter`)**：集成官方 Model Context Protocol (MCP 0.17.1) SDK，支持远程 HTTP/SSE 传输与本地 Stdio 子进程生命周期管理；支持标准 `SKILL.md` (agentskills.io 规范) 技能文件解析与目录自动加载，以及运行时自主咨询（`consult_skill`）。
- **声明式工具体验 (`@AgentTool`)**：使用普通 Java 方法打上注解，自动提取 Draft 2020-12 JSON Schema，自动处理参数反序列化与隐式参数注入。
- **流式场景组装器 (`ScenarioPluginBuilder`)**：无需编写冗余样板代码，数行代码即可拼装业务场景。

## 模块结构

```text
agent-sdk/
├── agent-core                  # 纯 Java 21 执行内核、状态机、工具、工作区与记忆 SPI
├── agent-model-spring-ai       # Spring AI / OpenAI / DeepSeek 模型适配器
├── agent-mcp-adapter           # Model Context Protocol (MCP) 客户端、工具适配器与企业 Skills
├── agent-spring-boot-starter   # Spring Boot 自动装配、@AgentComponent 发现与 MCP 配置
├── agent-samples               # 现存集成与场景样例
└── docs/                        # 架构设计、使用场景手册与演进指南
```

评测系统 `agent-sdk-eval` 位于 SDK 仓库之外，不属于 SDK 发布物。

## 文档指引体系

- **[工程加固与治理建设 Backlog (docs/23-Agent-SDK工程加固与治理建设Backlog.md)](docs/23-Agent-SDK工程加固与治理建设Backlog.md)**（**当前研发基准**）：修正后的阶段门（P0基线 $\rightarrow$ P1治理闭环 $\rightarrow$ Gate A攻防验证 $\rightarrow$ P2持久化 $\rightarrow$ P3生态）。
- **[全量优化任务清单 (docs/22-Agent-SDK全量优化任务清单.md)](docs/22-Agent-SDK全量优化任务清单.md)**（**历史跟踪记录**）：原 17 项全量演进任务历史记录。
- **[使用场景手册 (docs/使用场景手册.md)](docs/使用场景手册.md)**（**官方推荐首读**）：定义 SDK 单 Agent 核心定位、能力矩阵、十类企业场景设计卡与边界。
- **[演进建议 (docs/演进建议.md)](docs/演进建议.md)**（**原则与边界**）：务实质量基线、设计红线；具体执行顺序以 18 号 ADR 为准。
- **[企业化演进 ADR (docs/18-Agent-SDK企业化演进决策记录-ADR.md)](docs/18-Agent-SDK企业化演进决策记录-ADR.md)**（**当前执行路线**）：单 Agent 边界、Phase 0~4 阶段门、技术雷达与回滚策略。
- **[Phase 0 评测设计 (docs/19-Phase-0-评测设计.md)](docs/19-Phase-0-评测设计.md)**（**试点执行手册**）：IT 技术支持只读场景、数据集 schema、断言、报告模板与退出条件。
- **[使用手册 (使用手册.md)](使用手册.md)**：开发接入指南、配置清单、代码示例与 CLI 运行。
- **[架构体系历史参考 (docs/17-Agent-SDK架构体系与演进路线规划.md)](docs/17-Agent-SDK架构体系与演进路线规划.md)**：早期演进全景参考（注：阶段 3.5 多 Agent Swarm 与 4.0 强一致持久化已按《演进建议》自核心剥离）。
