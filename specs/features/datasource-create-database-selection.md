# 数据源新增表单与数据库下拉选择规约

```yaml
metadata:
  project: "text2sql"
  feature_name: "datasource-create-database-selection"
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

数据源管理中新增数据源时，需要让用户选择数据库类型，然后填写 host、端口、用户和密码。数据库字段改为下拉框，当用户填写完连接参数后，再选择数据库。host、端口、用户、密码、数据库和名称均不能为空。

### 已确认回答

```yaml
confirmed_answers:
  users: "管理员"
  primary_workflow: "管理员新增数据源时先填写连接参数，再从远程数据库列表中选择具体数据库"
  inputs:
    - "数据库类型"
    - "host"
    - "port"
    - "username"
    - "password"
    - "databaseName"
    - "name"
  outputs:
    - "可保存的数据源配置"
    - "可选择的数据库列表"
  sql_dialects:
    - "MySQL first"
  data_sources:
    - "真实 MySQL 实例"
    - "V2 预留 PostgreSQL、Oracle、SQL Server 等多数据源"
  permissions: "仅管理员可新增数据源、加载数据库列表"
  ui_or_api_shape: "前端表单 + Java REST API"
  acceptance_examples:
    - "连接参数未填完整时不能加载数据库列表"
    - "数据库名称通过下拉框选择"
    - "必填项为空时不能保存"
    - "V1 数据库类型下拉只启用 MySQL"
    - "数据库下拉默认过滤 MySQL 系统库"
    - "连接参数填写完整后自动加载数据库列表，不使用加载按钮"
```

### 已确认问题

- 第一版数据库类型下拉只启用 `MySQL`，V2 再支持多数据源。
- 数据库下拉列表默认排除 MySQL 系统库：`information_schema`、`mysql`、`performance_schema`、`sys`。
- 加载数据库列表采用自动触发：当数据库类型、host、端口、用户名、密码填写完整后自动加载。

## 1. 背景

当前数据源新增表单允许用户手动输入数据库名，容易出现数据库名拼写错误、连接参数缺失、保存后才发现不可用等问题。真实可用项目中，新增数据源应先验证连接参数，再基于连接参数拉取可访问数据库列表，降低配置错误率。

- 用户/角色：管理员。
- 当前痛点：数据库名手工输入，缺少连接参数完整性校验和数据库列表选择。
- 成功后的可观察结果：管理员能从真实数据库实例中加载数据库列表，并选择一个数据库保存为数据源。

## 2. 范围

### 目标

- 新增数据源表单提供数据库类型下拉。
- 表单按顺序填写：数据库类型、host、端口、用户名、密码。
- 数据库字段改为下拉框。
- 连接参数填写完整后，自动加载数据库列表。
- 保存时校验名称、数据库类型、host、端口、用户名、密码、数据库名均不能为空。
- 后端新增加载数据库列表接口，使用临时连接获取数据库列表，不持久化密码。
- 创建数据源时保存数据库类型，而不是后端硬编码 `MYSQL`。

### 非目标

- 不在本次实现多数据库连接器完整支持。
- 不在本次实现数据源编辑。
- 不在本次保存加载数据库列表时输入的连接参数，只有点击保存数据源时才持久化。
- 不在本次改变数据源授权、元数据刷新、查询执行逻辑。

本次包含：

- 前端数据源新增弹窗交互优化。
- Java 后端数据源创建参数校验。
- Java 后端加载数据库列表 API。
- MySQL 连接器列出数据库能力。

本次不包含：

- Oracle、PostgreSQL、SQL Server 的真实连接实现。
- 数据源密码更新能力。
- 数据源连接池长期缓存。

## 3. 用户故事

- 作为管理员，我希望新增数据源时先选择数据库类型并填写连接参数，再从可访问数据库列表中选择数据库，从而避免数据库名输错。
- 作为管理员，我希望必填项为空时系统及时提示，从而减少无效数据源配置。

## 4. 行为场景

```gherkin
Feature: 新增数据源时通过连接参数加载数据库列表

  Background:
    Given 管理员已登录 text2sql
    And 管理员正在打开数据源管理页面

  Scenario: 正常自动加载数据库并保存数据源
    Given 管理员选择数据库类型 MySQL
    And 管理员填写 host、端口、用户名和密码
    When 连接参数填写完整
    Then 系统返回该账号可访问的数据库列表
    When 管理员选择数据库并填写数据源名称
    And 管理员点击保存
    Then 系统保存数据源
    And 数据源列表展示新数据源

  Scenario: 连接参数未填完整
    Given 管理员未填写完整 host、端口、用户名或密码
    When 管理员查看数据库下拉框
    Then 系统不加载数据库列表
    And 不向后端发送加载数据库请求

  Scenario: 保存时存在空必填项
    Given 管理员打开新增数据源表单
    And 名称、数据库类型、host、端口、用户名、密码或数据库为空
    When 管理员点击保存
    Then 系统提示对应字段不能为空
    And 不保存数据源

  Scenario: 数据库连接失败
    Given 管理员填写了错误 host、端口、用户名或密码
    When 连接参数填写完整并触发自动加载
    Then 系统展示连接失败原因
    And 数据库下拉框保持为空
```

## 5. 技术设计

```yaml
design:
  affected_areas:
    backend:
      - "DataSourceController"
      - "JdbcDatasourceService"
      - "CoreMapper"
    frontend:
      - "frontend/src/views/DataSources.vue"
    database:
      - "data_source.db_type 已存在，无需新增字段"
    docs:
      - "README 如新增启动或使用说明则同步"
  modules:
    - name: "DataSourceController"
      responsibility: "提供加载数据库列表和创建数据源 API"
    - name: "JdbcDatasourceService"
      responsibility: "根据临时连接参数测试连接并列出数据库"
    - name: "DataSources.vue"
      responsibility: "新增数据源表单交互、必填校验、数据库下拉选择"
  data_flow:
    - step: 1
      description: "管理员填写 dbType、host、port、username、password"
    - step: 2
      description: "前端监听连接参数，完整后自动调用加载数据库 API"
    - step: 3
      description: "后端使用临时 JDBC 连接列出数据库名"
    - step: 4
      description: "前端将数据库名展示为下拉选项"
    - step: 5
      description: "管理员选择 databaseName、填写 name 后保存数据源"
  dependencies:
    runtime: []
    development: []
    new_large_dependency_required: false
    confirmation_status: "not_required"
  logging:
    required_events:
      - "数据库列表加载失败时记录异常摘要，不记录密码"
  error_handling:
    expected_errors:
      - "连接参数缺失"
      - "数据库类型暂不支持"
      - "连接失败"
      - "账号无权限列出数据库"
```

## 6. 数据与 API 合约

```yaml
contracts:
  inputs:
    - name: "dbType"
      type: "string"
      required: true
      validation: "第一版仅允许 MYSQL"
    - name: "host"
      type: "string"
      required: true
      validation: "非空"
    - name: "port"
      type: "integer"
      required: true
      validation: "1-65535"
    - name: "username"
      type: "string"
      required: true
      validation: "非空"
    - name: "password"
      type: "string"
      required: true
      validation: "非空"
    - name: "databaseName"
      type: "string"
      required: true
      validation: "必须来自下拉选择或后端可访问数据库列表"
    - name: "name"
      type: "string"
      required: true
      validation: "非空"
  outputs:
    - name: "databases"
      type: "string[]"
      description: "当前连接账号可访问的数据库列表"
  api:
    endpoint: "/api/datasources/databases"
    method: "POST"
    request_schema:
      dbType: "MYSQL"
      host: "localhost"
      port: 3306
      username: "root"
      password: "***"
    response_schema:
      databases:
        - "business_db"
  persistence:
    tables:
      - "data_source"
    migrations_required: false
```

创建数据源 API 保持 `POST /api/datasources`，但请求体需包含 `dbType`：

```yaml
create_datasource_request:
  dbType: "MYSQL"
  name: "业务库"
  host: "localhost"
  port: 3306
  databaseName: "business_db"
  username: "reader"
  password: "***"
  remark: ""
```

## 7. 安全与护栏

- 涉及敏感数据：数据库用户名和密码。
- 外部副作用：加载数据库列表只建立临时连接，不持久化；保存数据源才加密持久化密码。
- 人工确认：不需要额外确认。
- 策略拦截：只有管理员可加载数据库列表和保存数据源。

```yaml
guardrails:
  pii_allowed: false
  secrets_allowed: true
  production_side_effects_allowed: false
  human_approval_required: false
  rollback_plan: "该功能不新增数据库结构；如前端交互异常，可回退 DataSources.vue 与新增 API 代码"
```

## 8. 测试计划

- 单元测试：连接参数校验、dbType 校验。
- 集成测试：使用本地 MySQL 验证加载数据库列表。
- 端到端测试：前端新增数据源流程。
- 回归测试：已有数据源列表、连接测试、元数据刷新不受影响。
- 手动验证：
  - 连接参数缺失时不加载数据库。
  - 错误密码时展示错误提示。
  - 正确连接参数能加载数据库下拉。
  - 必填项为空时不能保存。

```yaml
verification:
  commands:
    - command: "mvn -q -DskipTests package"
      expected: "Java 编译通过"
    - command: "npm run build"
      expected: "前端构建通过"
  screenshots_required: true
  logs_to_check:
    - "后端不输出数据库密码"
```

## 9. 验收标准

- [ ] 规格已经评审并由用户确认。
- [ ] 新增数据源表单包含数据库类型下拉。
- [ ] host、端口、用户名、密码填写完整后自动加载数据库列表。
- [ ] 数据库字段为下拉框。
- [ ] 名称、数据库类型、host、端口、用户名、密码、数据库均为空校验。
- [ ] 后端创建数据源不再硬编码数据库类型。
- [ ] 加载数据库列表接口仅管理员可用。
- [ ] 加载数据库列表不持久化密码。
- [ ] 连接失败时前端展示错误信息。
- [ ] 文档已同步。

## 10. 实施记录

- 关键变更：
  - 后端新增 `POST /api/datasources/databases`，仅管理员可调用。
  - 后端创建数据源请求增加 `dbType`，并保存到 `data_source.db_type`。
  - MySQL 数据库列表通过临时 JDBC 连接获取，过滤 `information_schema`、`mysql`、`performance_schema`、`sys`。
  - 前端新增数据源弹窗增加数据库类型下拉；连接参数完整后自动加载数据库列表；数据库字段改为下拉选择。
  - 前端保存前校验名称、数据库类型、Host、端口、用户名、密码、数据库均不能为空。
- 测试命令：
  - `mvn -q -DskipTests package`：通过。
  - `npm run build`：通过；普通沙箱内因 esbuild 子进程 `spawn EPERM` 失败，提升权限重跑后通过。
- 已知限制：
  - V1 仅启用 MySQL。
  - V2 再扩展 PostgreSQL、Oracle、SQL Server 等多数据源连接器。
- 后续工作：
  - V2 抽象统一数据库连接器接口和各数据库元数据读取策略。

## 11. 待确认问题

- 暂无。
