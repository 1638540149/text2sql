# text2sql 项目总览模板

> 使用方式：复制本模板为 `specs/project-overview.md`，作为仓库级项目事实来源。后续功能规约应引用它。

```yaml
metadata:
  project: "text2sql"
  status: "draft"
  owner: ""
  reviewers: []
  created_at: ""
  updated_at: ""
  source_spec:
    - "../production-grade-agentic-development.md"
```

## 1. 项目定位

`text2sql` 是一个将自然语言文本转换为 SQL 的项目。

请补充：

- 目标用户：
- 主要使用场景：
- 支持的数据源：
- 目标数据库方言：
- 不支持或暂不支持的能力：

## 2. 产品目标

- 

## 3. 非目标

- 

## 4. 核心工作流

```gherkin
Feature: text2sql 核心工作流

  Scenario: 用户从自然语言生成 SQL
    Given 用户提供自然语言查询意图
    And 系统拥有可用的数据表结构上下文
    When 用户请求生成 SQL
    Then 系统返回可审查的 SQL
    And 系统说明涉及的数据表、字段和关键假设

  Scenario: 用户验证生成 SQL
    Given 系统已经生成 SQL
    When 用户请求验证
    Then 系统检查语法、表字段引用和潜在风险
    And 系统返回验证结果和修正建议
```

## 5. 系统边界

```yaml
system_boundary:
  inputs:
    - "自然语言问题"
    - "数据库 schema 或数据上下文"
    - "目标 SQL 方言"
  outputs:
    - "SQL 查询"
    - "解释说明"
    - "风险提示"
    - "验证结果"
  external_systems:
    - name: ""
      purpose: ""
      access_mode: "read_only"
  forbidden_by_default:
    - "执行非 SELECT SQL"
    - "修改生产数据库"
    - "输出敏感数据"
    - "绕过人工审批执行外部副作用"
```

## 6. 数据与权限

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
  pii_policy:
    raw_pii_in_specs: false
    raw_pii_in_logs: false
    placeholder_format: "[[VARIABLE_NAME]]"
```

## 7. 技术栈

待补充：

```yaml
tech_stack:
  language: ""
  framework: ""
  database: ""
  llm_provider: ""
  testing:
    unit: ""
    integration: ""
    e2e: ""
  linting: ""
  packaging: ""
```

## 8. 目录约定

```yaml
directories:
  specs: "规格、行为场景、API 合约和验收标准"
  specs/templates: "可复用规格模板"
  src: "产品代码"
  tests: "自动化测试"
  docs: "用户或开发文档"
```

## 9. 风险清单

- 生成 SQL 引用不存在的表或字段。
- 生成非只读 SQL。
- 在缺少 schema 上下文时编造字段。
- 暴露样例数据中的敏感信息。
- 用户误把生成 SQL 直接用于生产环境。

## 10. 验收清单

- [ ] 项目目标清楚。
- [ ] 支持的 SQL 方言明确。
- [ ] 数据访问默认只读。
- [ ] schema 上下文来源明确。
- [ ] 生成 SQL 有解释、验证和风险提示。
- [ ] 高风险操作需要人工确认。
