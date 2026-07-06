# 生产级 Agentic AI 开发规约

适用项目：`text2sql`

来源：`G:\具身智能\Day_5_v3.pdf`，Spec-Driven Production Grade Development in the Age of Vibe Coding，Lee Boonstra，2026-05。

## 1. 目标

本规约用于把“快速 AI 生成代码”约束为可审查、可测试、可追踪、可安全上线的工程流程。核心观点是：代码可以快速生成甚至重写，但规格、测试、评审和安全边界必须稳定可靠。

本仓库中的任何开发任务都应先明确规格，再实现代码。Agent 不应仅凭高层意图直接生成大规模实现。

## 2. 核心原则

- Spec first：先写清楚目标、边界、数据结构、接口、测试和验收标准，再开始实现。
- Code is disposable：代码可以重写，规格才是长期资产。
- Human owns intent：架构意图、上线责任和高风险操作由人类确认。
- Evidence over symptoms：调试必须基于日志、复现命令、失败测试或具体差异，而不是只描述“它坏了”。
- Automation needs guardrails：自动化越强，越需要沙箱、策略、审计、测试和人工检查点。
- Documentation is source of truth：规格、README、CHANGELOG 和关键设计说明必须与代码同步。
- Keep context clean：不得把真实 PII、密钥、生产 URL 或临时聊天上下文硬塞给 Agent。

## 3. 指令与文件布局

```yaml
instruction_layers:
  chat:
    purpose: "短期编排、即时反馈、引用具体任务"
    examples:
      - "阅读 specs/xxx.md，并先生成失败测试"
      - "根据日志定位根因，不做无关重构"
  specs:
    path: "specs/"
    purpose: "版本控制中的任务级事实来源"
    contains:
      - "技术设计"
      - "BDD 场景"
      - "API 合约"
      - "数据结构"
      - "验收标准"
    required_files:
      - "README.md"
      - "project-overview.md"
      - "production-grade-agentic-development.md"
  templates:
    path: "specs/templates/"
    purpose: "新项目总览与新功能规约的可复用模板"
    files:
      - "project-overview.md"
      - "feature-spec-template.md"
  agents:
    path: "AGENTS.md"
    purpose: "跨工具共享的仓库级 Agent 行为规则"
  skills:
    path: ".agents/skills/"
    purpose: "可复用、可触发的专项工作流"
    note: "只有当具体工具要求该路径时才创建，避免与 AGENTS.md 产生重复规则"
```

## 3.1 需求澄清流程

当用户只给出大概需求时，Agent 必须先进入需求澄清阶段，而不是直接实现。

```yaml
requirements_clarification:
  trigger:
    - "需求目标不完整"
    - "输入输出不明确"
    - "数据来源或 SQL 方言不明确"
    - "权限、安全、上线或验收标准不明确"
    - "用户只描述了大概想法"
  agent_behavior:
    - "先复述对需求的理解"
    - "尽可能提出结构化问题，覆盖产品、数据、交互、接口、安全、测试和验收"
    - "把用户回答沉淀到对应 spec"
    - "把仍未回答的问题写入待确认问题"
    - "没有关键阻塞问题后，请用户确认 spec"
  coding_gate:
    rule: "spec 未 Accepted 前，不进入编码阶段"
  defaults:
    rule: "可以提出推荐默认值，但必须标记为 proposed default，并等待确认"
```

建议提问维度：

- 用户与场景：谁使用，解决什么任务，成功标准是什么。
- 输入：自然语言、schema、样例数据、历史查询、业务术语从哪里来。
- 输出：只生成 SQL，还是同时解释、校验、评分、执行。
- SQL 方言：MySQL、PostgreSQL、SQLite、DuckDB、BigQuery、ClickHouse 或其他。
- 数据权限：是否允许连接真实数据库，是否只读，是否允许预览结果。
- 安全边界：是否允许非 `SELECT`，如何处理 PII、密钥、生产连接串。
- 交互形态：CLI、Web UI、API、插件、批处理或多种形态。
- 错误处理：schema 不足、歧义问题、字段不存在、SQL 执行失败时如何响应。
- 验收标准：用哪些样例问题、schema、测试命令和准确率目标验收。
- 非目标：当前明确不做哪些能力，避免范围膨胀。

## 4. 规格格式

叙述性内容使用 Markdown；三层以上的结构化配置、策略、接口和数据模式使用 YAML；用户行为和验收路径使用 Gherkin。

每个新功能规格至少包含：

- 背景：为什么要做，服务哪个用户或流程。
- 目标：本次要达成的可验证结果。
- 非目标：本次明确不做的内容。
- 范围：本次做什么，不做什么。
- 需求澄清记录：已确认答案与待确认问题。
- 现有系统影响：模块、接口、数据、配置、部署和文档影响。
- 技术设计：模块、数据流、依赖、错误处理、日志。
- 数据/API 合约：字段、类型、约束、兼容性。
- BDD 场景：正常、异常、边界和权限场景。
- 测试计划：单元、集成、端到端、回归测试。
- 风险与护栏：权限、隐私、外部副作用、人工确认点。
- 验收标准：可执行命令、预期输出或截图检查点。

## 5. BDD 模板

```gherkin
Feature: 生产级 Agentic AI 开发任务

  Background:
    Given 仓库中存在经过评审的规格文件
    And Agent 已阅读 AGENTS.md 和相关 specs

  Scenario: 从规格生成新项目
    Given 用户要求从零生成项目
    When Agent 完成初步分析
    Then Agent 必须先提出目录结构、技术栈、依赖版本、测试策略和日志策略
    And 在开始大量编码前等待用户确认或给出明确的合理假设

  Scenario: 从粗略需求进入澄清
    Given 用户只给出大概需求
    When Agent 开始处理需求
    Then Agent 必须先复述理解并提出结构化问题
    And 将用户回答整理进 spec
    And 在 spec 被用户确认前不得进入编码阶段

  Scenario: 在现有代码库中生成功能
    Given 用户要求实现一个已有规格中的功能
    When Agent 修改代码
    Then Agent 必须遵循现有命名、错误处理、测试和文档风格
    And 每次改动都应聚焦于规格范围内的行为

  Scenario: 修复缺陷
    Given 用户报告系统异常
    When Agent 开始修复
    Then Agent 必须先建立失败测试、复现命令、日志证据或最小复现场景
    And 只修复根因，不混入变量重命名、格式化或无关清理

  Scenario: 执行高风险工具调用
    Given 操作可能发送消息、改数据库、部署生产、删除文件或访问敏感系统
    When Agent 准备执行该操作
    Then Agent 必须提供意图、目标、影响范围和回滚方式
    And 必须等待人工确认
```

## 6. 执行模式

```yaml
execution_modes:
  project_generation:
    role: "Architect"
    required_behavior:
      - "先规划，后编码"
      - "列出依赖版本并验证来源"
      - "同时设计测试、文档、日志和可观测性"
      - "避免 YOLO 式一次性生成大量不可审查代码"
  feature_generation:
    role: "Builder"
    required_behavior:
      - "匹配现有架构和代码风格"
      - "把多文件变更拆成可审查单元"
      - "更新相关规格、README 或 CHANGELOG"
  bug_fixing:
    role: "Forensic Specialist"
    required_behavior:
      - "先复现，再修复"
      - "优先使用日志、测试、curl、git diff 或 CI 输出作为证据"
      - "保留回归测试"
      - "不做无关重构"
  documentation:
    role: "Author"
    required_behavior:
      - "文档与代码同步"
      - "Python 使用 Google 风格 docstring，TypeScript 使用 JSDoc"
      - "说明真实约束，不保留占位符"
  data_engineering:
    role: "Librarian"
    required_behavior:
      - "展示实际 SQL 或命令"
      - "默认只读查询"
      - "不得输出敏感数据、密钥或直接个人联系信息"
```

## 7. MCP 与工具集成约束

MCP 服务应把外部系统封装为可审计、可限制的工具，而不是给 Agent 不受控的通道。

```yaml
mcp_rules:
  default_permissions:
    database: "read_only"
    filesystem: "workspace_limited"
    network: "explicit_allowlist"
  tool_contracts:
    required:
      - "name"
      - "description"
      - "input_schema"
      - "side_effects"
      - "permission_level"
      - "audit_log"
  database_tools:
    query:
      allowed_sql:
        - "SELECT"
      blocked_sql:
        - "INSERT"
        - "UPDATE"
        - "DELETE"
        - "DROP"
        - "ALTER"
    mutation:
      requires_human_approval: true
      requires_transaction_log: true
  failure_mode:
    policy_denied: "返回 Policy Violation，允许 Agent 自我修正或停止"
```

## 8. 代码评审规约

每个 PR 或重要变更应包含：

- 变更摘要：改了什么，为什么改。
- 风险评估：可能破坏的模块、数据、权限或性能路径。
- 测试证据：运行过的命令、失败到通过的过程、未覆盖风险。
- 安全检查：密钥、SQL 注入、XSS、鉴权、PII、外部副作用。
- 规格对齐：是否实现了对应场景，是否更新了文档。

代码评审关注顺序：

1. 阻断问题：安全漏洞、数据破坏、鉴权绕过、生产副作用。
2. 行为问题：逻辑错误、边界条件、错误处理、并发或重试。
3. 可维护性：过大函数、重复逻辑、命名不清、架构偏离。
4. 风格问题：交给 linter、formatter 或既有样式规则自动处理。

持续评审可按三档演进：

```yaml
review_runtime_tiers:
  tier_1_managed:
    fit: "通用代码库、低定制要求"
    tradeoff: "上线快，但评审标准由供应商决定"
  tier_2_hybrid:
    fit: "大多数团队的起点"
    approach: "自定义 review skill + CI 非交互运行 + PR 评论"
    tradeoff: "标准可控，运行时依赖 CI"
  tier_3_custom:
    fit: "跨 PR 记忆、合规审计、大型遗留系统、知识图谱检索"
    approach: "自托管 Agent runtime + memory + policy server + observability"
    tradeoff: "需要维护评估、成本和 on-call"
```

## 9. 安全护栏

```yaml
guardrails:
  sandboxing:
    required_for:
      - "运行未知命令"
      - "执行生成代码"
      - "浏览器自动化"
      - "访问外部工具"
    expectation: "低权限、隔离网络、限制文件系统、可销毁环境"
  human_in_the_loop:
    required_for:
      - "生产部署"
      - "数据库 schema 或数据修改"
      - "发送邮件/消息"
      - "金融交易"
      - "删除或覆盖大量文件"
      - "访问或导出敏感数据"
  tests:
    required_before_fix: true
    acceptable_evidence:
      - "失败单元测试"
      - "curl 复现命令"
      - "端到端脚本"
      - "日志查询结果"
  evaluation:
    use_when:
      - "系统输出由模型生成"
      - "存在工具选择、摘要、检索或分类行为"
    method:
      - "基线对比"
      - "评分阈值"
      - "轨迹检查"
      - "回归样本集"
  policy_server:
    structural_gate: "基于角色、环境、工具名做确定性拦截"
    semantic_gate: "基于动作意图和参数内容做语义风险判断"
```

## 10. 上下文卫生与敏感信息

不得在规格、测试、prompt、日志或示例代码中硬编码真实个人信息、API key、生产 URL、客户数据或内部机密。需要动态值时使用占位符。

```yaml
context_hygiene:
  placeholders:
    format: "[[VARIABLE_NAME]]"
    examples:
      - "[[COMMENTER_EMAIL]]"
      - "[[DEFAULT_PRESENTATION_ID]]"
      - "[[TEST_CUSTOMER_ID]]"
  resolution_priority:
    - "运行时 override_state"
    - "经过验证的环境变量"
    - "保留未解析占位符并显式失败"
  blocked_patterns:
    - "真实邮箱批量列表"
    - "明文访问令牌"
    - "生产数据库连接串"
    - "客户 PII 样本"
  output_sanitization:
    required: true
    purpose:
      - "防止 prompt injection"
      - "防止 Agent 把上下文中的任意字符串当作真实目标"
      - "防止浏览器或工具误操作"
```

## 11. 验收清单

任务完成前必须确认：

- 相关规格已创建或更新。
- 规格状态已达到可实施状态，或用户明确要求跳过规约流程。
- 粗略需求已经经过问题澄清，阻塞问题已关闭或记录为非阻塞。
- 行为场景、边界场景和错误场景已覆盖。
- 代码改动聚焦于本次任务，没有混入无关重构。
- 测试或复现证据已运行并记录。
- 文档、README、CHANGELOG 或注释已按需同步。
- 没有硬编码密钥、PII、生产 URL 或不必要的外部副作用。
- 高风险操作已经人工确认。
- 评审摘要说明了风险、验证结果和剩余限制。

## 12. 推荐落地顺序

1. 为每个重要任务创建 `specs/*.md`。
2. 在 `AGENTS.md` 固化仓库级 Agent 行为规则。
3. 建立最小测试门禁：单元测试、lint、必要的 E2E 或复现命令。
4. 为高风险工具调用增加人工确认和策略拦截。
5. 将代码评审自动化从 Tier 1 或 Tier 2 开始，必要时再升级到自定义运行时。
