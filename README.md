# Agent SDK

企业级高可靠、确定性 AI Agent 运行时引擎与开发套件。

## 核心特性

- **纯粹执行内核 (`agent-core`)**：基于 Java 21 原生特性（Records、Pattern Matching），无重量级第三方框架依赖，毫秒级冷启动。
- **上下文工程与沙箱外溢**：内置精确 Token 预算估算，超大工具输出自动外溢落盘到 `WorkspaceSession` 沙箱，生成紧凑索引，彻底阻断 Token 爆炸。
- **细粒度运行分块与流式感知**：支持通过 `RunStreamChunk` 监听器实时捕获思考过程（Thought）、工具调用生命周期（`TOOL_STARTED` / `TOOL_FINISHED`）、文本分块与执行状态，便于宿主应用对接 SSE / WebSocket。
- **结构化目标与动态纠偏**：引入 `TaskBrief` 管理多约束目标，支持在多轮交互中动态修正目标与约束（`reviseGoal`）。
- **人机协同安全网关 (HITL)**：工具原生支持 `RiskLevel.READ_ONLY` 与 `RiskLevel.HIGH_RISK`，高危写操作自动拦截并挂起为 `WAITING_APPROVAL`，支持审批通过与拒绝。
- **会话治理与自动清理**：闲置会话超时驱逐（`waitingTimeout`），LRU 最大容量控制，会话结束自动安全擦除 `.temp/` 临时文件。
- **MCP 与企业技能生态 (`agent-mcp-adapter`)**：遵循 MCP (2024-11-05) 协议，支持外部 Stdio 子进程工具桥接、防僵尸进程治理，以及企业 SOP 技能注册与自主咨询（`consult_skill`）。
- **声明式工具体验 (`@AgentTool`)**：使用普通 Java 方法打上注解，自动提取 Draft 2020-12 JSON Schema，自动处理参数反序列化与隐式参数注入。
- **流式场景组装器 (`ScenarioPluginBuilder`)**：无需编写冗余样板代码，数行代码即可拼装企业级业务场景。

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

## 文档指引体系

- **[使用场景手册 (docs/使用场景手册.md)](docs/使用场景手册.md)**（**官方推荐首读**）：定义 SDK 单 Agent 核心定位、能力矩阵、十类企业场景设计卡与边界。
- **[演进建议 (docs/演进建议.md)](docs/演进建议.md)**（**官方技术路线**）：务实质量基线、P0~P3 演进节奏与设计红线。
- **[使用手册 (使用手册.md)](使用手册.md)**：开发接入指南、配置清单、代码示例与 CLI 运行。
- **[架构体系历史参考 (docs/17-Agent-SDK架构体系与演进路线规划.md)](docs/17-Agent-SDK架构体系与演进路线规划.md)**：早期演进全景参考（注：阶段 3.5 多 Agent Swarm 与 4.0 强一致持久化已按《演进建议》自核心剥离）。
