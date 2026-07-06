# text2sql 功能规约模板

> 使用方式：复制本模板为 `specs/features/<feature-name>.md`，补齐内容并评审后再开始实现。

```yaml
metadata:
  project: "text2sql"
  feature_name: ""
  status: "draft"
  owner: ""
  reviewers: []
  created_at: ""
  updated_at: ""
  related_docs:
    - "../production-grade-agentic-development.md"
```

## 0. 需求澄清

当原始需求比较粗略时，先在本节记录问题和回答。不要在关键问题未确认时进入编码。

### 已确认回答

```yaml
confirmed_answers:
  users: ""
  primary_workflow: ""
  inputs: []
  outputs: []
  sql_dialects: []
  data_sources: []
  permissions: ""
  ui_or_api_shape: ""
  acceptance_examples: []
```

### 建议提问清单

- 这个功能主要给谁用？
- 用户会输入什么？自然语言、schema、样例数据、历史 SQL 是否都会提供？
- 期望输出是什么？只要 SQL，还是需要解释、字段依据、风险提示和验证结果？
- 目标 SQL 方言是什么？
- 是否允许连接真实数据库？如果允许，是否强制只读？
- 是否允许执行 SQL，还是只生成和校验？
- 遇到歧义问题时，是追问用户、给多个候选 SQL，还是使用默认假设？
- 成功验收用哪些样例问题和 schema？
- 有哪些明确不做的内容？

## 1. 背景

说明这个功能为什么存在、服务谁、解决什么问题。

- 用户/角色：
- 当前痛点：
- 业务或学习目标：
- 成功后的可观察结果：

## 2. 范围

### 目标

- 

### 非目标

- 

本次包含：

- 

本次不包含：

- 

## 3. 用户故事

- 作为 `<角色>`，我希望 `<能力>`，从而 `<收益>`。

## 4. 行为场景

```gherkin
Feature: <功能名称>

  Background:
    Given 用户正在使用 text2sql

  Scenario: 正常路径
    Given <前置状态>
    When <用户动作或系统事件>
    Then <可验证结果>

  Scenario: 边界路径
    Given <边界输入或状态>
    When <用户动作或系统事件>
    Then <系统应如何处理>

  Scenario: 错误路径
    Given <错误输入或异常状态>
    When <用户动作或系统事件>
    Then <错误提示、回滚或降级行为>
```

## 5. 技术设计

```yaml
design:
  affected_areas:
    backend: []
    frontend: []
    database: []
    docs: []
  modules:
    - name: ""
      responsibility: ""
  data_flow:
    - step: 1
      description: ""
  dependencies:
    runtime: []
    development: []
    new_large_dependency_required: false
    confirmation_status: "not_required"
  logging:
    required_events: []
  error_handling:
    expected_errors: []
```

## 6. 数据与 API 合约

```yaml
contracts:
  inputs:
    - name: ""
      type: ""
      required: true
      validation: ""
  outputs:
    - name: ""
      type: ""
      description: ""
  api:
    endpoint: ""
    method: ""
    request_schema: {}
    response_schema: {}
  persistence:
    tables: []
    migrations_required: false
```

## 7. 安全与护栏

- 是否涉及敏感数据：
- 是否涉及外部副作用：
- 是否需要人工确认：
- 是否需要沙箱运行：
- 是否需要策略拦截：

```yaml
guardrails:
  pii_allowed: false
  secrets_allowed: false
  production_side_effects_allowed: false
  human_approval_required: false
  rollback_plan: ""
```

## 8. 测试计划

- 单元测试：
- 集成测试：
- 端到端测试：
- 回归测试：
- 手动验证：

```yaml
verification:
  commands:
    - command: ""
      expected: ""
  screenshots_required: false
  logs_to_check: []
```

## 9. 验收标准

- [ ] 规格已经评审。
- [ ] 行为场景全部实现。
- [ ] 正常、边界、错误路径都有测试或明确验证证据。
- [ ] 文档已同步。
- [ ] 没有硬编码密钥、PII 或生产 URL。
- [ ] 高风险操作已人工确认。

## 10. 实施记录

实现完成后补充：

- 关键变更：
- 测试命令：
- 已知限制：
- 后续工作：

## 11. 待确认问题

- 
