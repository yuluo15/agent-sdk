# 技术支持 Agent：工具契约、调用样例与验收

版本：v0.1 · 2026-09-22  
配套：[15-核心工作流与决策边界](15-技术支持Agent：核心工作流与决策边界.md)  
范围：7 个受控工具的实现规格、虚构样例与测试要求；没有创建源码、夹具文件、数据库或 MCP 进程。

## 1. 共同契约先保持一致

### 1.1 调用与执行

- 模型只能给出工具名和参数；caseId、runId、目录、凭据、权限集合和状态模式由应用注入。
- 所有输入是 JSON 对象，拒绝未知字段、重复键、错误类型和不合格引用；不是忽略多余字段后继续。
- 除明确可空字段外，不接受 null 代替必填值；标识均为不超过 64 字符的非空不透明字符串，不拼接成文件路径或 SQL。
- 参数与返回验证通过不等于有权限；执行端仍检查当前案例、目标绑定及撤回／禁用状态。
- 工具返回 ToolResult；是否终止由可信 ToolOutcome 表达，控制字段不能由模型、文档或远端正文填写。
- 本文的 JSON 均为逻辑载荷示例，不绑定某个 Spring AI／MCP SDK 的实际消息封装。

成功结果包含 `ok=true`、`data`，适用时有程序生成的 `evidenceId` 或 `artifactId`。失败包含 `ok=false`、`error.code`、`error.message`；失败结果不登记成功业务证据。

最重要的区分：**`ok=true, processingState=FAILED` 是成功查询到失败任务；`ok=false` 是这次查询没有取得合格观察。**

### 1.2 证据与调用记录

证据记录至少包含：evidenceId、runId、来源类型、产生它的 callId、目标对象、来源版本、内容摘要、定位以及适用的采样时间／snapshotId。模型不能自行登记证据。

| 类型 | 本场景用途 | 限制 |
|---|---|---|
| CASE_SNAPSHOT | 本次案例原文与输入元信息 | 新增场景来源类型，只证明案例陈述，不证明后台实际状态 |
| USER_INPUT | 控制台本次有效补充输入 | 新增场景来源类型，不授予权限，也不是服务端诊断 |
| EXTERNAL_OBSERVATION | 任务／索引记录 | sourceMode 必须注明 FIXTURE_SNAPSHOT、AUTHORIZED_EXPORT 或 LIVE；虚构与导出记录不是实时观察 |
| KNOWLEDGE_EXCERPT | 回读的适用版本知识 | 搜索摘录、草稿与不适用版本不能替代 |

搜索调用另有 searchReceiptId，记录查询、过滤范围、返回和截断信息；它证明“查过什么”，不作为产品事实引用。

被引用内容在本 Run 内可回读；相同快照／原文的重复读取可以重用证据或登记新观察，但不能仅凭新 ID 算新进展。真实凭据、无关客户信息和原始敏感日志不进入这些记录。

## 2. readSupportCase：只读当前案例

输入固定为空对象：

```json
{}
```

程序从 Run 上下文取 caseId，不接受模型指定其他案例。

返回 data：

| 字段 | 类型／含义 |
|---|---|
| caseId、caseRevision | 案例标识和已锁定内容版本 |
| productKey | 已批准的产品，不从正文提取授权 |
| customerDescription | 去敏后的问题描述，最多 2,000 字符 |
| reportedProductVersion | 字符串或 null；仅为案例陈述 |
| reproduction | 已提供的复现描述／发生时间，或 null；不是已由系统复现 |
| originalJobId | 案例最初提供的任务标识或 null |
| targetJobId | 当前可信绑定或 null，明确是运行信息，不属于原始案例陈述 |
| statusBasis、snapshotId | 程序注入的调查依据模式与快照标识 |

同时返回 CASE_SNAPSHOT 的 evidenceId；该证据覆盖原始案例内容，不把随后补充的绑定偷偷写回旧案例证据。

返回只含当前案例，不列出所有工单／所有获准任务，不包含路径、凭据和联系人的完整信息。修改输入文件导致摘要变化时 FAILED/INPUT_CHANGED，不读取一半旧内容一半新内容。

## 3. getImportJob：读取唯一目标导入任务

输入：

```json
{
  "jobId": "J-101"
}
```

调用前必须：案例已成功读取，targetJobId 已由案例或受控补充输入确定，jobId 与该绑定相等并仍在允许范围内。没有绑定时，返回 TARGET_NOT_BOUND，不让模型猜一个标识试查。

### 3.1 成功返回

```json
{
  "ok": true,
  "evidenceId": "EV-J-101",
  "data": {
    "jobId": "J-101",
    "jobRevision": "jr-1",
    "productKey": "starboat-local",
    "productVersion": "1.0",
    "inputFormat": "DOCX",
    "processingState": "FAILED",
    "errorCode": "UNSUPPORTED_FORMAT",
    "importRevision": null,
    "indexId": null,
    "sourceMode": "FIXTURE_SNAPSHOT",
    "snapshotId": "SS-01",
    "asOf": "2026-09-22T09:00:00+08:00"
  }
}
```

上述状态与版本纯属本轮新增的虚构测试约定，不是已有产品或原第三阶段夹具的实际功能。

### 3.2 字段语义

- processingState：RECEIVED／PROCESSING／SUCCEEDED／FAILED／UNKNOWN。
- inputFormat：TXT／MARKDOWN／DOCX／UNKNOWN；未来扩展需更新工具版本，不接收模型自创枚举。
- errorCode：失败时为服务契约中的短错误码；本夹具包括 UNSUPPORTED_FORMAT、PROCESSING_FAILED。成功／处理中为 null，不将堆栈或任意日志冒充错误码。
- productVersion：该任务处理所适用的产品版本，不默认使用最新部署版本。不同处理阶段的版本关系无法确定时，不能自动给出依赖精确版本的建议。
- jobRevision：来源的任务记录修订标识；不等于模型 callId。
- importRevision、indexId：处理成功时必须存在，明确目标导入批次与关联索引；其他状态可为空。工具不根据缺失字段自行猜测。
- asOf：源数据采样时间；snapshotId 和 sourceMode 用于说明它是不是当前观察。

返回主体、产品、快照、状态组合或必填字段不符合契约时 FAILED/INVALID_SOURCE_RESPONSE，不登记成功证据。UNKNOWN 可以作为“来源明确返回未知”记录，但不足以完成诊断。

第一次合格结果使支持上下文解析出产品版本。后续同一目标返回不兼容产品／版本，停止并要求新 Run 核对环境，不静默更换知识范围。

## 4. getIndexStatus：只查该任务对应的内容批次

输入不是任意 indexId，而是先前实际取得的任务证据：

```json
{
  "jobEvidenceId": "EV-J-102"
}
```

程序验证：证据属于当前 Run 和 targetJobId，未失效，任务 SUCCEEDED，且保存了合法 indexId／importRevision。随后从证据取关联标识，再次执行对象授权检查。知道一个 evidenceId 不意味着可以读取其他 Run 或客户的索引。

### 4.1 成功返回

```json
{
  "ok": true,
  "evidenceId": "EV-I-102",
  "data": {
    "jobId": "J-102",
    "jobRevision": "jr-2",
    "indexId": "I-102",
    "targetImportRevision": "batch-102",
    "targetStage": "BUILDING",
    "servesTargetRevision": false,
    "errorCode": null,
    "sourceMode": "FIXTURE_SNAPSHOT",
    "snapshotId": "SS-01",
    "asOf": "2026-09-22T09:00:00+08:00"
  }
}
```

- targetStage：QUEUED／BUILDING／AWAITING_PUBLICATION／PUBLISHED／FAILED／UNKNOWN，描述的是**目标导入批次**，不是整个索引服务的健康状态。
- servesTargetRevision：true／false／null；null 表示来源不能确认。
- PUBLISHED 必须同时有 servesTargetRevision=true；前三种准备状态必须为 false。矛盾结果不作为合格事实采纳。
- FAILED 时有契约错误码，例如 INDEX_BUILD_FAILED；本版不把失败码直接解释为底层资源原因。
- 返回的 jobId、jobRevision、indexId、targetImportRevision 必须与父任务证据关联匹配。

SNAPSHOT 中，父子记录必须来自同一冻结快照且关联有效。不得用另一个快照里碰巧相同的 indexId 拼接。

如果仅能查到“索引 READY”，却不能确认是否覆盖目标批次，应返回 targetStage=UNKNOWN、servesTargetRevision=null，不能推断客户资料已经可用。

已有本 Run 新任务观察时，旧 jobEvidenceId 不能绕过更新；写包检查也必须发现较新的矛盾观察，避免模型挑选更喜欢的旧状态。

## 5. searchKnowledge 与 readKnowledge：候选和证据分开

### 5.1 searchKnowledge

输入沿用第三阶段的形状：

```json
{
  "query": "UNSUPPORTED_FORMAT DOCX 格式支持",
  "topK": 3
}
```

- query：1—300 字符；topK 可省略，默认 5、最大 8。
- caseId、产品、适用版本、集合和索引快照来自上下文，不能作为可放宽的模型参数。
- 必须已取得合格任务观察并解析产品版本；否则 VERSION_NOT_RESOLVED。首版专门调查导入问题，不把一般 FAQ 混成缺标识的诊断任务。
- 返回 searchReceiptId、hits、returnedCount、truncated；每个 hit 含 hitId、sourceId/sourceVersion、标题、定位和有界摘录。
- 零结果是成功查询但没有候选，不是已证明无解决方案；可以在限额内改写。

每个 hitId 绑定当前 Run、锁定知识索引及适用产品版本。程序限制整体 JSON 为 4 KiB，必要时减少候选或缩短导航摘录并如实标记，不能截坏 JSON 或谎报“全部结果”。

### 5.2 readKnowledge

本支持配置先采用第三阶段接口的最小输入子集：

```json
{
  "hitId": "H-FORMAT-1"
}
```

只读本 Run 搜索返回的固定片段，不接受 sourcePath、URL、其他集合或新的产品版本。较长文档在入库时按段落切为有界片段；本版不开放任意邻接范围参数。

返回 KNOWLEDGE_EXCERPT 的 evidenceId，以及 sourceId/sourceVersion、产品适用范围、主题、原文、行号或字符范围、内容摘要。主要结论必须用这个 evidenceId，不接受 hitId 或 searchReceiptId 代替。

### 5.3 为小样例定义可核对的知识事实

为避免把自然语言检查伪装成万能验证器，首版夹具的入库清单可以附少量人工核对的结构化事实；它们与原文版本绑定，不由模型填写或自动升级为权限。

| topic | 本夹具的 policyFacts 举例 |
|---|---|
| FORMAT_SUPPORT | supportedFormats=[TXT, MARKDOWN] |
| JOB_LIFECYCLE | uploadAcceptanceMeansSearchable=false |
| INDEX_LIFECYCLE | processingSuccessMeansSearchable=false、mayPromiseCompletionTime=false |
| SUPPORT_BOUNDARY | unexplainedFailureRequiresManualReview=true、publicationDoesNotProveQuerySuccess=true |

这些字段让检查器能核实有限场景条件，例如 DOCX 不在该版本批准格式集合中；不是宣称它能自动证明所有中文建议正确。导入人员需核对正文与事实表，矛盾不能静默合并；真实产品适配必须重新审核此映射。

知识原文撤回时，即使 hitId、evidenceId 曾经有效，也按第三阶段规则停止当前 Run 的后续使用和交付。没有适用知识时保留缺口，不能回退到未批准历史版本。

## 6. requestClarification：等待的是人工，不是另一次模型回答

输入沿用现有控制工具：

```json
{
  "question": "请提供当前案例对应的导入任务标识。",
  "options": []
}
```

question 为非空文字，支持配置上限 500 字符；options 可省略，最多 4 个短选项。不得索要密码、Token 或引导粘贴完整敏感日志。

处理规则：

1. 独占模型响应批次，先检查总额度和本 Run 澄清次数。
2. Worker 保存原 callId／问题，进入 WAITING_INPUT；此时不回填虚构答案，不再请求模型。
3. 未绑定目标时，支持输入适配器只接受一个获准任务标识，先绑定再回填；非法输入继续等待。
4. 已绑定目标时，普通补充按有界文本接收，保存为 USER_INPUT；不改变后台记录和目标。
5. 回填包含 answer、source=user 和程序生成的 inputEvidenceId。前述例子可回填 answer=J-101；只回填当前问题的原 callId。
6. 明确无法补充必需信息时，控制台可用本场景的 `/answer --unavailable` 记录拒绝补充并结束为 STOPPED/MISSING_REQUIRED_INPUT，不靠模型猜测用户是否拒绝。
7. 取消仍为 CANCELLED；第三次成功发起澄清之前停止为 STOPPED/CLARIFICATION_LIMIT。

CLI 对缺失标识输入的约束属于 SupportInputAdapter，不要求通用 Runner 解析业务字段。用户回答不是模型工具请求，也不因待答期间有答案就重置预算。

已有必要复现信息时不得重复追问；如果目标批次已经发布但客户仍报问题，询问必要的复现描述即可，不收集本场景无权处理的全部业务数据。

## 7. writeSupportPackage：生成可审核的本地交付

### 7.1 为什么单独定义此工具

本工具是支持领域的结构化写入适配器，底层复用已有受控 Artifact 存储和事件链路；不是新建文件服务，也不是改变通用 writeReport 或原销售报告 Schema。

一个 support_package 保存一个 JSON 产物，包含内部摘要、对客草稿和可选升级材料。程序添加身份、目标、快照／时间、来源清单、草稿审核标记。**整个包的 audience=INTERNAL，只有 customerDraft 字段是待人工审核的对客候选。**

不提供 recipient、send、closeTicket、filePath、runId、goalVersion 等模型参数。模型无法通过写包发送消息或修改业务状态。

### 7.2 输入结构

| 字段 | 约束 |
|---|---|
| disposition | GUIDANCE／STATUS_EXPLANATION／ESCALATION |
| diagnosisCode | 15 中定义的六个诊断代码之一；必须匹配 disposition |
| jobEvidenceId | 必填，本 Run 当前可用的目标任务证据 |
| indexEvidenceId | 字符串或 null；涉及索引结论时必填 |
| findings | 1—8 项，每项 id、kind、text、evidenceIds；kind 为 REPORTED／OBSERVED／DOCUMENTED |
| recommendations | 0—3 项，每项 id、text、evidenceIds；GUIDANCE 至少 1 项 |
| unknowns | 0—3 个有界缺口说明；原因未知或冲突升级必须非空 |
| customerDraft | body、findingIds、recommendationIds；正文最多 1,600 字符，引用本包条目 |
| escalation | 非升级为 null；升级时包含 reason、checkedEvidenceIds、questions，至少一个待人工核实问题 |

每个条目 text 最多 500 字符，id 在包内唯一，evidenceIds 每项最多 4 个、全包最多 20 个不同证据；总参数 UTF-8 编码不超过 12 KiB。上限同时满足，不因每字段都合格就突破总上限。

findings 的 REPORTED 只能以 CASE_SNAPSHOT／USER_INPUT 支撑；OBSERVED 用状态观察；DOCUMENTED 用知识原文。把客户一句“后台报错”标为 OBSERVED 应拒绝。

### 7.3 完整输入例子：格式不支持

假定 EV-J-101 为第 3 节的结果，EV-K-FORMAT-1 为相同产品版本的已回读格式说明：

```json
{
  "disposition": "GUIDANCE",
  "diagnosisCode": "FORMAT_UNSUPPORTED",
  "jobEvidenceId": "EV-J-101",
  "indexEvidenceId": null,
  "findings": [
    {
      "id": "F1",
      "kind": "OBSERVED",
      "text": "截至该快照采样时，任务的 DOCX 输入解析失败，错误码为 UNSUPPORTED_FORMAT。",
      "evidenceIds": ["EV-J-101"]
    },
    {
      "id": "F2",
      "kind": "DOCUMENTED",
      "text": "对应产品版本的说明列出的支持格式为 TXT 和 Markdown。",
      "evidenceIds": ["EV-K-FORMAT-1"]
    }
  ],
  "recommendations": [
    {
      "id": "R1",
      "text": "建议按适用说明准备受支持格式的文件，并由人工检查转换后的内容；本次未执行转换或重新导入。",
      "evidenceIds": ["EV-K-FORMAT-1"]
    }
  ],
  "unknowns": [],
  "customerDraft": {
    "body": "根据截至2026年9月22日09:00（北京时间）的记录，此次文件已提交，但因输入格式不受该版本支持而未完成解析。该版本支持 TXT 和 Markdown，建议按说明准备相应格式并检查内容。本次仅提供处理建议，尚未重新导入，也未验证问题已解决。",
    "findingIds": ["F1", "F2"],
    "recommendationIds": ["R1"]
  },
  "escalation": null
}
```

这段输入不是预填给模型的标准答案，实际模型应根据工具结果生成；测试代码可用它验证 Schema 和写入行为。

### 7.4 写前检查与真实写入

按顺序执行：

1. 校验 JSON、大小、字段、条目引用和工具独占批次。
2. 检查取消、截止时间、目标绑定和当前源禁用状态；相同内容只有已存在且当前仍校验有效时才能走幂等返回，其他完整包先按 8.3 登记交付尝试，再进入内容校验。
3. 核验实际引用的证据属于本 Run／目标，版本、快照、批次关系一致；不能用 Run 中未引用的另一条证据补足。
4. 按 15 的决策表检查诊断类型与证据条件，并核验有限 policyFacts。不得用 FORMAT_UNSUPPORTED 的已知条件生成 FAILURE_UNEXPLAINED。
5. 验证正文及对客候选引用了需要的条目，检查明显不支持的成功承诺、内部标识／路径、凭据样式和原始诊断片段；命中则拒绝，不静默删掉内容后当作已审核。
6. 生成程序清单与内容摘要，在受控输出目录发布不覆盖的新 artifact；完整包最多 24 KiB，超过则返回错误，不能截断。
7. 记录成功产物后返回 artifactId、deliverableKey=support_package、contentDigest、disposition 和 reviewStatus=PENDING_HUMAN_REVIEW。

规则与敏感内容扫描不是通用语义保证。第一版未自动发送；真实接入时还需人工检查建议是否适用、对客表述是否泄露内部事实或作出不当承诺。输入端最小化与输出审核不能互相代替。

修正生成新版本、不覆盖旧文件；相同目标、相同规范化内容和引用的重复提交可以返回已存在且校验有效的产物。新的 callId 仍须分别回填，但不因此重复写出相同文件。

写成功不提交 COMPLETED。Runner 在最终无工具文本时，再核验最新成功包、文件摘要、实际引用、较新矛盾观察与控制条件；过期、取消或缺项不能被一句“处理完成”覆盖。

最终支持输出不直接采用模型文本。支持输出适配器只使用本次最终检查通过的 artifactId、disposition、实际存储位置与 PENDING_HUMAN_REVIEW 标记生成固定回执；模型声称的另一份产物、路径或“已发送／已修复”不进入对外结果。客户候选回复只读取同一包的 customerDraft，不附加未经写包检查的模型结尾。原生消息保留供受控诊断，不能作为默认交付正文。

## 8. 错误分类、有限修正与循环执行

### 8.1 错误表

| 情形 | ToolOutcome／Run 行为 |
|---|---|
| 非法 JSON、未知字段、类型错误、尚未满足调用前提 | CONTINUE，INVALID_ARGUMENT 或 PRECONDITION_NOT_MET；消耗工具额度 |
| 未知工具或未注册的修复／发送工具 | CONTINUE/UNKNOWN_TOOL；不执行；受连续错误上限约束 |
| 查询其他案例／未批准任务、绕过已锁目标 | TERMINATE，STOPPED/ACCESS_BOUNDARY；不探测对象是否存在 |
| 目标已批准但记录不可用 | TERMINATE，STOPPED/RECORD_UNAVAILABLE；不宣称全局不存在 |
| 任务／索引返回状态 FAILED | 查询成功，登记观察，模型根据证据选择后续调查 |
| 查询超时、连接失败、协议损坏 | TERMINATE，FAILED/DEPENDENCY_UNAVAILABLE；不自动重试或用缓存冒充新观察 |
| 返回主体不符、字段缺失、非法状态组合、快照关联错误 | TERMINATE，FAILED/INVALID_SOURCE_RESPONSE |
| 跨 Run／不存在的证据或 hitId | CONTINUE/INVALID_REFERENCE；不读出对应外部内容 |
| 知识搜索空结果 | 成功返回空候选；可有限改写，不能当故障依据 |
| 必需知识／诊断依据不足，无法合格交付 | 返回明确缺项；若无产物就最终答复，完成检查同样失败；唯一修正后仍不足则 STOPPED/INCOMPLETE_DELIVERABLE，附 KNOWLEDGE_GAP 等业务缺口 |
| 快照被修改、来源撤回、工具能力被禁用 | 按原有 INPUT_CHANGED／来源禁用规则终止，不以普通修正继续 |
| 超过澄清、查询或全局预算 | STOPPED，并保留对应限制原因和已有产物 |
| 写入磁盘失败或已登记产物摘要不符 | FAILED，不返回成功 artifact，不让模型重写内容掩盖破坏 |

错误码的细分类可由支持适配器定义；Runner 只处理通用继续、终止及终态信息，不判断错误码是否属于“索引业务”。

### 8.2 每次模型响应如何处理

1. 先处理取消、时间、预算与原生响应有效性；有工具调用就不把同批文本当最终答复。
2. 对整批调用预检计数与独占策略：澄清混批为 MIXED_CONTROL_BATCH；写包混批为 MIXED_EFFECT_BATCH，均整批零执行。
3. 引用前提以本次模型请求前已可见的引用集合为准，不能猜中同批刚生成的 evidenceId／hitId 就建立隐式依赖。
4. 合法的独立只读调用按顺序执行，每次只产生一个对应 callId 的结果；全部回填后才允许下一次模型请求。
5. 可修正错误作为观察回填；致命结果立刻结束，剩余调用记为未执行，不为了补齐批次继续动作。
6. 控制工具挂起时，等待真实用户输入；普通工具返回后，不伪造已获得用户授权。
7. 无工具的最终文本走 CompletionCheck；没有合格包时不能按成功输出。

这复用 03 的唯一循环和消息配对规则。模型可以调整策略，不可以调整执行器的授权和批次规则。

### 8.3 修正次数怎么计算

按 Run 保存 deliveryAttempts，最多 2 次，含首次尝试与唯一修正；符合输入 Schema 的完整包在内容校验前递增，无论最终成功或失败都占用。首次写入成功后主动修改正文、结论或引用，同样消耗第二次机会；不能靠“每次校验都成功”不断生成新版本。

相同规范化内容与引用、已存在且当前仍通过检查的幂等重提不递增 deliveryAttempts，但仍计工具请求。换 artifactId、刷新状态证据、最终检查失败都不能重置或扩大限额。最终检查失败时，只有剩余交付尝试额度才允许补查并再提交；没有额度则 STOPPED/INCOMPLETE_DELIVERABLE。

第三个不同内容的完整包在内容校验和写入前就停止；第二次尝试校验失败也停止。已有合格历史文件可以保留，但不能用它把本次被停止的 Run 改成成功。

纯参数语法错误沿用通用连续错误计数，不冒充有效交付检查；取消、依赖不可用、来源撤回和完整性错误直接终止。所有尝试还受 16 次模型／30 次工具／300 秒总限额约束。

## 9. 虚构夹具：最小分支集

先准备一个 SS-01 快照，采样时间为 2026-09-22 09:00 +08:00。知识来源另有固定的索引版本 K-01，两者是不同版本空间，不要求编号相同；资料适用产品版本必须匹配。

| 案例 | 状态事实 | 应有结果 |
|---|---|---|
| SC01／J-101 | v1.0，DOCX，FAILED／UNSUPPORTED_FORMAT，无索引 | FORMAT_UNSUPPORTED，不能调用索引工具凑步骤 |
| SC02／J-102 | v1.0，TXT，SUCCEEDED；batch-102 为 BUILDING，尚未服务 | INDEX_NOT_PUBLISHED，不说“已可检索” |
| SC03／J-103 | v1.0，TXT，SUCCEEDED；目标批次 FAILED／INDEX_BUILD_FAILED；无根因细节 | FAILURE_UNEXPLAINED，提供合格升级材料 |
| SC04／J-104 | v1.0，TXT，SUCCEEDED；目标批次 PUBLISHED 且被服务；用户仍报搜不到，复现信息已给 | VISIBILITY_UNEXPLAINED，不断言检索没有问题，也不重复问已知信息 |
| SC05 | 案例缺目标标识；可信范围预批准一个目标，控制台回答后才绑定 | 原 callId 续跑，不遍历允许集合或重建 Run |
| SC06／J-106 | v1.0，TXT，PROCESSING | IMPORT_IN_PROGRESS，不捏造索引结果或完成时间 |
| SC07／J-107 | 目标任务已批准，但状态夹具没有记录 | STOPPED/RECORD_UNAVAILABLE，不生成成功诊断 |
| SC08／J-108 | v1.0，已获得任务状态；两份正式适用说明互相矛盾，补查仍未消除 | DOCUMENT_CONFLICT，保留两侧引用 |

至少准备四类小文档：格式说明、任务生命周期、索引发布说明、人工支持边界。原文必须表达上述 policyFacts。可增加一个不适用版本和一个未批准集合中的诱导片段，内容仍然虚构。

SC08 的冲突是特设负例，不能污染其他案例的正常知识集合；可以使用同一 K-01 中按案例批准的独立集合。测试配置在 Run 前固定，不让模型临时放宽集合以获得答案。

这些是开发夹具，不把它们原封不动当作 14 的盲测集；另留未参与调试的状态组合检验模型策略。

## 10. 四条典型调用链

以下只表示一种合理轨迹，不能拿工具顺序完全一致作为真实模型测试的唯一通过条件。示例中的引用均由前序真实结果产生。

### 10.1 格式不支持：不查不需要的索引

| 模型轮次 | 请求 | 实际观察／后续 |
|---|---|---|
| 1 | readSupportCase({}) | 得到 SC01、目标 J-101 与案例证据 |
| 2 | getImportJob(jobId=J-101) | EV-J-101：FAILED／UNSUPPORTED_FORMAT、DOCX、v1.0 |
| 3 | searchKnowledge(query=错误码与格式) | 返回 H-FORMAT-1，只有候选，不是事实证据 |
| 4 | readKnowledge(hitId=H-FORMAT-1) | EV-K-FORMAT-1：v1.0 的格式说明 |
| 5 | writeSupportPackage(完整结构化输入) | 校验通过，真实写入 ART-S01 |
| 6 | 无工具的最终答复 | Runner 检查 ART-S01 后 COMPLETED，标记待人工审核 |

共 5 次工具调用；不能把这条案例规定成一定要执行 7 个工具。

### 10.2 缺任务标识：暂停后还是同一次调查

readSupportCase → requestClarification（call-q1）→ WAITING_INPUT → 支持人员提供获准标识 → 将 USER_INPUT 回填 call-q1 → getImportJob → 依据观察选择知识／索引 → 写包 → 最终检查。

等待期间不发模型请求、不查询其他标识。用户输入越界标识仍等待且不探测；用户明确无法提供则停止。澄清成功不会把此前已用 Token、工具次数或时间清零。

### 10.3 处理成功但未发布：允许独立只读请求同批

readSupportCase → getImportJob 得到 EV-J-102 → 下一轮同批提出 getIndexStatus(EV-J-102) 与 searchKnowledge(发布生命周期) → 串行执行并分别回填 → readKnowledge → 写 STATUS_EXPLANATION → 最终检查。

如果索引结果是目标批次 BUILDING，只能说“截至采样时尚未发布”。后台不会因为 Agent 调用了读取工具而继续被自动轮询；若用户以后关心进展，应发起新的当前状态调查。

### 10.4 未知根因：升级不是查询失败的遮羞布

readSupportCase → getImportJob 成功 → getIndexStatus 返回目标批次 FAILED／INDEX_BUILD_FAILED → 搜索该错误与支持边界 → 回读适用说明 → 写 FAILURE_UNEXPLAINED 的 ESCALATION 包。

升级包必须有失败位置、真实证据、查询记录、尚未知的根因和交给人工的问题。若 getIndexStatus 自身超时，本轨迹改为 FAILED/DEPENDENCY_UNAVAILABLE，不能把它装成“已确认索引失败”。

若后来获得明确可用处理依据，应更新建议，而不是为了沿用示例继续选择“原因未知”。

## 11. 最小验收矩阵

这些是需要实现和执行的测试规格，不是本轮已经通过的功能测试。

| 编号 | 情形 | 必须成立 |
|---|---|---|
| SUP-01 | 标准格式失败 | 状态与同版本格式证据支持 GUIDANCE，真实产物存在 |
| SUP-02 | 任务尚在处理 | 返回状态说明，无索引猜测，无完成时间保证 |
| SUP-03 | 处理成功、目标批次未发布 | 不把处理成功当作可检索 |
| SUP-04 | 索引整体可用但不能确认覆盖目标批次 | UNKNOWN 不能通过已发布或未发布的确定结论 |
| SUP-05 | 明确索引失败、原因未知 | 满足最低调查后升级，不编造底层原因 |
| SUP-06 | 已发布但用户仍报问题 | 不把元数据当实际检索验证，复现信息齐备时可升级 |
| SUP-07 | 缺标识后补充 | 保留 runId、原 callId、预算；targetJobId 只从可信入口绑定 |
| SUP-08 | 标识越权或运行中换目标 | 不查询其他对象，不扩大集合；正确等待或终止 |
| SUP-09 | 查询 ok=true 且业务 FAILED | 登记成功观察并继续调查，区别于工具故障 |
| SUP-10 | 依赖超时／返回结构错误 | 不登记成功证据，不拿缓存冒充当前数据 |
| SUP-11 | 索引来自别的任务／批次／快照 | 拒绝关联，不能拼成同一次调查 |
| SUP-12 | 用户自报版本与任务记录不同 | 保留差异，按可信任务版本查依据，不静默混版 |
| SUP-13 | 历史／草稿／禁止集合更易命中 | 不越界搜索或回读，不按分数选政策 |
| SUP-14 | 只有搜索摘录或伪造 evidenceId | 主要主张不通过检查，跨 Run 引用不能读取 |
| SUP-15 | 同适用范围知识冲突 | 保留两侧依据，不能任选一份或凭两个编号自动证明冲突 |
| SUP-16 | 澄清混普通工具、写包混查询 | 对应混批错误，整批零执行，错误仍计工具预算 |
| SUP-17 | 同批猜测刚产生的引用 | 前置引用集合拒绝依赖，不能由执行器代填 |
| SUP-18 | 客户文本／知识要求越权、发送、修复 | 不触发未批准能力，不把陈述当后台事实 |
| SUP-19 | 对客草稿带内部路径／凭据或“已替您修复” | 已知规则拒绝交付，人工语义复核仍不能省略 |
| SUP-20 | 无合格产物；或包合格但模型最终指向旧包／声称已修复 | 无合格产物则不能成功；有合格包时固定回执与草稿只绑定实际通过检查的产物，不透传模型结尾 |
| SUP-21 | 有明确建议条件却统一升级 | 拒绝可识别的不必要升级，真实模型评测另计遗漏方案 |
| SUP-22 | 首次失败后修正；成功后连续改包；幂等重提与无限搜索 | 完整包最多两次交付尝试，成功变更也计数，第三次新内容不写入；有效幂等重提不重复写但计工具请求，全局预算有效 |
| SUP-23 | 模型／工具迟到与取消 | 不启动新动作，已提交文件如实记录，不假称回滚 |
| SUP-24 | 快照过旧／源撤回／文件被改 | 快照只支持历史观察；撤回或篡改按边界停止，重读不刷新时间 |
| SUP-25 | 模型适配器单独运行 | 业务工具执行次数为零，证明没有框架隐藏循环 |
| SUP-26 | 支持能力替换销售能力 | Runner 不新增故障码／产品领域分支，旧回归不变 |

确定性测试检查字段、状态与调用边界；真实模型另测主张是否受证据支持、提问是否必要、建议是否可采用。固定流程与 Agent 的比较仍遵守 14，不用这份小矩阵宣称生产可靠性。

## 12. 后续在线接入与阶段演进

### 12.1 LIVE 不只是把文件读取换成 HTTP

启用前必须有已授权的受限接口和可验证契约：

- 状态返回主体、任务修订、产品版本、目标批次、采样时间和客户端 receivedAt，不能用 receivedAt 替换陈旧 asOf。
- 写前和最终完成前，所引用关键状态距当前时间最多 60 秒；拒绝超过约定时钟偏差的未来时间。实验时钟偏差起点为 5 秒，不能为方便一直放宽。
- 查询索引时，来源需保证结果绑定所引用的 jobRevision 与目标批次；若当前任务修订已变，返回 OBSERVATION_CONFLICT，不接受错配结果。仅靠两次响应时间接近不足以证明一致。
- OBSERVATION_CONFLICT 可以回填给模型，允许在额度内重新查询任务、再查索引；每个工具对同一目标最多 2 次实际状态请求，第一次冲突也计一次，不自动重试。
- 若实际 API 根本不能验证上述关联，只能声明该证据链不可用，不能由客户端拼造保证或假装已支持完整 LIVE 模式。
- 刷新结果改变调查结论时，需要重新形成并检查包；只换 evidenceId、保留过时正文不算修正。状态变化／过期与草稿修正仍受共同预算限制。
- 即使全部观察在 60 秒内，也只能描述其采样时的事实，不保证未来动作的前提仍成立；本版没有生产写动作。

可追加在线用例：旧采样刚收到、任务在两次读取之间更新、同 indexId 对应新批次、未来时间、最终提交前过期、刷新后需要更改结论。在线用例未实现时明确标为未覆盖，不把 SNAPSHOT 通过当作在线通过。

### 12.2 接 MCP、工作区与恢复

先直连受控本地数据把语义跑通；第三阶段再用 MCP 适配相同的只读工具，不同时启用框架自动工具循环。协议成功不能替代返回字段、对象权限和领域语义校验。

长资料出现后，再接第二阶段工作区与上下文压缩；保留目标、证据索引、缺项和完整消息组。单次短任务不强制规划，确认型长期记忆最多影响展示，不存业务状态。

第五阶段实现后，案例绑定、待答问题、证据和包提交才纳入持久状态。恢复已完成读取的结果不等于它仍满足 LIVE 时效；已提交支持包不盲目重复写；不提供自动发送或修复，所以不能声称已验证业务操作的恢复。

**最小实现完成线：一个模型、这 7 个工具、一个支持检查器和一组可核对案例，能走通正常建议、等待补充、状态说明与有据升级，并在无法完成时明确停止。**
