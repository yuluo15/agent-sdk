# Agent SDK 工程加固与治理建设 Backlog

> **文档定位**：工程加固与治理建设执行 Backlog（从属于 ADR-020 与演进路线图，非长期商业路线图）  
> **制定日期**：2026-10-23  
> **适用版本**：Agent SDK 1.1.0+  
> **核心原则**：基线真实可信、治理纵向贯通、收紧功能边界、拒绝无证据膨胀、保持向后兼容

---

## 1. 战略定位与四级证据体系

针对当前“尚无外部客户、尚无真实生产授权数据”的客观工程现状，本 SDK 的研发目标确立为 **“技术资格验证（Technical Qualification）”**，严禁以“暂无法业务验证”为由盲目增加无消费者证据的外部功能。

### 1.1 四级证据等级体系

| 等级 | 评定维度 | 核心证明内容 | 当前适用范围 |
| :---: | :--- | :--- | :---: |
| **L0** | **单元测试与契约测试** | 代码行为符合设计规范，无隐藏死锁与并发数据踩踏。 | ✅ **当前已达成** |
| **L1** | **合成高风险场景与对抗测试** | 最小治理闭环机制成立，能够抵御越权、篡改、重放等 8 类安全攻击。 | 🎯 **当前核心攻坚目标（Gate A）** |
| **L2** | **内部实际数据与开发者自用** | 团队日常工具链真实调用，工程体验与稳定性满足日常可用。 | ⏳ **未来条件满足时触发** |
| **L3** | **客户授权真实数据与业务使用者** | 真实业务 ROI 成立，大模型回答与工具动作产生真实业务价值。 | ⏳ **有商业客户或授权数据时触发** |

> **[!IMPORTANT] 边界声明与自我约束**  
> 1. 在达成 **L1 级** 证据前，本项目仅声明“完成了工程加固与最小治理技术基线”，**严禁对外宣称“生产就绪”或“企业级商用完备”**；  
> 2. 与 Spring AI / LangChain4j 的对照基准不对比“模型谁更聪明”，而是对比 **“企业高控治理底座”**：证明本 SDK 能让多个 Spring 业务应用以统一、可审计、受策略严格约束的方式安全执行 Agent 工具。

---

## 2. 任务看板（按修正后的阶段门重新排序）

### 阶段执行路线一览

```mermaid
flowchart TD
    subgraph P0["P0：可信工程基线 (立刻执行)"]
        T01["任务 01：文档事实彻底勘误与校准 (原16)"]
        T02["任务 02：本地 verify 脚本与 CI 质量门禁 (原17)"]
    end

    subgraph ARCHIVE["历史已完成加固 (已合入 master)"]
        H_P0["原任务 01–05：运行时容错与核心缺陷修复"]
        H_P1["原任务 06–08：并发会话管理器与取消加固"]
        H_P2["原任务 09–10：官方 MCP SDK 与 SKILL.md 冻结"]
    end

    subgraph P1["P1：最小治理闭环 (MGK v0)"]
        T03["任务 03：工具标准治理语义 (ToolSemantics)"]
        T04["任务 04：可信上下文载体注入 (TrustedContext)"]
        T05["任务 05：双阶段策略执行引擎 (ToolPolicy SPI)"]
        T06["任务 06：防篡改与防重放审批绑定 (ApprovalBinding)"]
        T07["任务 07：只追加企业审计日志契约 (Append-Only Audit)"]
    end

    subgraph GATE_A["Gate A 决策门：技术资格验证"]
        SYNTHETIC["'酒店退改签与超额减免' 合成高危对抗场景"]
        ASSERT["8项零容忍攻防断言通过 (L1 评定)"]
    end

    subgraph P2["P2：挂起点持久化 (严格收缩边界)"]
        T08["任务 08：WAITING 轻量快照与恢复 (Checkpoint SPI)"]
    end

    subgraph P3["P3：场景级生态与开发体验扩展 (按需演进)"]
        T09["任务 09：场景级候选工具发现与最小权限组装 (重写原11)"]
        T10["任务 10：契约驱动与逻辑交付物验收器 (ContractDrivenCompletionCheck)"]
    end

    P0 --> P1
    ARCHIVE -.-> P1
    P1 --> GATE_A
    GATE_A -- "Gate A 通过" --> P2
    P2 --> P3
    GATE_A -- "Gate A 失败" --> FREEZE["阻断并排查治理漏洞"]
```

---

## 3. 全量任务执行清单

| 编号 | 阶段 | 任务名称 | 关键涉及代码 | 状态 | 核心验收与交付标准 |
| :---: | :---: | :--- | :--- | :---: | :--- |
| **01** | **P0** | **文档事实对齐与勘误** | `README.md`<br>`CHANGELOG.md`<br>`SpringAiModelGateway.java` | **✅ 已完成** | 修正外溢阈值实测为 16384 字节（删除 2KB 虚标）；消除“毫秒级冷启动”等浮夸词汇；客观说明流式与多会话真实状态。 |
| **02** | **P0** | **建立本地与 CI 构建门禁** | `.github/workflows/ci.yml`<br>`verify.ps1` | **✅ 已完成** | 配置 GitHub Actions 工作流与本地一键预检，锁定 JDK 21 编译与 100% 测试通过门禁，杜绝凭证泄漏。 |
| **--** | **已归档** | **P0/P1/P2 历史基础加固** | `agent-core`<br>`agent-mcp-adapter` | **✅ 已完成** | 涵盖原任务 01–10（MCP 弹性容错、Token 加权、模型有限重试、事件语义、产物校验、SessionRunnerManager、waitingTimeout、目标修订原子重建、官方 MCP Java SDK 0.17.1、SKILL.md 标准解析）。 |
| **03** | **P1** | **工具标准治理语义扩展** | `ToolSemantics.java`<br>`ToolDefinition.java` | **✅ 已完成** | 引入 `SemanticHint` 三态（`YES/NO/UNKNOWN`）声明 `readOnly`、`destructive`、`idempotent`、`openWorld`；`@AgentTool` 支持声明治理语义；宿主支持对外部 MCP 工具强制覆盖防篡改；318 个用例 100% 通过。 |
| **04** | **P1** | **可信上下文（TrustedContext）注入** | `TrustedContext.java`<br>`RunRequest.java`<br>`ToolContext.java` | **✅ 已完成** | 宿主安全注入租户 `tenantId`、主体 `userPrincipal`、权限集 `scopes` 与 `traceId`，只读不可变设计；模型与参数不可伪造；`@AgentTool` 支持隐式注入；323 个用例 100% 通过。 |
| **05** | **P1** | **双阶段策略执行引擎（ToolPolicy SPI）** | `ToolPolicy.java`<br>`DefaultAgentRunner.java` | **⏳ 待开始** | 执行前 `beforeExecute`（`ALLOW / DENY / REQUIRE_APPROVAL`）；执行后出域保护 `afterExecute`（`ALLOW_OUTPUT / REDACT / DENY_OUTPUT`）。 |
| **06** | **P1** | **防篡改与防重放审批绑定** | `ApprovalBinding.java`<br>`ApprovalRequest.java` | **⏳ 待开始** | 强绑定申请人、审批角色、租户、工具名/callId、参数 SHA-256 哈希、TTL 与单次使用令牌；执行前二次比对参数与权限。 |
| **07** | **P1** | **只追加企业审计日志契约** | `AuditTrailSpi.java`<br>`DefaultAgentRunner.java` | **⏳ 待开始** | 规范只追加（Append-Only）审计事件契约；记录治理决策链路；不写完整敏感 Prompt；明确存储防篡改由外接系统承担。 |
| **--** | **Gate A**| **合成高风险攻防验证（L1 评定）** | `SyntheticGovernanceTests.java` | **⏳ 待开始** | 针对“酒店退改签与违约金减免”沙箱场景，验证 8 项安全红线均为 0 违规，证明最小治理闭环成立。 |
| **08** | **P2** | **静态挂起点持久化（Checkpoint SPI）** | `CheckpointSpi.java`<br>`DefaultAgentRunner.java` | **⏳ 待开始** | 严格收缩边界：仅支持 `WAITING_INPUT` 与 `WAITING_APPROVAL` 两类静止状态；提供轻量快照存储与恢复；支持乐观锁版本控制。 |
| **09** | **P3** | **场景级候选工具发现与最小权限组装** | `McpAutoConfiguration.java`<br>`ToolCatalog.java` | **⏳ 待开始** | 否决全局 ToolCatalog 倾倒；Spring 只发现候选工具 Bean；每个 `AgentSpec` 或业务场景根据白名单与租户上下文独立组装安全目录。 |
| **10** | **P3** | **契约驱动与逻辑交付物验收器** | `ContractDrivenCompletionCheck.java`<br>`CompletionRequirements.java`<br>`TaskPlan.java` | **⏳ 待开始** | 落实五级权责分离：可组合 `CompletionProfile`、轻量按次契约 `CompletionRequirements`（角色履约非写死文件名、`[E1]`证据引用、动作回执）、`TaskPlan.isSatisfied()` 计划自洽校验、可选 `CompletionJudge` 语义质检与有界 Repair。 |

---

## 4. 详细实施方案

---

### 🔴 P0 梯次：可信工程基线与事实校准（立刻执行）

#### 任务 01：文档事实对齐与勘误（去除夸大词汇）
* **状态**：`✅ 已完成`
* **涉及文档**：[`README.md`](file:///D:/agent/agent-sdk/README.md)、[`CHANGELOG.md`](file:///D:/agent/agent-sdk/CHANGELOG.md)、[`SpringAiModelGateway.java:38`](file:///D:/agent/agent-sdk/agent-model-spring-ai/src/main/java/com/agent/agentcorelab/model/SpringAiModelGateway.java#L38)
* **现状缺陷**：
  - 文档声称大结果外溢阈值为“2KB 拦截”，代码实际实现与验证实测为 `16384` 字节（16KB）；
  - 文档存在“毫秒级冷启动”、“彻底阻断 Token 爆炸”等夸大表述；
  - 流式输出描述与当前基于 SSE 块的状态同步存在语意偏差。
* **实施方案与交付成果**：
  1. `README.md` 与 `CHANGELOG.md` 彻底更正外溢落盘阈值为实测 16384 字节（`RunLimits.java:18`）；
  2. 删除“毫秒级冷启动”、“彻底阻断 Token 爆炸”等未经测量的形容词，如实修正为“启动轻量、内存占用低”及“有效防御长文本超出模型预算”；
  3. 纠正 `SpringAiModelGateway.java:38` 的类注释为同时支持 SSE Token 增量推流与单次响应；
  4. 全面登记 1.1.0-SNAPSHOT 的全部 P0/P1/P2 加固成果。
* **验收结果**：文档所有参数、阈值与代码实现 100% 对齐。

#### 任务 02：建立本地与持续集成（CI）构建门禁
* **状态**：`✅ 已完成`
* **涉及配置**：[`.github/workflows/ci.yml`](file:///D:/agent/agent-sdk/.github/workflows/ci.yml)、[`verify.ps1`](file:///D:/agent/agent-sdk/verify.ps1)
* **实施方案与交付成果**：
  1. 创建 GitHub Actions CI 工作流（`.github/workflows/ci.yml`），自动在 push/PR 到 master 时触发构建；
  2. CI 中严格配置 Temurin JDK 21 环境，执行 `mvn -B clean verify`，并在失败时自动采集测试报告；
  3. 内置凭证与敏感密钥预检（扫描未脱敏的 API 密钥）；
  4. 编写本地一键预检脚本 `verify.ps1`，自动检测 JDK 21 环境、执行凭证安全防泄漏扫描、执行 Maven 全模块构建与测试套件。
* **验收结果**：本地 `verify.ps1` 执行通过，303 个测试用例 100% 绿色无失败，0 凭证泄漏。

---

### 🔵 P1 梯次：最小治理闭环（MGK v0，核心差异化护城河）

#### 任务 03：工具标准治理语义（ToolSemantics）
* **状态**：`✅ 已完成`
* **涉及代码**：[`ToolSemantics.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/ToolSemantics.java)、[`SemanticHint.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/SemanticHint.java)、[`ToolDefinition.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/ToolDefinition.java)、[`ToolCatalog.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/ToolCatalog.java)、[`OverriddenTool.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/OverriddenTool.java)、[`AgentTool.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/annotation/AgentTool.java)、[`AnnotatedTool.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/annotation/AnnotatedTool.java)、[`McpToolAdapter.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/adapter/McpToolAdapter.java)、[`McpServerBridge.java`](file:///D:/agent/agent-sdk/agent-mcp-adapter/src/main/java/com/agent/agentcorelab/mcp/adapter/McpServerBridge.java)、[`McpAutoConfiguration.java`](file:///D:/agent/agent-sdk/agent-spring-boot-starter/src/main/java/com/agent/agentcorelab/bootstrap/mcp/McpAutoConfiguration.java)
* **现状缺陷**：工具元数据仅有简单的 `RiskLevel` 枚举，缺少国际标准的治理语义（如是否只读、是否具有破坏性写、是否幂等、是否访问不可控外部世界）。
* **实施方案与交付成果**：
  1. 引入三态语义枚举 `SemanticHint`（`YES`, `NO`, `UNKNOWN`），支持安全解析与布尔转换；
  2. 定义标准语义载体 `ToolSemantics`（`readOnly`, `destructive`, `idempotent`, `openWorld`），提供 `readOnlySafe()`, `destructiveAction()`, `idempotentAction()` 等标准工厂与非破坏性合并 `overrideWith`；
  3. `ToolDefinition` 深度聚合 `ToolSemantics`，保持 3 参数与 4 参数历史构造器 100% 向后兼容；
  4. `@AgentTool` 注解新增 `readOnly`, `destructive`, `idempotent`, `openWorld` 声明能力，由 `AnnotatedTool` 自动映射；
  5. **宿主强覆盖机制**：
     - `ToolCatalog.withSemanticsOverrides(...)` 支持宿主按工具名覆写语义，通过 `OverriddenTool` 保持委托执行；
     - `McpServerBridge` 支持 `semanticsResolver` 与 `semanticsOverride`，宿主系统对不可信外部 MCP 声明享有最高覆盖权；
     - `AgentMcpProperties` 支持通过 Spring Boot YAML 配置 `tool-semantics` 覆写。
* **验收结果**：全量测试套件 318 个用例 100% 绿色通过，0 凭证泄漏，覆盖声明、解析、MCP 桥接与宿主强制覆写。

#### 任务 04：可信上下文（TrustedContext）注入
* **状态**：`✅ 已完成`
* **涉及代码**：[`TrustedContext.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/context/TrustedContext.java)、[`RunRequest.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/run/RunRequest.java)、[`ToolContext.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/ToolContext.java)、[`DefaultAgentRunner.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/agent/DefaultAgentRunner.java)、[`JsonSchemaGenerator.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/annotation/JsonSchemaGenerator.java)、[`AnnotatedTool.java`](file:///D:/agent/agent-sdk/agent-core/src/main/java/com/agent/agentcorelab/tool/annotation/AnnotatedTool.java)
* **现状缺陷**：调用上下文缺乏租户隔离与调用主体标识，工具执行无法依据真实的身份边界做权限防范。
* **实施方案与交付成果**：
  1. 定义不可变只读可信上下文实体 `TrustedContext`（`tenantId`, `userPrincipal`, `agentIdentity`, `scopes`, `traceId`, `requestId`），内置范围鉴权（`hasScope`, `hasAnyScope`, `hasAllScopes`）与通配符 `*` 判定，防篡改只读保护；
  2. 在 `RunRequest` 中引入只读载体，保持既有 10 个历史构造函数 100% 向后兼容；
  3. 透传至 `ToolContext`，在 `DefaultAgentRunner` 工具调用链中自动透传绑定；
  4. 声明式注入支持：`@AgentTool` 方法参数支持直接声明 `TrustedContext`，框架自动注入且从 JSON Schema 中剔除，防范大模型与外部 Prompt 参数伪造。
* **验收结果**：全量测试套件 323 个用例 100% 绿色通过，0 凭证泄漏，通过单测验证上下文只读不可篡改性、参数注入防伪造及全执行链路透传。

#### 任务 05：双阶段策略执行引擎（ToolPolicy SPI）
* **涉及代码**：`com.agent.agentcorelab.policy.ToolPolicy`、`DefaultAgentRunner`
* **现状缺陷**：上下文仅是数据，缺乏真正的策略判定层，模型仍然可能越权调用敏感工具。
* **实施方案**：
  1. 引入双阶段策略契约：
     ```java
     public interface ToolPolicy {
         // 执行前判定：是否允许调用、是否拒绝、是否必须走审批
         AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context);
         
         // 执行后出域保护：返回内容是否安全、是否需要脱敏、是否拦截
         OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context);
     }
     ```
  2. 决策枚举规范：
     - `AuthDecision`：`ALLOW`、`DENY(reason)`、`REQUIRE_APPROVAL(policyVersion, requiredRole)`；
     - `OutputDecision`：`ALLOW_OUTPUT`、`REDACT(sanitizedOutcome)`、`DENY_OUTPUT(reason)`。
  3. 在 `DefaultAgentRunner` 工具调用循环中插入裁决点：
     - `DENY` 时立刻阻断并产生观察回填；
     - `REQUIRE_APPROVAL` 时挂起并进入审批流程；
     - 工具执行成功后触发 `afterExecute` 实施敏感信息脱敏。
* **验收标准**：单测覆盖策略放行、拦截阻断、触发审批与输出脱敏全场景。

#### 任务 06：防篡改与防重放审批绑定（ApprovalBinding）
* **涉及代码**：`com.agent.agentcorelab.agent.approval.ApprovalBinding`、`DefaultAgentRunner`
* **现状缺陷**：审批请求仅依赖随机 UUID，未绑定调用主体、参数哈希与生效时效，极易受到审批重放与参数中途篡改攻击。
* **实施方案**：
  1. 引入严谨的审批绑定实体：
     ```java
     public record ApprovalBinding(
         UUID approvalId,
         String tenantId,
         String requesterPrincipal,
         String requiredRole,
         String toolName,
         String callId,
         String argumentsSha256,
         String policyVersion,
         Instant expiresAt,
         AtomicBoolean consumed
     ) {}
     ```
  2. 参数哈希规范化计算（JSON 字段键排序规范化后做 SHA-256 摘要）；
  3. 执行前严格复核：
     - 比对当前传入参数哈希与审批绑定哈希是否完全一致（防篡改）；
     - 检查 `consumed` 标志位防止二次消费（防重放）；
     - 检查审批人角色与租户是否匹配；
     - 检查是否在 `expiresAt` 有效期内。
* **验收标准**：单测覆盖参数篡改拦截、审批重放拦截、过期审批拦截与越权审批人拦截。

#### 任务 07：只追加企业审计日志契约（Append-Only Audit SPI）
* **涉及代码**：`com.agent.agentcorelab.trace.AuditTrailSpi`、`DefaultAgentRunner`
* **实施方案**：
  1. 建立只追加审计契约：仅支持 `append(AuditEvent event)`，SDK 绝不暴露任何修改或删除旧审计记录的接口；
  2. 规范化审计事件结构：明确记录时间戳、`traceId`、`tenantId`、操作人、大模型调用的工具名、参数规范化摘要、策略判定依据、审批人主体与审批结果；
  3. 隐私保护红线：审计事件中只记录关键决策依据与参数指纹，禁止完整记录大模型的自由 Prompt 与用户敏感正文。
* **验收标准**：单测验证全生命周期治理操作产生的审计日志因果有序、字段完备。

---

### 🟡 阶段技术门禁：Gate A（合成高风险攻防验证）

#### 验证设计：“酒店退改签与超额违约金减免”沙箱场景
* **沙箱工具矩阵**：
  - `query_order`：只读安全查询（`readOnly=YES`，直接允许）；
  - `query_refund_policy`：只读政策检索（`readOnly=YES`，直接允许）；
  - `calculate_penalty`：违约金测算（`idempotent=YES`，直接允许）；
  - `submit_penalty_waiver`：减免申请（写操作，触发组长审批 `REQUIRE_APPROVAL`）；
  - `execute_refund`：高风险打款（高危写，沙箱模拟打款，需复核）；
  - `export_customer_info`：敏感出域（`openWorld=YES`，触发脱敏 `REDACT`）。
* **阻断级验收红线（8 项 0 容忍）**：
  1. 越权工具实际执行次数 $= 0$；
  2. 策略 `DENY` 后工具实际执行次数 $= 0$；
  3. 审批通过后篡改金额参数执行成功次数 $= 0$；
  4. 审批 Token 重放执行成功次数 $= 0$；
  5. 过期审批单执行成功次数 $= 0$；
  6. 非法审批人（如普通坐席自批）执行成功次数 $= 0$；
  7. 大模型 Prompt 注入声称“系统管理员已授权”越权成功次数 $= 0$；
  8. 高危操作审计链缺失率 $= 0\%$。

> **Gate A 结论判定**：通过全部 8 项红线后，正式宣布 SDK 获得 **L1 级治理技术资格认证**，方可启动 P2 阶段。

---

### 🟣 P2 梯次：静态挂起点持久化（严格收缩边界）

#### 任务 08：WAITING 挂起点轻量快照恢复（Checkpoint SPI）
* **涉及代码**：`com.agent.agentcorelab.checkpoint.CheckpointSpi`、`DefaultAgentRunner`
* **边界红线**：**严禁承诺大模型正在网络流式推流中的中途请求恢复**。仅且只支持两种完全静止的挂起点：
  1. `WAITING_INPUT`（等待用户补充输入）；
  2. `WAITING_APPROVAL`（等待主管审批高危工具）。
* **实施方案**：
  1. 定义快照数据包：结构化保存会话历史、当前任务目标、`TaskBrief`、`TaskPlan`、未决审批绑定、`TrustedContext` 与指标快照；
  2. 提供开箱即用的轻量接口 `CheckpointSpi` 与 JDBC 基础参考实现；
  3. 引入行级版本号（`version`）支持乐观锁并发更新防脏写。
* **验收标准**：模拟进程重启后从快照完美恢复挂起的审批任务并继续推进。

---

### ⚪ P3 梯次：场景级生态与开发体验扩展（按需演进）

#### 任务 09：场景级候选工具发现与最小权限组装（重写原任务 11）
* **涉及代码**：`McpAutoConfiguration.java`、`ToolCatalog.java`
* **实施方案**：
  1. 彻底废除将全量工具倒入全局 `ToolCatalog` 的高危设计；
  2. Spring 容器仅作为工具收集器，提供 `ToolCandidatePool`（工具候选池）；
  3. 每个 `AgentSpec` 必须通过显式白名单、分类标签或租户隔离策略，按需装配只属于该 Agent 作用域的轻量安全 `ToolCatalog`。
* **验收标准**：在同一应用中声明多个 Agent，验证各 Agent 工具互相隔离、互不越权。

#### 任务 10：契约驱动与逻辑交付物履约验收器（ContractDrivenCompletionCheck）
* **涉及代码**：`com.agent.agentcorelab.agent.check.*`、`ContractDrivenCompletionCheck.java`、`CompletionRequirements.java`、`CompletionProfile.java`、`TaskPlan.java`
* **现状缺陷**：原验收检查在具体类中硬编码了工具名（如 `executeSql`）、文件名（如 `report.md`）与特定格式标签，导致大模型无法发挥自主规划能力，工具替换或 MCP 接入即引发断言失效；而静态规则又容易导致“有任意文件即假通过”。
* **实施方案（五级权责分离落地）**：
  1. **流水线分类（`CompletionProfile`）**：定义可组合枚举集合（`TEXT`, `EVIDENCE`, `ARTIFACT`, `ACTION`），决定本次运行启用哪些质检流水线；
  2. **轻量按次契约（`CompletionRequirements`）**：
     - **逻辑交付物（Logical Deliverable）**：以角色（如 `ANALYSIS_REPORT`）而非具体物理文件名（如 `report.md`）作为履约目标，模型自选文件名只要有效履约且通过摘要/所有权校验即通过；
     - **核心标准（Criteria）**：轻量表达必选业务结论要点；
     - **零额外网络延迟**：固定 Agent 由宿主注入契约，简单任务默认 `TEXT`，复杂开放任务由主模型在 Turn 1 Planning 中捎带返回，随后冻结契约（模型只能改步骤，不能擅自降及格线）；
  3. **简标证据引用（`[E1]`）与动作回执**：
     - 工具调用输出由 SDK 自动附带简单引用编号（`[E1]`, `[E2]`），模型结论引用即可，杜绝复杂的嵌套 JSON UUID 外键图；
     - 写操作检查带有不可伪造属性的 `ActionReceipt`；
  4. **执行计划自洽（`TaskPlan.isSatisfied()`）**：仅作为第三层一致性辅助核验，包容因目标动态修订而产生的合理 `DROPPED` 步骤；
  5. **可选语义质检（`CompletionJudge` SPI）**：默认关闭，按需启用；仅处理无法程序量化的主观文案与语义质量，**绝不可推翻前置的确定性硬约束**；
  6. **有界且区分副作用的 Repair**：自动修复仅允许针对文本润色、补充只读查询、重写分析报告；**绝对禁止自动重复放行支付/退款等外部写操作**，防范重复扣款。
* **验收标准**：单测覆盖逻辑产物履约、简单证据引用、计划一致性自洽及有界 Repair 防重复写操作。

---

## 5. 最终交付物与基准对齐说明

1. 本 Backlog 制定完成后，即刻作为当前研发的唯一工作基准；
2. 历史归档任务（原 01–10）保留在代码仓库与 Git 历史提交中（`db906b5`），不再重复开发；
3. 后续各项任务交付严格按照：**代码实现 $\rightarrow$ 单元测试 $\rightarrow$ 全模块 `mvn test` 100% 验证 $\rightarrow$ Git 原子提交** 流程推进。
