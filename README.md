# Agent SDK

企业级高可靠、确定性 AI Agent 运行时引擎与开发套件。

## 核心特性

- **纯粹执行内核 (`agent-core`)**：基于 Java 21 原生特性（Records、Pattern Matching），无重量级第三方框架依赖，毫秒级冷启动。
- **上下文工程与沙箱外溢**：内置精确 Token 预算估算，超大工具输出自动外溢落盘到 `WorkspaceSession` 沙箱，生成紧凑索引，彻底阻断 Token 爆炸。
- **全双工流式响应**：支持 SSE / WebSocket 实时推送打字机 Token、思维链 Thought、工具调用过程与状态。
- **结构化目标与动态纠偏**：引入 `TaskBrief` 管理多约束目标，支持在多轮交互中动态修正目标与约束（`reviseGoal`）。
- **人机协同安全网关 (HITL)**：工具原生支持 `RiskLevel.READ_ONLY` 与 `RiskLevel.HIGH_RISK`，高危写操作自动拦截并挂起为 `WAITING_APPROVAL`，支持审批通过与拒绝。
- **会话治理与自动清理**：闲置会话超时驱逐（`waitingTimeout`），LRU 最大容量控制，会话结束自动安全擦除 `.temp/` 临时文件。
- **声明式工具体验 (`@AgentTool`)**：使用普通 Java 方法打上注解，自动提取 Draft 2020-12 JSON Schema，自动处理参数反序列化与隐式参数注入。
- **流式场景组装器 (`ScenarioPluginBuilder`)**：无需编写冗余样板代码，数行代码即可拼装企业级业务场景。

## 模块结构

```text
agent-sdk/
├── agent-core                  # 纯 Java 21 执行内核、状态机、工具与工作区
├── agent-model-spring-ai       # Spring AI / OpenAI / DeepSeek 模型适配器
├── agent-spring-boot-starter   # Spring Boot 自动装配、@AgentComponent 发现
├── agent-samples               # 现存集成与场景样例
└── dosc/                       # 架构设计与实施规范文档
    └── 17-Agent-SDK架构体系与演进路线规划.md
```

## 架构体系与演进规划

详见核心规划文档：[17-Agent-SDK架构体系与演进路线规划.md](dosc/17-Agent-SDK架构体系与演进路线规划.md)
