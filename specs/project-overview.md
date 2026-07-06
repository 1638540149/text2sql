# text2sql Project Overview Spec

Status: Accepted

## 背景

`text2sql` 是一个将自然语言文本转换为 SQL 的真实可用项目。项目后续开发采用 Spec-Driven Development：先澄清需求，再写规约，规约确认后再写代码。

当前项目第一版已确认采用 Java 主服务 + Python AI 服务 + Vue 前端 + MySQL 的三端单仓架构。第一版详细功能以 `specs/features/v1-nl2sql-system.md` 为准。

## 项目定位

- 将用户的自然语言查询意图转换为可审查的 SQL。
- 基于数据库 schema、业务术语和可选样例数据约束生成结果，减少模型编造字段。
- 默认提供 SQL 解释、字段依据、关键假设和风险提示。
- 默认不直接修改数据库，数据库访问以只读为基线。

## 目标

- 建立清晰的 text2sql 需求澄清与规格驱动流程。
- 为后续自然语言转 SQL、SQL 校验、SQL 解释、schema 管理等功能提供统一规约入口。
- 确保所有涉及数据库、SQL 执行和敏感数据的能力都有安全护栏。
- 完成 B/S 系统的端到端闭环：数据源、元数据、真实模型调用、查询、结果展示、历史、反馈和模型分析。

## 非目标

- 当前规约不允许默认执行非只读 SQL。
- 当前规约不要求连接生产数据库。
- 第一版不做高级 BI、拖拽报表、多数据源联邦查询、完整血缘图谱、多数据库同时支持和完整 RBAC。

## 系统边界

```yaml
system_boundary:
  inputs:
    - "自然语言问题"
    - "数据库 schema 或数据上下文"
    - "目标 SQL 方言"
    - "可选业务术语或样例查询"
  outputs:
    - "SQL 查询"
    - "解释说明"
    - "涉及表字段"
    - "关键假设"
    - "风险提示"
    - "验证结果"
  forbidden_by_default:
    - "执行非 SELECT SQL"
    - "修改生产数据库"
    - "输出敏感数据"
    - "在 schema 不足时编造字段且不提示假设"
    - "绕过人工审批执行外部副作用"
```

## 第一版技术栈

```yaml
frontend:
  framework: "Vue 3"
  ui: "Element Plus"
  charts: "ECharts"
backend_java:
  runtime: "Java 17"
  framework: "Spring Boot 3"
  security: "Spring Security + JWT"
  persistence: "MyBatis"
ai_service_python:
  runtime: "Python 3.10"
  framework: "FastAPI"
  llm: "OpenAI-compatible chat completions"
database:
  app_db: "MySQL database NL2SQL"
  datasource_v1: "MySQL"
```

## 默认数据与权限策略

```yaml
data_policy:
  default_database_access: "read_only"
  allowed_sql:
    - "SELECT"
  blocked_sql:
    - "INSERT"
    - "UPDATE"
    - "DELETE"
    - "DROP"
    - "ALTER"
    - "TRUNCATE"
  pii_policy:
    raw_pii_in_specs: false
    raw_pii_in_logs: false
    placeholder_format: "[[VARIABLE_NAME]]"
  ambiguity_policy:
    when_schema_missing: "ask_clarifying_question"
    when_field_uncertain: "state_assumption_or_ask"
```

## 核心工作流

```gherkin
Feature: text2sql 核心工作流

  Scenario: 用户从自然语言生成 SQL
    Given 用户提供自然语言查询意图
    And 系统拥有可用的数据表结构上下文
    When 用户请求生成 SQL
    Then 系统返回可审查的 SQL
    And 系统说明涉及的数据表、字段和关键假设

  Scenario: 用户提供的信息不足
    Given 用户只提供模糊查询意图
    And 系统缺少必要 schema 或业务语义
    When 用户请求生成 SQL
    Then 系统先提出澄清问题
    And 不编造不存在的表字段作为确定事实

  Scenario: 用户验证生成 SQL
    Given 系统已经生成 SQL
    When 用户请求验证
    Then 系统检查语法、表字段引用和潜在风险
    And 系统返回验证结果和修正建议
```

## 新功能开发流程

1. 从 `specs/templates/feature-spec-template.md` 创建 `specs/features/<feature-name>.md`。
2. 对粗略需求先提问澄清，并把回答写入 spec。
3. 写清楚背景、目标、非目标、技术方案、接口、数据结构、交互、异常处理、测试和验收标准。
4. 将状态保持为 `Draft`，直到人工 review。
5. 人工确认后改为 `Accepted`。
6. Codex 根据已确认 spec 采用最小修改范围实现代码、测试和文档。
7. 生成代码后运行相关测试或验证命令，并在回复中说明结果。
8. 实现完成并验证后，将状态更新为 `Implemented`。

## 风险清单

- 生成 SQL 引用不存在的表或字段。
- 生成非只读 SQL。
- 在缺少 schema 上下文时编造字段。
- 暴露样例数据中的敏感信息。
- 用户误把生成 SQL 直接用于生产环境。
- SQL 方言不匹配导致语法正确但运行失败。

## 验收原则

每个需求至少需要确认：

- 功能符合 spec 目标。
- 非目标没有被额外实现。
- SQL 方言、输入、输出和安全边界符合 spec。
- 生成 SQL 有解释、验证和风险提示。
- 必要测试或复现命令已经执行。
- 未擅自引入新框架或大型依赖。
- 数据库 schema 或数据修改已提前确认并完成迁移验证。
- spec、README、CHANGELOG 是否需要同步更新已经明确说明。

## 待确认问题

- 后续是否扩展 PostgreSQL、Oracle、SQL Server 等数据库。
- 后续是否引入向量检索提升大库元数据召回。
- 后续是否增加完整 RBAC、审计报表和高级 BI。
