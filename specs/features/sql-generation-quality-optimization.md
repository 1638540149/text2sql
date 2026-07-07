# SQL 生成质量优化规约

```yaml
metadata:
  project: "text2sql"
  feature_name: "sql-generation-quality-optimization"
  status: "implemented"
  owner: "Codex"
  reviewers:
    - "user"
  created_at: "2026-07-06"
  updated_at: "2026-07-06"
  related_docs:
    - "../project-overview.md"
    - "../production-grade-agentic-development.md"
    - "./v1-nl2sql-system.md"
    - "./large-schema-query-optimization.md"
```

## 0. 需求澄清

### 原始需求

用户反馈生成的 SQL 语句质量不够好，希望优化提示词，并询问还能从哪些方面提升 SQL 生成质量。随后用户确认按开发要求推进该需求。

### 已确认回答

```yaml
confirmed_answers:
  users:
    - "查询工作台用户"
    - "排障人员"
  primary_workflow:
    - "用户输入自然语言问题后，系统基于最终候选表生成更高质量 SQL"
    - "如果 SQL parser、AI 校验或 EXPLAIN 失败，系统把错误反馈给模型并自动修复一次"
  inputs:
    - "自然语言问题"
    - "最终候选表元数据"
    - "SQL 方言 MySQL"
    - "可选业务术语"
    - "可选样例 SQL"
    - "parser/validate/EXPLAIN 错误反馈"
  outputs:
    - "结构化 SQL 生成计划"
    - "最终 MySQL SELECT SQL"
    - "字段依据和关键假设"
    - "失败原因或修复轨迹"
  sql_dialects:
    - "MySQL first"
  data_sources:
    - "已同步到 NL2SQL 应用库的 MySQL 元数据"
  permissions:
    - "沿用现有数据源授权逻辑"
    - "不发送数据库密码"
    - "不发送真实查询结果行用于 SQL 生成"
  ui_or_api_shape:
    - "Java REST 查询流程"
    - "Java 调用 Python AI 服务"
    - "Python OpenAI-compatible chat completions"
  acceptance_examples:
    - "生成 SQL 不使用 SELECT *"
    - "聚合查询包含合理 GROUP BY"
    - "模型输出结构化计划和最终 SQL"
    - "parser/EXPLAIN 失败时自动修复一次"
    - "仍失败时不执行 SQL，并保留失败原因"
```

### 已确认决策

```yaml
confirmed_decisions:
  prompt_strategy:
    structured_generation: true
    output_format: "JSON first, with SQL fallback parser for compatibility"
    required_sections:
      - "intent"
      - "tables"
      - "fields"
      - "joins"
      - "filters"
      - "groupBy"
      - "orderBy"
      - "limit"
      - "assumptions"
      - "sql"
  sql_quality_rules:
    - "只生成一条 MySQL SELECT"
    - "禁止 SELECT *"
    - "只能使用候选表中的真实字段"
    - "聚合字段和非聚合字段必须匹配 GROUP BY"
    - "统计类问题优先使用 SUM/COUNT/AVG 等聚合"
    - "趋势/按月/按天问题优先使用日期或时间字段"
    - "ORDER BY 必须引用可解释字段或别名"
    - "没有明确 LIMIT 时由 Java 统一追加 LIMIT"
    - "不确定字段含义时在 assumptions 中说明"
  repair_strategy:
    parser_or_validation_failure: "把错误反馈给模型并重试一次"
    explain_failure: "把数据库 EXPLAIN 错误反馈给模型并重试一次"
    max_repair_attempts: 1
  business_context:
    glossary_support: "预留 request 字段，当前先传空数组"
    example_sql_support: "预留 request 字段，当前先传空数组"
    persistence_or_ui: "本次不做管理页面和数据库持久化"
  safety:
    readonly_only: true
    raw_rows_to_llm: false
    secrets_to_llm: false
```

### 待最终确认

- 本规格已根据用户方向写成 `reviewed`。
- 用户明确确认后，状态改为 `accepted`，再进入实现。

## 1. 背景

上一轮已完成大规模元数据分页、分层选表、SQL parser 和 EXPLAIN 前置校验。当前主要问题从“表选错或表不存在”转向“SQL 虽可解析，但语义、聚合、排序、字段选择和可读性不够好”。因此本次聚焦 SQL 生成质量，而不是继续扩大 UI 或数据源能力。

- 用户/角色：查询工作台用户、排障人员。
- 当前痛点：模型只收到短提示，缺少明确 SQL 生成规范和结构化思考步骤。
- 成功后的可观察结果：SQL 更少出现 `SELECT *`、聚合不完整、排序不清楚、字段选择随意等问题；失败时可自动修复一次。

## 2. 范围

### 目标

- 优化 Python AI 服务的 SQL 生成 prompt，使模型先生成结构化计划再给 SQL。
- Python `/nl2sql/generate` 返回更多解释字段，但保持现有 `sql` 字段兼容 Java。
- Java 在 parser、AI validate 或 EXPLAIN 失败时，使用错误反馈自动重试生成一次。
- 在 prompt 中预留业务术语和样例 SQL 输入能力，当前先由 Java 传空数组。
- 日志和流程 trace 能体现 SQL 生成修复过程。

### 非目标

- 不新增业务术语管理页面。
- 不新增样例 SQL 管理页面。
- 不修改数据库 schema。
- 不引入向量检索或 RAG。
- 不保证业务语义 100% 正确。
- 不允许执行非 `SELECT` SQL。

本次包含：

- Python SQL 生成 prompt 重写。
- Python 结构化 JSON 响应解析与兼容兜底。
- Java AI client 请求体增加 `glossary`、`examples`、`feedback`。
- Java 查询流程把 SQL parser/AI validate/EXPLAIN 失败反馈给模型修复一次。
- README 和规格实施记录更新。

本次不包含：

- 数据库迁移。
- 新 UI 页面。
- 批量评测平台。
- 多 SQL 方言支持。

## 3. 用户故事

- 作为查询用户，我希望系统生成更符合业务意图的 SQL，从而减少手动改 SQL 的次数。
- 作为查询用户，我希望 SQL 出错时系统能自动修复一次，从而提升一次点击成功率。
- 作为排障人员，我希望流程中能看到 SQL 修复原因，从而判断问题来自模型、字段、表关系还是 SQL 写法。

## 4. 行为场景

```gherkin
Feature: SQL 生成质量优化

  Background:
    Given 用户已登录 text2sql
    And 用户选择了数据源和模型
    And 系统已经完成最终候选表选择

  Scenario: 生成结构化 SQL 计划
    Given 用户输入一个统计类自然语言问题
    When 系统调用 Python AI 服务生成 SQL
    Then Prompt 要求模型先输出意图、表、字段、聚合、排序和假设
    And 响应包含最终 SQL
    And Java 继续使用响应中的 sql 字段进入校验流程

  Scenario: 禁止 SELECT 星号
    Given 候选表包含多个字段
    When 模型生成 SQL
    Then Prompt 明确禁止 SELECT *
    And SQL 应显式列出需要字段或聚合表达式

  Scenario: parser 或 AI 校验失败后自动修复
    Given 第一次生成的 SQL 无法被 parser 解析或 AI validate 不通过
    When Java 捕获校验错误
    Then Java 把错误反馈给 Python
    And Python 基于反馈重新生成一次 SQL
    And Java 再次执行 parser 和校验

  Scenario: EXPLAIN 失败后自动修复
    Given 第一次生成的 SQL 通过 parser 但数据库 EXPLAIN 报错
    When Java 捕获 EXPLAIN 错误
    Then Java 把数据库错误反馈给 Python
    And Python 基于反馈重新生成一次 SQL
    And Java 再次执行 parser、AI validate 和 EXPLAIN

  Scenario: 自动修复仍失败
    Given SQL 自动修复后仍无法通过校验或 EXPLAIN
    When 系统保存查询历史
    Then 系统不执行 SELECT
    And trace 和日志记录最终失败原因
```

## 5. 技术设计

```yaml
design:
  affected_areas:
    backend:
      - "AiClient"
      - "QueryService"
    ai_service_python:
      - "ai-service-python/app/main.py"
    frontend: []
    database: []
    docs:
      - "README"
  modules:
    - name: "Structured SQL prompt"
      responsibility: "Use explicit rules and JSON output contract to improve SQL quality"
    - name: "SQL repair loop"
      responsibility: "Retry once when parser, validate or EXPLAIN fails"
    - name: "Business context placeholders"
      responsibility: "Carry glossary and example SQL arrays without persistence in this iteration"
  data_flow:
    - step: 1
      description: "Java sends question, final candidate metadata, empty glossary, empty examples and optional feedback to Python"
    - step: 2
      description: "Python prompt asks model for structured plan plus final SQL"
    - step: 3
      description: "Python parses JSON response and returns sql, explanation, assumptions and plan fields"
    - step: 4
      description: "Java runs parser, AI validate and EXPLAIN"
    - step: 5
      description: "On failure, Java sends sanitized feedback to Python and retries once"
    - step: 6
      description: "If retry succeeds, Java continues execution; otherwise save failure history"
  dependencies:
    runtime: []
    development: []
    new_large_dependency_required: false
    confirmation_status: "not_required"
  logging:
    required_events:
      - "sql_generate success/failure with sqlLength and tableCount"
      - "sql_repair_attempt with failure stage and sanitized reason"
      - "sql_repair_success or sql_repair_failed"
  error_handling:
    expected_errors:
      - "model returns non-JSON content"
      - "model omits sql field"
      - "parser validation fails"
      - "AI validate fails"
      - "EXPLAIN fails"
```

## 6. 数据与 API 合约

```yaml
contracts:
  inputs:
    - name: "question"
      type: "string"
      required: true
      validation: "Non-empty"
    - name: "metadata"
      type: "TableMetadata[]"
      required: true
      validation: "Final candidate tables only"
    - name: "feedback"
      type: "string"
      required: false
      validation: "Sanitized parser/validate/EXPLAIN failure feedback"
    - name: "glossary"
      type: "GlossaryTerm[]"
      required: false
      validation: "Current iteration passes []"
    - name: "examples"
      type: "SqlExample[]"
      required: false
      validation: "Current iteration passes []"
  outputs:
    - name: "sql"
      type: "string"
      description: "Final MySQL SELECT SQL"
    - name: "plan"
      type: "object"
      description: "Structured reasoning summary, excluding hidden chain-of-thought"
    - name: "assumptions"
      type: "string[]"
      description: "Key assumptions about ambiguous fields or filters"
  api:
    endpoint: "/nl2sql/generate"
    method: "POST"
    request_schema:
      question: "按地区统计销售金额"
      dialect: "MYSQL"
      metadata: "final candidate table metadata"
      feedback: "EXPLAIN error: Unknown column ..."
      glossary: []
      examples: []
      model: "safe model config"
    response_schema:
      sql: "SELECT region, SUM(amount) AS total_amount FROM orders GROUP BY region ORDER BY total_amount DESC"
      explanation: "按地区聚合销售金额"
      assumptions:
        - "amount 表示销售金额"
      plan:
        intent: "按地区统计销售金额"
        tables:
          - "orders"
        fields:
          - "region"
          - "amount"
        groupBy:
          - "region"
        orderBy:
          - "total_amount DESC"
  persistence:
    tables: []
    migrations_required: false
```

## 7. 安全与护栏

- 涉及敏感数据：用户问题、生成 SQL、模型配置。
- 外部副作用：模型接口调用、只读 EXPLAIN、只读 SELECT。
- 人工确认：不需要；不改数据库结构，不引入新依赖。
- 策略拦截：继续只允许单条 `SELECT`。

```yaml
guardrails:
  pii_allowed: false
  secrets_allowed: false
  production_side_effects_allowed: false
  human_approval_required: false
  rollback_plan: "可回退 Python prompt 和 Java repair loop；不涉及数据库迁移"
```

关键护栏：

- 不把数据库密码、API Key、真实结果行发送给模型。
- 修复反馈必须脱敏。
- 自动修复最多一次，避免循环调用模型。
- 修复后仍必须重新通过 parser、AI validate 和 EXPLAIN。

## 8. 测试计划

- 单元测试：
  - Python JSON 响应解析。
  - Python 从 Markdown JSON 或纯 SQL 中兼容提取 SQL。
  - Java 修复反馈构造不包含敏感信息。
- 集成测试：
  - parser 失败触发一次重试。
  - EXPLAIN 失败触发一次重试。
  - 重试成功后继续执行。
  - 重试失败后不执行 SELECT。
- 回归测试：
  - 现有分层选表流程不受影响。
  - 非 `SELECT` SQL 仍被拦截。
  - 查询历史仍保存失败原因和 trace。

```yaml
verification:
  commands:
    - command: "mvn -q test"
      expected: "Java tests pass"
    - command: "mvn -q -DskipTests package"
      expected: "Java package succeeds"
    - command: "python -m compileall app"
      expected: "Python source compiles"
    - command: "npm run build"
      expected: "Frontend build still succeeds if untouched"
  screenshots_required: false
  logs_to_check:
    - "No password, apiKey, passwordCipher, raw rows in repair logs"
```

## 9. 验收标准

- [ ] 规格已经评审并由用户确认。
- [ ] Python SQL 生成 prompt 明确包含 SQL 质量规则。
- [ ] Python `/nl2sql/generate` 能返回结构化 plan、assumptions 和 sql。
- [ ] 模型返回非 JSON 时仍尽量兼容提取 SQL。
- [ ] parser 或 AI validate 失败时自动修复一次。
- [ ] EXPLAIN 失败时自动修复一次。
- [ ] 自动修复失败后不执行 SELECT。
- [ ] 自动修复过程写入 trace 和日志。
- [ ] 不新增数据库表或迁移。
- [ ] README 或相关文档已同步。

## 10. 实施记录

- 关键变更：
  - Python `/nl2sql/generate` 请求增加 `glossary` 和 `examples` 预留字段，当前 Java 传空数组。
  - Python SQL 生成 prompt 改为结构化 JSON 输出，要求包含 `plan`、`sql`、`explanation`、`assumptions`。
  - Prompt 明确 SQL 质量规则：禁止 `SELECT *`、只使用候选字段、聚合匹配 `GROUP BY`、趋势优先时间字段、排序字段需可解释。
  - Python 增加 JSON、Markdown JSON、纯 SQL 的兼容解析，避免模型未严格返回 JSON 时直接失败。
  - Java `AiClient.generate` 向 Python 传递 `feedback`、`glossary`、`examples`。
  - Java 查询流程新增统一修复预算：parser、AI validate、EXPLAIN 谁先失败就反馈给模型自动修复一次。
  - 修复后的 SQL 必须重新通过 parser、AI validate 和 EXPLAIN，失败则不执行 SELECT。
  - README 已同步结构化生成、自动修复和 SQL 质量规则说明。
- 测试命令：
  - `conda run -n text2sql python -m unittest discover -s tests`：通过。
  - `python -m compileall app`：通过。
  - `mvn -q test`：通过。
  - `mvn -q -DskipTests package`：通过。
- 已知限制：
  - 结构化 prompt 可提升 SQL 质量，但不能保证业务语义 100% 正确。
  - 业务术语和样例 SQL 仅预留接口，本次不持久化、不提供管理页面。
  - 自动修复最多一次，避免模型调用循环。
- 后续工作：
  - 增加业务术语和样例 SQL 管理能力。
  - 建立 NL2SQL 回归评测集，量化 SQL 质量改进。
  - 增强字段级 parser 校验和 JOIN 关系推断。

## 11. 待确认问题

无。
