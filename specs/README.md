# Specs

`specs/` 是 `text2sql` 的规约目录，也是用户和 Agent 的共同事实来源。所有新功能、重要重构、接口调整、数据结构变更、SQL 生成策略变化和跨模块改动，都必须先在这里形成规约，再进入编码阶段。

## 文件命名

- `project-overview.md`：项目总规约，描述项目定位、系统边界、技术栈、目录结构和通用约定。
- `production-grade-agentic-development.md`：来自课程 PDF 的生产级 Agentic AI 开发总规约。
- `features/<feature-name>.md`：单个功能或需求的规约。
- `templates/project-overview.md`：项目总规约模板。
- `templates/feature-spec-template.md`：功能规约模板。

## 状态

每份功能规约必须标记一个状态：

- `Draft`：草稿阶段，仍有待确认问题。
- `Reviewed`：已被人工 review，但还未最终确认。
- `Accepted`：已确认，可以进入编码阶段。
- `Implemented`：已实现并完成验证。

## 写作格式

- Markdown 用于背景、目标、流程、风险、测试和验收标准等叙述性内容。
- YAML 用于结构化字段、复杂配置、接口字段、状态枚举和数据结构。
- 行为验收场景使用 Gherkin 风格的 `Given / When / Then`。

## 需求澄清工作流

当用户给出粗略需求时：

1. Agent 先复述理解，不直接编码。
2. Agent 尽可能提出结构化问题，覆盖用户、场景、输入、输出、SQL 方言、数据源、权限、安全、UI/API、错误处理、测试和验收标准。
3. 用户回答后，Agent 将答案写入对应 spec。
4. 未回答或仍模糊的问题写入“待确认问题”。
5. 阻塞问题未关闭前，spec 保持 `Draft`。
6. 用户明确确认后，spec 才能变为 `Accepted`，Agent 才能进入实现。

## 开发工作流

1. 新需求先创建或更新对应 spec。
2. spec 必须写清楚目标、非目标、接口、数据结构、核心流程、异常处理、测试和验收标准。
3. spec 中有不确定点时，写入“待确认问题”，不要直接猜。
4. 人工确认 spec 后，Agent 才能进入编码。
5. 实现必须采用最小修改范围，不擅自引入新框架或大型依赖。
6. 如果涉及数据库 schema 修改，必须先说明影响范围、迁移方案、回滚风险和验证方式，并等待用户确认。
7. 生成代码后必须运行相关测试或验证命令，并在回复中说明结果。
8. 实现完成后，检查是否需要同步更新 spec、README 或 CHANGELOG。
