# text2sql V1 自然语言转 SQL 系统规约

Status: Accepted

## 0. 需求来源

本规约来自用户确认的 `text2sql MVP 补救与完整实现计划`，用于约束第一版实现。实现前必须先阅读本文件、`specs/project-overview.md` 和 `AGENTS.md`。

## 1. 背景

`text2sql` 是一个 B/S 架构自然语言转 SQL 系统。第一版目标是完成从数据源配置、元数据同步、真实大模型生成 SQL、只读查询执行、表格/图表展示、查询历史、用户评分到模型结果分析的端到端闭环。

## 2. 目标

- 提供 Java 主服务、Python AI 服务和 Vue 前端三端单仓项目。
- 支持 MySQL 数据源配置、连接测试、元数据刷新和元数据浏览。
- 支持真实 OpenAI 兼容大模型调用，后期通过后台配置 `base_url`、`api_key`、`model` 即可使用。
- 未配置模型或模型调用失败时必须明确失败，并记录到查询历史和模型分析。
- 支持查询工作台：选择数据源和模型、输入自然语言、生成 SQL、编辑 SQL、校验、EXPLAIN、执行、展示表格和图表。
- 支持查询历史、流程回放、用户评分和模型结果分析。
- 提供本地 MySQL 初始化脚本、依赖安装说明、启动说明和 Docker Compose 部署说明。

## 3. 非目标

- 第一版不做高级 BI、拖拽报表、多数据源联邦查询、完整血缘图谱、多数据库同时支持和完整 RBAC。
- 第一版不持久化完整查询结果，只保存摘要、SQL、图表配置、用户反馈和流程记录。
- Python AI 服务不得直接连接业务数据源，也不得接收数据库密码。

## 4. 技术架构

```yaml
repository:
  frontend: "Vue3 + Element Plus + ECharts"
  backend_java: "Java 17 + Spring Boot 3 + Spring Security + JWT + MyBatis"
  ai_service_python: "Python FastAPI + OpenAI-compatible HTTP client"
  database: "MySQL 8, database name NL2SQL"
runtime:
  local_python_env: "conda text2sql, Python 3.10"
  local_mysql: "root 用户初始化，后续建议改成低权限用户"
communication:
  frontend_to_java: "REST /api/* with JWT Bearer token"
  java_to_python: "HTTP JSON"
  sql_execution_owner: "backend-java only"
```

## 5. 核心模块

```yaml
modules:
  auth:
    roles: ["ADMIN", "USER"]
    behavior: "管理员可管理数据源、模型和分析；普通用户查询和查看自己的历史"
  datasource:
    fields: ["name", "host", "port", "databaseName", "username", "password", "remark"]
    password_storage: "AES-GCM encrypted"
    permissions: "按用户授权"
  metadata:
    scope: ["database", "tables", "columns", "indexes", "comments"]
    refresh: "manual"
  query:
    flow:
      - "用户选择数据源和模型"
      - "可选限定表范围"
      - "Java 传递授权后的元数据摘要给 Python"
      - "Python 调用模型生成 SQL"
      - "Python/Java 校验 SQL"
      - "Java 执行 EXPLAIN"
      - "Java 执行只读 SELECT"
      - "Python 推荐图表"
      - "Java 保存历史和流程摘要"
  analytics:
    visibility: "ADMIN only"
    metrics:
      - "successRate"
      - "failureRate"
      - "callCount"
      - "avgDurationMs"
      - "p95DurationMs"
      - "tokens"
      - "costEstimate"
      - "avgRows"
      - "avgFeedback"
```

## 6. AI 服务接口

```yaml
ai_service:
  POST /nl2sql/generate:
    input: ["question", "dialect", "metadata", "model"]
    output: ["sql", "explanation", "assumptions", "promptTokens", "completionTokens", "durationMs", "modelCall"]
  POST /sql/validate:
    input: ["sql", "metadata"]
    output: ["valid", "message", "repairedSql"]
  POST /chart/recommend:
    input: ["question", "fields", "rows"]
    output: ["type", "title", "option"]
security:
  model_context: "只允许元数据摘要，不发送真实数据行"
  database_credentials: "不得发送给 Python"
```

## 7. 查询安全

- 只允许执行 `SELECT`。
- 禁止多语句和 `INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE/GRANT/REVOKE`。
- 执行前自动追加或要求 `LIMIT`。
- 执行前运行 `EXPLAIN`。
- EXPLAIN 高风险时需用户确认后才执行。
- 查询超时和最大返回行数由系统配置控制。

## 8. 前端页面

```yaml
pages:
  - "登录页"
  - "数据源管理"
  - "元数据浏览"
  - "查询工作台"
  - "查询历史"
  - "模型配置"
  - "模型分析"
query_workspace:
  layout: "左侧数据源和元数据，中间问题/SQL/结果，右侧流程侧栏"
  result_views: ["table", "bar", "line", "pie"]
```

## 9. 验收场景

```gherkin
Feature: text2sql V1 端到端闭环

  Scenario: 管理员完成数据源配置并同步元数据
    Given 管理员已登录
    When 管理员测试 MySQL 数据源连接并刷新元数据
    Then 系统展示表、字段、索引和注释信息

  Scenario: 用户通过自然语言查询并查看图表
    Given 用户已被授权访问数据源
    When 用户选择模型并输入自然语言问题
    Then 系统生成可编辑 SQL
    And 系统校验 SQL 并执行 EXPLAIN
    And 系统执行只读查询并展示表格和推荐图表

  Scenario: 用户提交反馈并由管理员查看模型分析
    Given 用户已完成一次查询
    When 用户提交 1-5 分、标签和评论
    Then 管理员在模型分析页看到成功率、耗时、token、成本和评分指标更新
```

## 10. 验收清单

- [ ] `NL2SQL` 数据库和应用表可初始化。
- [ ] README 覆盖配置、安装、启动、部署、模型配置和使用说明。
- [ ] 前端依赖可安装，`npm run build` 可通过。
- [ ] Python 依赖可安装，AI 服务接口可访问。
- [ ] Java 服务可编译并启动。
- [ ] 默认管理员和普通用户可登录。
- [ ] 能配置真实 MySQL 数据源并刷新元数据。
- [ ] 能调用真实 OpenAI 兼容模型；无 Key 或调用失败时明确失败并保存失败原因。
- [ ] 非 `SELECT` 查询被拦截。
- [ ] 查询历史、反馈和模型分析可用。
