# text2sql

`text2sql` 是一个 B/S 架构自然语言转 SQL 系统。用户可以配置 MySQL 数据源，刷新数据库元数据，通过真实 OpenAI 兼容大模型生成 SQL，执行只读查询，并用表格与图表查看结果。系统还会保存查询历史、流程摘要、用户评分，并提供管理员可见的模型结果分析。

## 功能范围

- 登录与权限：管理员、普通用户。
- 数据源管理：MySQL 数据源配置、连接测试、用户授权。
- 元数据管理：表、字段、索引、注释同步与浏览。
- 查询工作台：自然语言问题、模型选择、懒加载表范围选择、分层选表、结构化 SQL 生成、SQL 编辑、SQL parser 校验、EXPLAIN、只读执行。
- 结果展示：表格、柱状图、折线图、饼图。
- 查询历史：保存问题、SQL、状态、失败原因、耗时、token、成本、流程摘要和评分。
- 模型配置：OpenAI 兼容 `base_url`、`api_key`、`model`、token 单价。
- 模型分析：成功率、失败率、调用次数、平均耗时、P95、token、成本、平均结果行数、评分均值。

## 技术架构

```text
frontend           Vue3 + Element Plus + ECharts
backend-java       Java 17 + Spring Boot 3 + Spring Security + JWT + MyBatis
ai-service-python  Python 3.10 + FastAPI + OpenAI-compatible HTTP client
database           MySQL 8, database name NL2SQL
```

SQL 执行只在 Java 主服务中完成。Python AI 服务只接收授权后的元数据摘要，不接收数据库密码，也不直接连接业务数据源。模型生成 SQL 时会先输出结构化计划和最终 SQL；Java 主服务会使用 SQL parser 校验单条只读 `SELECT`，再进行表名白名单校验和 `EXPLAIN`。如果 parser、AI 校验或 `EXPLAIN` 失败，系统会把脱敏错误反馈给模型自动修复一次。

## 目录结构

```text
backend-java/        Java 主服务
ai-service-python/   Python AI 服务
frontend/            Vue 前端
devops/mysql/        本地 MySQL 初始化脚本
specs/               规约文档
```

## 默认账号

```text
管理员：admin / admin123
普通用户：user / user123
```

## 本地配置

本地 MySQL 使用：

```text
database: NL2SQL
username: root
password: 使用你的本地 MySQL root 密码
```

建议只在本地开发中使用 root。后续部署请创建低权限账号，并只授予 `NL2SQL` 所需权限。

## 初始化 MySQL

在仓库根目录执行：

```powershell
mysql -uroot -p < devops/mysql/init-local.sql
```

输入你的本地 MySQL root 密码后，脚本会创建 `NL2SQL` 数据库、应用表、默认账号和系统阈值配置。业务数据源需要管理员在前端“数据源管理”中手动配置。

如果你之前运行过旧版本并产生了演示模型、演示数据源或 `demo_orders` 表，可以手动执行清理脚本：

```powershell
mysql -uroot -p < devops/mysql/cleanup-demo-artifacts.sql
```

## Python AI 服务

使用你已经创建的 conda 环境：

```powershell
conda activate text2sql
cd ai-service-python
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

也可以使用 conda 环境文件安装：

```powershell
conda env update -n text2sql -f ai-service-python/environment.yml
```

健康检查：

```powershell
curl http://localhost:8000/health
```

日志默认写入：

```text
logs/text2sql-python.log
```

## Java 主服务

确认 [application.yml](backend-java/src/main/resources/application.yml) 中的 MySQL 配置正确后启动：

```powershell
cd backend-java
mvn spring-boot:run
```

服务地址：

```text
http://localhost:8080
```

日志默认写入：

```text
logs/text2sql-java.log
```

Java 和 Python 日志均按天滚动，保留最近 7 天。可通过 `TEXT2SQL_LOG_DIR` 指定统一日志目录，例如在 PowerShell 中：

```powershell
$env:TEXT2SQL_LOG_DIR="..\logs"
```

## 前端

```powershell
cd frontend
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

## 模型获取与配置流程

系统使用 OpenAI 兼容接口。你可以使用任意兼容 `/chat/completions` 的模型服务。

配置步骤：

1. 登录管理员账号。
2. 打开“模型配置”。
3. 新增模型：
   - 名称：例如 `Qwen Plus`、`DeepSeek Chat`、`GPT`。
   - Base URL：模型服务的 OpenAI 兼容地址，例如 `https://api.example.com/v1`。
   - API Key：你的模型密钥，后端会加密入库。
   - Model：模型名，例如 `deepseek-chat`、`qwen-plus`。
   - 输入/输出 token 单价：用于模型分析页估算成本，可填 0。
4. 启用模型。
5. 点击“测试”确认模型服务可调用。
6. 在“查询工作台”中选择该模型进行查询。

没有配置真实模型或模型调用失败时，系统会明确返回失败并记录到查询历史与模型分析，不会使用本地规则伪造模型结果。

## 使用流程

1. 管理员登录。
2. 在“数据源管理”中创建数据源：选择数据库类型，填写 Host、端口、用户名和密码，系统会自动加载可访问数据库列表，再选择数据库并保存。
3. 测试连接，必要时授权用户访问数据源。
4. 在“元数据”或“数据源管理”中刷新元数据。
5. 进入“查询工作台”。
6. 选择数据源和模型。
7. 可选：分页搜索并勾选表范围。
8. 点击“生成并执行”。
9. 系统会先粗选表名，再基于候选表字段复选，最后生成 SQL。
10. 系统会校验 SQL；如 parser、AI 校验或 EXPLAIN 失败，会自动修复一次。
11. 查看生成 SQL、执行流程、表格和图表。
12. 对结果提交评分。
13. 管理员进入“模型分析”查看模型成功率、耗时、token、成本和失败原因分布。

## Docker Compose 部署

```powershell
docker compose up --build
```

服务端口：

```text
frontend: http://localhost:5173
backend:  http://localhost:8080
ai:       http://localhost:8000
mysql:    localhost:3306
```

如需修改 MySQL root 密码：

```powershell
$env:MYSQL_ROOT_PASSWORD="your_password"
docker compose up --build
```

## 验证命令

```powershell
cd ai-service-python
conda run -n text2sql python -m compileall app

cd ../backend-java
mvn package -DskipTests

cd ../frontend
npm run build
```

## 常见问题

### 为什么生成 SQL 不准？

请先确认 Python AI 服务已启动、模型配置测试通过、数据源元数据已刷新。查询工作台未手动选表时，系统会先让模型粗选表名再基于字段复选；如果你已知道相关范围，也可以手动勾选表来提高准确率。

系统会提示模型遵守这些 SQL 质量规则：禁止 `SELECT *`、只使用候选表字段、聚合查询匹配 `GROUP BY`、趋势问题优先使用时间字段、排序字段必须可解释。必要时可以结合生成 SQL、执行流程和查询历史中的失败原因继续调整模型配置或表字段注释。

### 为什么不能执行 UPDATE/DELETE？

第一版强制只读查询，只允许 `SELECT`。这是为了防止自然语言误操作数据库。

### 为什么 EXPLAIN 后需要确认？

当执行计划估算扫描行数超过阈值时，系统会提示风险并要求确认，避免慢查询拖垮数据库。

### 密钥是否会返回前端？

不会。模型 API Key 会加密入库，列表接口不会返回密钥明文。
