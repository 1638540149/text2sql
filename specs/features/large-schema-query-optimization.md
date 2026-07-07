# 大规模元数据查询优化与可观测性规约

```yaml
metadata:
  project: "text2sql"
  feature_name: "large-schema-query-optimization"
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
```

## 0. 需求澄清

### 原始需求

用户提出当前系统最重要的几个优化点：

1. 查询工作台不要一次展示具体表字段和表结构，因为表字段太多时会导致页面卡顿；可选择不展示或懒加载。
2. 当前执行流程中表选择不准确，模型甚至会返回不存在的表名；期望把数据库里所有表结构、字段等数据发送给大模型，让模型先选择表，再转换 SQL。
3. Java 后端和 Python AI 服务的关键路径需要输出日志，并持久化日志，保留最近 7 天。
4. 元数据浏览页面左侧表名和右侧字段需要分页，不要一次性展示全部内容，避免表太多导致页面过长和卡顿。

### 已确认回答

```yaml
confirmed_answers:
  users:
    - "查询工作台用户"
    - "元数据浏览用户"
    - "系统管理员/排障人员"
  primary_workflow:
    - "用户在查询工作台选择数据源和模型，输入自然语言，系统先完成表选择，再生成 SQL、校验并执行"
    - "用户在元数据浏览页分页查看表和字段"
    - "排障人员通过 Java 与 Python 持久化日志定位模型调用、表选择、SQL 校验、执行失败等问题"
  inputs:
    - "自然语言问题"
    - "授权数据源的已同步元数据：表、字段、类型、注释、索引"
    - "用户可选的限定表范围"
    - "分页参数 page、pageSize、keyword"
  outputs:
    - "分页后的表列表"
    - "分页后的字段列表"
    - "模型选择的候选表及选择依据"
    - "可审查 SQL、校验结果、执行结果和流程记录"
    - "Java/Python 7 天滚动日志"
  sql_dialects:
    - "MySQL first"
  data_sources:
    - "已同步到 NL2SQL 应用库的 MySQL 元数据"
  permissions:
    - "沿用现有数据源授权逻辑"
    - "普通用户只能访问已授权数据源的元数据与查询能力"
    - "管理员保留全部数据源管理能力"
  ui_or_api_shape:
    - "Vue 查询工作台"
    - "Vue 元数据浏览"
    - "Java REST API"
    - "Java 调用 Python AI 服务"
  acceptance_examples:
    - "查询工作台切换数据源后不再渲染全部字段树"
    - "元数据浏览表列表分页展示"
    - "选择某张表后字段分页展示"
    - "模型返回不存在表名时系统拦截并给出失败原因或重试"
    - "Java 和 Python 日志文件保留最近 7 天且不记录密码、API Key 或原始结果行"
```

### 已确认决策

```yaml
confirmed_decisions:
  query_workspace_metadata:
    table_names_visible: true
    table_names_lazy_loaded: true
    fields_default_visible: false
    field_loading: "lazy"
    user_can_select_table_scope: true
  manual_table_scope:
    enabled: true
    behavior: "用户手动勾选限定表范围时，Java 先做表白名单校验，再把这些表作为候选范围"
  table_selection_strategy:
    goal: "提高大模型选择表名的准确性，避免编造不存在的表"
    stages:
      - name: "coarse_table_selection"
        input: "当前数据源全部授权表名、表注释、字段数量，不发送字段明细"
        output: "粗选候选表"
      - name: "field_aware_table_selection"
        input: "粗选候选表的表名、字段名、字段类型、字段注释、表注释"
        output: "最终候选表"
      - name: "sql_generation"
        input: "最终候选表的完整元数据"
        output: "MySQL SELECT SQL"
    model_call_count: "通常 3 次：粗选表名、字段感知复选、生成 SQL；用户手动选表时可跳过粗选"
  candidate_limits:
    final_model_selected_tables: 8
    user_selected_tables: 20
  unknown_table_handling:
    validation_owner: "Java"
    retry_policy: "发现未知表名时带错误反馈重试一次；仍失败则终止，不执行 SQL"
  sql_parser:
    required: true
    owner: "Java backend"
    purpose:
      - "解析模型生成 SQL，确保语法可被 parser 接受"
      - "确认是单条 SELECT"
      - "提取表引用、辅助表名白名单校验"
      - "在 EXPLAIN 前拦截明显不可执行 SQL"
    candidate_library: "JSqlParser or equivalent Java SQL parser; dependency version must be verified before implementation"
    note: "SQL parser 不能证明业务语义正确；最终仍通过数据库 EXPLAIN 验证实际可执行性"
  logging:
    directory: "logs/"
    java_file: "logs/text2sql-java.log"
    python_file: "logs/text2sql-python.log"
    retention_days: 7
    distinguish_services: "通过文件名、logger name 和 service 字段区分 Java/Python"
  pagination:
    table_page_size: 20
    column_page_size: 50
    max_page_size: 100
  log_content_policy:
    allowed:
      - "表名"
      - "字段名"
      - "生成 SQL"
      - "用户自然语言问题摘要或长度"
    forbidden:
      - "数据库密码"
      - "模型 API Key"
      - "密码密文"
      - "原始查询结果行"
      - "原始敏感值"
```

### 待最终确认

- 本规格已根据用户回答完成 review 更新，当前状态为 `reviewed`。
- 用户明确确认本规格后，状态改为 `accepted`，再进入实现。

## 1. 背景

当前 V1 已实现数据源、元数据同步、查询工作台、自然语言转 SQL、SQL 校验和执行闭环。但在表和字段数量较大时，前端会一次拉取并渲染完整元数据树，造成页面变长和卡顿。同时，当前 `metadataSummary` 会在未手动选表时最多截取少量候选表，导致模型上下文不完整，出现选表错误或编造不存在表名的风险。

- 用户/角色：查询用户、管理员、排障人员。
- 当前痛点：大元数据前端渲染卡顿；模型选表不准；缺少持久化日志；元数据页面一次展示过多。
- 成功后的可观察结果：页面响应更稳，模型不会执行引用不存在表的 SQL，模型 SQL 先通过 parser 语法解析和数据库 EXPLAIN，关键流程可通过 7 天日志追踪。

## 2. 范围

### 目标

- 查询工作台不再默认展示完整表字段树。
- 支持查询工作台表名懒加载，默认不展示字段，字段只在需要时懒加载。
- 保留用户手动勾选限定表范围。
- 优化 NL2SQL 执行流程为“全量授权表名粗选 -> 候选表字段感知复选 -> 表名白名单校验 -> 基于最终候选表生成 SQL -> SQL parser 解析 -> SQL 表字段校验 -> EXPLAIN -> SELECT 执行”。
- 对模型返回不存在表名的情况进行拦截、重试或失败关闭。
- 引入 Java 侧 SQL parser，确保模型生成 SQL 在执行前至少能被解析为单条 SELECT，并可提取表引用。
- Java 后端关键路径输出结构化日志并持久化，保留最近 7 天。
- Python AI 服务关键路径输出结构化日志并持久化，保留最近 7 天。
- 元数据浏览页面表名列表分页，字段列表分页。

### 非目标

- 不在本次引入多数据库完整支持，仍以 MySQL 为第一版目标。
- 不把真实数据行发送给 Python 或大模型。
- 不执行非 `SELECT` SQL。
- 不在本次重做完整 RBAC。
- 不在本次实现向量检索、语义索引或高级元数据检索。
- 不在本次修改数据源密码存储策略。

本次包含：

- 前端查询工作台元数据展示优化。
- 前端元数据浏览分页交互。
- Java 元数据分页 API、表名懒加载 API 和字段懒加载 API。
- Java 查询流程中的粗选表名上下文、字段感知复选上下文、SQL parser 校验、白名单校验、重试和日志。
- Python AI 服务表粗选接口、字段感知复选接口、生成提示词约束、日志滚动配置。
- Java SQL parser 依赖；具体版本在实现前通过官方来源确认。
- README 或运行说明中的日志路径说明。

本次不包含：

- 生产部署脚本重构。
- 真实业务数据脱敏抽样。
- 大模型评测平台。
- 新数据库方言。

## 3. 用户故事

- 作为查询用户，我希望查询工作台不要一次渲染所有字段，从而在大库场景下页面仍然流畅。
- 作为查询用户，我希望系统先从全部可用表字段中选择正确表，再生成 SQL，从而减少表名错误和幻觉。
- 作为管理员，我希望元数据浏览支持分页，从而能查看大库但不会把页面拉得很长。
- 作为排障人员，我希望 Java 和 Python 服务保留 7 天关键日志，从而能追踪一次查询失败发生在哪个阶段。

## 4. 行为场景

```gherkin
Feature: 大规模元数据查询优化与可观测性

  Background:
    Given 用户已登录 text2sql
    And 用户拥有某个 MySQL 数据源的访问权限
    And 数据源已经刷新过元数据

  Scenario: 查询工作台加载数据源时不渲染完整字段树
    Given 数据源包含大量表和字段
    When 用户进入查询工作台并选择该数据源
    Then 页面只加载轻量表信息或分页表信息
    And 页面不默认渲染所有字段节点
    And 用户仍可提交自然语言查询

  Scenario: 模型分层选择表再生成 SQL
    Given 用户没有手动选择表
    When 用户输入自然语言问题并点击生成执行
    Then Java 将该数据源的全部授权表名、表注释和字段数量发送给 Python 粗选步骤
    And Python 返回粗选候选表列表和选择依据
    And Java 校验粗选候选表必须存在于当前数据源
    When Java 加载粗选候选表的字段名、字段类型和字段注释
    Then Python 基于字段明细返回最终候选表
    And Java 校验最终候选表必须存在于当前数据源
    And 系统再基于最终候选表生成 SQL

  Scenario: 模型返回不存在表名
    Given Python 表选择或 SQL 生成返回了当前数据源不存在的表名
    When Java 执行表名白名单校验
    Then 系统拦截该结果
    And 系统最多按确认策略重试一次
    And 重试仍失败时不执行 SQL
    And 查询历史和日志记录失败原因

  Scenario: 模型生成 SQL 无法被 parser 解析
    Given Python 返回了一条语法错误或非单条 SELECT 的 SQL
    When Java 使用 SQL parser 解析该 SQL
    Then 系统拦截该 SQL
    And 系统不执行 EXPLAIN 或 SELECT
    And 查询历史和日志记录 SQL parser 校验失败原因

  Scenario: 元数据浏览分页查看表与字段
    Given 数据源包含超过一页的表
    When 用户打开元数据浏览页面
    Then 左侧展示第一页表名
    And 用户可翻页或搜索表名
    When 用户选择某张表
    Then 右侧展示该表第一页字段
    And 用户可翻页查看后续字段

  Scenario: 日志滚动保留
    Given Java 后端和 Python AI 服务正常运行
    When 用户完成一次查询或查询失败
    Then Java 日志记录请求开始、粗选表名、字段感知复选、SQL parser 校验、EXPLAIN、执行和历史保存结果
    And Python 日志记录模型调用、表选择、SQL 生成、校验接口的耗时和状态
    And Java 日志写入 logs/text2sql-java.log
    And Python 日志写入 logs/text2sql-python.log
    And 日志文件按天滚动并保留最近 7 天
    And 日志不包含数据库密码、模型 API Key 或原始查询结果行
```

## 5. 技术设计

```yaml
design:
  affected_areas:
    backend:
      - "DataSourceController"
      - "JdbcDatasourceService"
      - "QueryService"
      - "AiClient"
      - "CoreMapper"
      - "application.yml / logging configuration"
    frontend:
      - "frontend/src/views/QueryWorkbench.vue"
      - "frontend/src/views/Metadata.vue"
      - "frontend/src/api/client.ts"
    ai_service_python:
      - "ai-service-python/app/main.py"
      - "Python logging configuration"
    database:
      - "No schema migration expected"
    docs:
      - "README logging and metadata behavior notes if user-facing commands change"
  modules:
    - name: "Metadata pagination API"
      responsibility: "Return paginated table summaries and paginated columns without loading all metadata into the frontend"
    - name: "Coarse table-selection flow"
      responsibility: "Send all authorized table summaries to the model and receive a coarse candidate table set"
    - name: "Field-aware table-selection flow"
      responsibility: "Send candidate table fields and comments to the model and receive final candidate tables"
    - name: "SQL parser validation"
      responsibility: "Parse generated SQL as a single SELECT, extract table references, and reject unparseable SQL before EXPLAIN"
    - name: "SQL/table whitelist validation"
      responsibility: "Reject SQL that references tables not present in the full metadata whitelist"
    - name: "Java logging"
      responsibility: "Persist sanitized structured logs to logs/text2sql-java.log for query, metadata, datasource and AI-client events"
    - name: "Python logging"
      responsibility: "Persist sanitized structured logs to logs/text2sql-python.log for AI endpoints and model-provider calls"
  data_flow:
    - step: 1
      description: "Frontend loads datasource list and lazy-loaded/paginated table summaries"
    - step: 2
      description: "User submits natural language question with optional selectedTables"
    - step: 3
      description: "If selectedTables is present, Java validates user-selected tables and uses them as candidate scope"
    - step: 4
      description: "If selectedTables is empty, Java loads all authorized table summaries and calls Python coarse table-selection"
    - step: 5
      description: "Java validates coarse candidates against the full table whitelist"
    - step: 6
      description: "Java loads fields/comments for validated candidates and calls Python field-aware table-selection"
    - step: 7
      description: "Java validates final candidates, then Python generates SQL using only final candidate table metadata"
    - step: 8
      description: "Java parses SQL with SQL parser, enforces single SELECT, validates referenced tables/fields where practical"
    - step: 9
      description: "Java runs EXPLAIN, then executes SELECT only when parser, whitelist and EXPLAIN checks pass"
    - step: 10
      description: "Java saves query history, trace, and logs sanitized event details"
  dependencies:
    runtime:
      - "Java SQL parser dependency, candidate: JSqlParser; exact version to be verified from official source before implementation"
    development: []
    new_large_dependency_required: true
    confirmation_status: "confirmed_by_user_for_sql_parser"
  logging:
    required_events:
      - "datasource metadata refresh start/end/failure with counts and duration"
      - "metadata page query with datasource id, page, pageSize, keyword presence, result count"
      - "query run start/end/failure with user id, datasource id, model id, duration, status"
      - "coarse table selection start/end/failure with candidate count and invalid table names"
      - "field-aware table selection start/end/failure with final candidate count and invalid table names"
      - "AI model call start/end/failure with model name, duration, HTTP status, token estimate"
      - "SQL parser validation start/end/failure with sanitized reason"
      - "SQL validation failure reason"
      - "EXPLAIN high-risk warning"
      - "SELECT execution duration and returned row count"
    forbidden_log_values:
      - "database password"
      - "model API Key"
      - "passwordCipher"
      - "raw result rows"
      - "production connection strings containing credentials"
  error_handling:
    expected_errors:
      - "metadata not refreshed"
      - "metadata payload too large"
      - "coarse table selection failed"
      - "field-aware table selection failed"
      - "candidate table does not exist"
      - "SQL parser cannot parse generated SQL"
      - "SQL parser detects non-SELECT or multi-statement SQL"
      - "generated SQL references unknown table"
      - "generated SQL references unknown field"
      - "AI service unavailable"
      - "metadata page parameters invalid"
```

## 6. 数据与 API 合约

```yaml
contracts:
  inputs:
    - name: "dataSourceId"
      type: "number"
      required: true
      validation: "Must be an authorized datasource id"
    - name: "question"
      type: "string"
      required: true
      validation: "Non-empty, existing max length applies"
    - name: "selectedTables"
      type: "string[]"
      required: false
      validation: "Every table must exist in authorized metadata"
    - name: "page"
      type: "number"
      required: false
      validation: ">= 1"
    - name: "pageSize"
      type: "number"
      required: false
      validation: "1..100"
    - name: "keyword"
      type: "string"
      required: false
      validation: "Trimmed, used for table/column search"
  outputs:
    - name: "tables"
      type: "Paged<TableSummary>"
      description: "Paginated table summaries without full column arrays"
    - name: "columns"
      type: "Paged<ColumnSummary>"
      description: "Paginated columns for one table"
    - name: "selectedTables"
      type: "TableSelectionResult"
      description: "AI-selected candidate tables and reasons"
    - name: "trace"
      type: "TraceStep[]"
      description: "User-visible execution trace including table selection"
  api:
    frontend_to_java:
      - endpoint: "/api/datasources/{id}/metadata/tables"
        method: "GET"
        request_schema:
          page: 1
          pageSize: 20
          keyword: ""
        response_schema:
          total: 0
          page: 1
          pageSize: 20
          items:
            - tableName: "orders"
              tableComment: "订单表"
              columnCount: 12
              syncedAt: "2026-07-06T00:00:00"
      - endpoint: "/api/datasources/{id}/metadata/tables/{tableName}/columns"
        method: "GET"
        request_schema:
          page: 1
          pageSize: 50
          keyword: ""
        response_schema:
          total: 0
          page: 1
          pageSize: 50
          items:
            - columnName: "order_id"
              dataType: "bigint"
              nullableFlag: "NO"
              columnKey: "PRI"
              columnComment: "订单 ID"
              ordinalPosition: 1
    java_to_python:
      - endpoint: "/nl2sql/select-table-candidates"
        method: "POST"
        request_schema:
          question: "按地区统计销售金额"
          dialect: "MYSQL"
          tables:
            - tableName: "orders"
              tableComment: "订单表"
              columnCount: 12
          model:
            modelName: "[[MODEL_NAME]]"
        response_schema:
          tables:
            - tableName: "orders"
              reason: "contains sales amount and region fields"
          assumptions: []
      - endpoint: "/nl2sql/refine-tables"
        method: "POST"
        request_schema:
          question: "按地区统计销售金额"
          dialect: "MYSQL"
          candidateMetadata:
            - tableName: "orders"
              tableComment: "订单表"
              columns:
                - columnName: "amount"
                  dataType: "decimal"
                  columnComment: "销售金额"
                - columnName: "region"
                  dataType: "varchar"
                  columnComment: "地区"
          model:
            modelName: "[[MODEL_NAME]]"
        response_schema:
          tables:
            - tableName: "orders"
              reason: "contains both region and amount fields"
          assumptions: []
      - endpoint: "/nl2sql/generate"
        method: "POST"
        request_schema:
          question: "按地区统计销售金额"
          dialect: "MYSQL"
          metadata: "validated candidate table metadata only"
          model: "safe model config"
        response_schema:
          sql: "SELECT region, SUM(amount) AS total_amount FROM orders GROUP BY region"
          explanation: ""
          assumptions: []
          promptTokens: 0
          completionTokens: 0
          durationMs: 0
          modelCall: {}
  persistence:
    tables:
      - "datasource_table"
      - "datasource_column"
      - "datasource_index_info"
      - "query_history"
    migrations_required: false
```

## 7. 安全与护栏

- 涉及敏感数据：数据源凭据、模型 API Key、用户问题、生成 SQL。
- 外部副作用：只读数据库查询、模型接口调用、日志文件写入。
- 人工确认：SQL parser 依赖已由用户确认；如后续需要数据库结构迁移或生产部署，仍需额外确认。
- 策略拦截：继续只允许 `SELECT`，继续禁止多语句和危险关键字。

```yaml
guardrails:
  pii_allowed: false
  secrets_allowed: false
  production_side_effects_allowed: false
  human_approval_required: false
  rollback_plan: "保留旧 /metadata 接口兼容；前端可回退为轻量表列表；日志配置可关闭文件输出但保留控制台输出"
```

关键护栏：

- Python AI 服务不得接收数据库密码。
- Python AI 服务不得接收真实查询结果行用于 SQL 生成。
- 日志不得输出 API Key、数据库密码、密码密文、原始查询结果行。
- 模型返回不存在表名或字段时，系统不得执行 SQL。
- SQL parser 解析失败、非单条 SELECT、多语句或危险语句时，系统不得执行 EXPLAIN 或 SELECT。
- SQL parser 通过不代表业务语义正确；数据库 EXPLAIN 仍是执行前的实际可执行性验证步骤。
- 元数据分页接口必须复用现有数据源授权。

## 8. 测试计划

- 单元测试：
  - Java 分页参数校验。
  - Java SQL parser 可解析单条 SELECT。
  - Java SQL parser 拒绝语法错误、非 SELECT、多语句。
  - Java 表名白名单校验。
  - Java SQL 引用未知表拦截。
  - Python 粗选表名响应解析与未知表防护。
  - Python 字段感知复选响应解析与未知表防护。
- 集成测试：
  - 刷新元数据后分页查询表和字段。
  - 查询流程中无手动选表时调用粗选表名、字段感知复选，再生成 SQL。
  - 查询流程中手动选表时跳过粗选，并校验用户选表范围。
  - 模型返回未知表时不执行 SQL。
  - 模型生成不可解析 SQL 时不执行 SQL。
- 端到端测试：
  - 查询工作台在大元数据场景不默认展示字段树。
  - 元数据浏览分页切换表和字段。
  - 成功查询后流程侧栏包含表选择步骤。
- 回归测试：
  - 已有数据源列表、刷新元数据、模型配置、查询历史、反馈、分析页面不受影响。
  - 非 `SELECT` SQL 仍被拦截。
- 手动验证：
  - 构造多表多字段测试数据，观察页面不卡顿。
  - 人为让模型返回不存在表名，确认被拦截并记录日志。
  - 人为让模型返回语法错误 SQL，确认被 SQL parser 拦截。
  - 检查 `logs/text2sql-java.log` 和 `logs/text2sql-python.log` 按天滚动，保留最近 7 天。

```yaml
verification:
  commands:
    - command: "mvn -q test"
      expected: "Java tests pass"
    - command: "mvn -q -DskipTests package"
      expected: "Java package succeeds"
    - command: "npm run build"
      expected: "Frontend build succeeds"
    - command: "pytest"
      expected: "Python tests pass if test suite exists or is added"
  screenshots_required: true
  logs_to_check:
    - "logs/text2sql-java.log exists and contains sanitized query lifecycle events"
    - "logs/text2sql-python.log exists and contains sanitized model call events"
    - "No password, apiKey, passwordCipher, or raw result rows in logs"
```

## 9. 验收标准

- [ ] 规格已经评审并由用户确认。
- [ ] 查询工作台不再默认展示完整字段树。
- [ ] 查询工作台切换大数据源时页面不因字段数量大而明显卡顿。
- [ ] 元数据浏览左侧表名分页展示。
- [ ] 元数据浏览右侧字段分页展示。
- [ ] 自然语言查询未手动选表时，系统先用全部授权表名做粗选，再用候选表字段明细做复选，最后生成 SQL。
- [ ] 用户手动选表时，系统校验选表范围并跳过全表粗选。
- [ ] 表粗选使用当前数据源完整授权表名，或在超过配置上限时明确失败并提示用户。
- [ ] 模型返回不存在表名时，系统不执行 SQL。
- [ ] 生成 SQL 引用不存在表名时，系统不执行 SQL。
- [ ] 生成 SQL 无法被 SQL parser 解析时，系统不执行 SQL。
- [ ] 生成 SQL 不是单条 SELECT 时，系统不执行 SQL。
- [ ] 通过 SQL parser 后仍需通过数据库 EXPLAIN 才能执行 SELECT。
- [ ] Java 关键路径日志持久化，保留最近 7 天。
- [ ] Python 关键路径日志持久化，保留最近 7 天。
- [ ] Java 日志写入 `logs/text2sql-java.log`。
- [ ] Python 日志写入 `logs/text2sql-python.log`。
- [ ] 日志中没有数据库密码、模型 API Key、密码密文或原始查询结果行。
- [ ] 查询历史和流程侧栏能体现粗选表名、字段感知复选、SQL parser 校验、失败原因。
- [ ] README 或相关文档已同步日志路径和元数据分页行为。

## 10. 实施记录

- 关键变更：
  - 查询工作台不再加载完整字段树，改为分页加载表名、表注释和字段数量，用户可手动勾选最多 20 张表作为限定范围。
  - 元数据浏览改为左侧表分页、右侧字段分页，默认表 20 条/页、字段 50 条/页。
  - Java 新增元数据分页 API：
    - `GET /api/datasources/{id}/metadata/tables`
    - `GET /api/datasources/{id}/metadata/tables/{tableName}/columns`
  - Java 查询流程改为粗选表名、字段感知复选、最终候选表生成 SQL。
  - Java 引入 `com.github.jsqlparser:jsqlparser:5.3`，在 EXPLAIN 前解析 SQL、确认单条 `SELECT`、提取表引用并做白名单校验。
  - Python AI 服务新增：
    - `POST /nl2sql/select-table-candidates`
    - `POST /nl2sql/refine-tables`
  - Java 日志默认写入 `logs/text2sql-java.log`，Python 日志默认写入 `logs/text2sql-python.log`，均保留最近 7 天。
  - README 已同步元数据分页、分层选表、SQL parser 和日志路径说明。
- 测试命令：
  - `mvn -q test`：通过。
  - `mvn -q -DskipTests package`：通过。
  - `python -m compileall app`：通过。
  - `npm run build`：通过；普通沙箱内因 esbuild 子进程 `spawn EPERM` 失败，提升权限重跑后通过。
- 已知限制：
  - SQL parser 只能保证语法可解析、只读形态和表引用可校验，不能保证业务语义一定正确。
  - 模型粗选/复选依赖模型按 JSON 格式返回；Java 会对不存在表名重试一次并失败关闭。
  - 字段级复杂表达式校验仍主要依赖数据库 `EXPLAIN` 暴露实际错误。
- 后续工作：
  - 可增加更系统的 NL2SQL 评测集，量化表选择准确率。
  - 可继续增强字段级 parser 校验、CTE/子查询表引用提取和 SQL 方言覆盖。

## 11. 待确认问题

无。
