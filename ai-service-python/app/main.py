from __future__ import annotations

import json
import logging
import os
import re
import time
from logging.handlers import TimedRotatingFileHandler
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


app = FastAPI(title="text2sql AI Service", version="0.1.0")
logger = logging.getLogger("text2sql-python")


def configure_logging() -> None:
    log_dir = Path(os.getenv("TEXT2SQL_LOG_DIR", "logs"))
    log_dir.mkdir(parents=True, exist_ok=True)
    handler = TimedRotatingFileHandler(
        log_dir / "text2sql-python.log",
        when="midnight",
        backupCount=7,
        encoding="utf-8",
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s service=text2sql-python - %(message)s"))
    logger.setLevel(logging.INFO)
    logger.propagate = True
    if not any(isinstance(existing, TimedRotatingFileHandler) for existing in logger.handlers):
        logger.addHandler(handler)


configure_logging()


class ModelConfig(BaseModel):
    name: str | None = None
    baseUrl: str | None = None
    modelName: str | None = None
    apiKey: str | None = None


class GenerateRequest(BaseModel):
    question: str
    dialect: str = "MYSQL"
    metadata: list[dict[str, Any]] = Field(default_factory=list)
    model: ModelConfig | None = None
    feedback: str = ""
    glossary: list[dict[str, Any]] = Field(default_factory=list)
    examples: list[dict[str, Any]] = Field(default_factory=list)


class TableCandidateRequest(BaseModel):
    question: str
    dialect: str = "MYSQL"
    tables: list[dict[str, Any]] = Field(default_factory=list)
    model: ModelConfig | None = None
    invalidTables: list[str] = Field(default_factory=list)
    maxTables: int = 20


class RefineTablesRequest(BaseModel):
    question: str
    dialect: str = "MYSQL"
    candidateMetadata: list[dict[str, Any]] = Field(default_factory=list)
    model: ModelConfig | None = None
    invalidTables: list[str] = Field(default_factory=list)
    maxTables: int = 8


class ValidateRequest(BaseModel):
    sql: str
    metadata: list[dict[str, Any]] = Field(default_factory=list)


class ChartRequest(BaseModel):
    question: str = ""
    fields: list[dict[str, Any]] = Field(default_factory=list)
    rows: list[dict[str, Any]] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "ok"}


@app.post("/nl2sql/generate")
async def generate(req: GenerateRequest) -> dict[str, Any]:
    started = time.perf_counter()
    ensure_model(req.model)
    try:
        generation = await call_openai_compatible(req)
        sql = str(generation.get("sql", ""))
    except httpx.HTTPStatusError as exc:
        body = exc.response.text[:300] if exc.response is not None else ""
        logger.warning(
            "event=sql_generate success=false model=%s durationMs=%s reason=%s",
            req.model.modelName if req.model else "",
            elapsed_ms(started),
            sanitize(f"HTTP {exc.response.status_code} {body}"),
        )
        raise HTTPException(status_code=502, detail=f"模型接口返回错误: HTTP {exc.response.status_code} {body}") from exc
    except Exception as exc:
        logger.warning(
            "event=sql_generate success=false model=%s durationMs=%s reason=%s",
            req.model.modelName if req.model else "",
            elapsed_ms(started),
            sanitize(str(exc)),
        )
        raise HTTPException(status_code=502, detail=f"模型调用失败: {exc}") from exc
    logger.info(
        "event=sql_generate success=true model=%s tableCount=%s sqlLength=%s durationMs=%s",
        req.model.modelName if req.model else "",
        len(req.metadata),
        len(sql),
        elapsed_ms(started),
    )

    return {
        "sql": sql,
        "explanation": generation.get("explanation", "由 OpenAI 兼容模型生成。"),
        "assumptions": generation.get("assumptions", ["仅使用授权后的元数据摘要。"]),
        "plan": generation.get("plan", {}),
        "promptTokens": estimate_tokens(req.question + str(req.metadata) + str(req.glossary) + str(req.examples)),
        "completionTokens": estimate_tokens(sql),
        "durationMs": int((time.perf_counter() - started) * 1000),
        "modelCall": {"mode": "openai-compatible", "model": req.model.modelName},
    }


@app.post("/nl2sql/select-table-candidates")
async def select_table_candidates(req: TableCandidateRequest) -> dict[str, Any]:
    started = time.perf_counter()
    ensure_model(req.model)
    try:
        result = await call_table_selection(req)
    except httpx.HTTPStatusError as exc:
        body = exc.response.text[:300] if exc.response is not None else ""
        logger.warning(
            "event=coarse_table_selection success=false model=%s durationMs=%s reason=%s",
            req.model.modelName if req.model else "",
            elapsed_ms(started),
            sanitize(f"HTTP {exc.response.status_code} {body}"),
        )
        raise HTTPException(status_code=502, detail=f"模型接口返回错误: HTTP {exc.response.status_code} {body}") from exc
    except Exception as exc:
        logger.warning(
            "event=coarse_table_selection success=false model=%s durationMs=%s reason=%s",
            req.model.modelName if req.model else "",
            elapsed_ms(started),
            sanitize(str(exc)),
        )
        raise HTTPException(status_code=502, detail=f"表名粗选失败: {exc}") from exc
    logger.info(
        "event=coarse_table_selection success=true model=%s tableCount=%s resultCount=%s durationMs=%s",
        req.model.modelName if req.model else "",
        len(req.tables),
        len(result.get("tables", [])),
        elapsed_ms(started),
    )
    return result


@app.post("/nl2sql/refine-tables")
async def refine_tables(req: RefineTablesRequest) -> dict[str, Any]:
    started = time.perf_counter()
    ensure_model(req.model)
    try:
        result = await call_table_refinement(req)
    except httpx.HTTPStatusError as exc:
        body = exc.response.text[:300] if exc.response is not None else ""
        logger.warning(
            "event=field_aware_table_selection success=false model=%s durationMs=%s reason=%s",
            req.model.modelName if req.model else "",
            elapsed_ms(started),
            sanitize(f"HTTP {exc.response.status_code} {body}"),
        )
        raise HTTPException(status_code=502, detail=f"模型接口返回错误: HTTP {exc.response.status_code} {body}") from exc
    except Exception as exc:
        logger.warning(
            "event=field_aware_table_selection success=false model=%s durationMs=%s reason=%s",
            req.model.modelName if req.model else "",
            elapsed_ms(started),
            sanitize(str(exc)),
        )
        raise HTTPException(status_code=502, detail=f"字段感知复选失败: {exc}") from exc
    logger.info(
        "event=field_aware_table_selection success=true model=%s candidateCount=%s resultCount=%s durationMs=%s",
        req.model.modelName if req.model else "",
        len(req.candidateMetadata),
        len(result.get("tables", [])),
        elapsed_ms(started),
    )
    return result


@app.post("/sql/validate")
def validate(req: ValidateRequest) -> dict[str, Any]:
    started = time.perf_counter()
    sql = strip_sql(req.sql)
    lower = sql.lower()
    if not lower.startswith("select") and not lower.startswith("with "):
        logger.info("event=sql_validate success=false reason=not_select durationMs=%s", elapsed_ms(started))
        return {"valid": False, "message": "只允许 SELECT SQL"}
    if ";" in sql or re.search(r"\b(insert|update|delete|drop|alter|truncate|create|grant|revoke)\b", lower):
        logger.info("event=sql_validate success=false reason=dangerous_keyword durationMs=%s", elapsed_ms(started))
        return {"valid": False, "message": "SQL 包含危险关键字或多语句"}

    known_tables = {str(t.get("tableName", "")).lower() for t in req.metadata}
    if known_tables:
        referenced = {
            m.group(2).lower()
            for m in re.finditer(r"\b(?:from|join)\s+(?!\()(?:(?:`?([a-zA-Z_][\w]*)`?)\.)?`?([a-zA-Z_][\w]*)`?", lower)
        }
        unknown = sorted(t for t in referenced if t not in known_tables)
        if unknown:
            logger.info("event=sql_validate success=false reason=unknown_table unknownCount=%s durationMs=%s", len(unknown), elapsed_ms(started))
            return {"valid": False, "message": f"引用了未知表: {', '.join(unknown)}"}
    logger.info("event=sql_validate success=true durationMs=%s", elapsed_ms(started))
    return {"valid": True, "message": "SQL 校验通过", "repairedSql": None}


@app.post("/chart/recommend")
def chart(req: ChartRequest) -> dict[str, Any]:
    if not req.fields:
        return {"type": "table", "title": "查询结果", "option": {}}

    categories = [f["name"] for f in req.fields if not is_numeric_type(str(f.get("type", "")))]
    numbers = [f["name"] for f in req.fields if is_numeric_type(str(f.get("type", "")))]
    dates = [f["name"] for f in req.fields if is_date_type(str(f.get("type", "")))]

    if dates and numbers:
        x = dates[0]
        y = numbers[0]
        return line_option(x, y, req.rows)
    if categories and numbers and 0 < len(req.rows) <= 8:
        return pie_option(categories[0], numbers[0], req.rows)
    if categories and numbers:
        return bar_option(categories[0], numbers[0], req.rows)
    return {"type": "table", "title": "查询结果", "option": {}}


def ensure_model(model: ModelConfig | None) -> None:
    if not model or not model.apiKey or not model.baseUrl or not model.modelName:
        raise HTTPException(status_code=400, detail="未配置可用模型，请在模型配置中填写 Base URL、API Key 和 Model")


async def call_table_selection(req: TableCandidateRequest) -> dict[str, Any]:
    assert req.model is not None
    table_names = [str(t.get("tableName", "")) for t in req.tables]
    prompt = (
        "你是 text2sql 的表名粗选器。只根据表名、表注释和字段数量选择候选表。\n"
        "不要编造表名，只能从给定 tableName 中选择。\n"
        f"最多返回 {max(1, min(req.maxTables, 20))} 张表。\n"
        "只返回 JSON，不要 Markdown。格式: "
        '{"tables":[{"tableName":"表名","reason":"选择原因"}],"assumptions":[]}\n'
        f"问题: {req.question}\n"
        f"SQL 方言: {req.dialect}\n"
        f"上次非法表名: {req.invalidTables}\n"
        f"可选表摘要: {req.tables}\n"
    )
    data = await call_openai_json(req.model, prompt, "Select relevant database tables.")
    return normalize_table_selection(data, req.maxTables)


async def call_table_refinement(req: RefineTablesRequest) -> dict[str, Any]:
    assert req.model is not None
    prompt = (
        "你是 text2sql 的字段感知表复选器。根据候选表的字段名、字段类型、字段注释判断最终需要哪些表。\n"
        "不要编造表名，只能从候选 metadata 的 tableName 中选择。\n"
        f"最多返回 {max(1, min(req.maxTables, 8))} 张表。\n"
        "只返回 JSON，不要 Markdown。格式: "
        '{"tables":[{"tableName":"表名","reason":"选择原因"}],"assumptions":[]}\n'
        f"问题: {req.question}\n"
        f"SQL 方言: {req.dialect}\n"
        f"上次非法表名: {req.invalidTables}\n"
        f"候选表字段: {req.candidateMetadata}\n"
    )
    data = await call_openai_json(req.model, prompt, "Refine relevant database tables from candidate metadata.")
    return normalize_table_selection(data, req.maxTables)


async def call_openai_compatible(req: GenerateRequest) -> dict[str, Any]:
    assert req.model is not None
    valid_tables = [str(t.get("tableName", "")) for t in req.metadata]
    output_contract = {
        "plan": {
            "intent": "用一句话概括用户问题",
            "tables": ["使用的表名"],
            "fields": ["使用的字段名或表达式"],
            "joins": ["JOIN 条件，没有则为空数组"],
            "filters": ["WHERE 条件，没有则为空数组"],
            "groupBy": ["GROUP BY 字段，没有则为空数组"],
            "orderBy": ["ORDER BY 字段或别名，没有则为空数组"],
            "limit": "是否需要 LIMIT，由 Java 最终追加也可写 null",
        },
        "sql": "一条 MySQL SELECT SQL",
        "explanation": "简短说明 SQL 如何回答问题",
        "assumptions": ["不确定字段含义或业务口径时写在这里"],
    }
    prompt = (
        "你是资深 MySQL 数据分析工程师，负责把自然语言问题转换为可执行、可审查、只读的 SQL。\n"
        "你必须先产出结构化计划，再产出最终 SQL；计划是简洁可审查摘要，不要输出隐藏推理过程。\n"
        "输出必须是 JSON，不要 Markdown，不要额外解释。\n"
        "\n"
        "硬性规则:\n"
        "1. 只生成一条 MySQL SELECT 查询，禁止多语句，禁止 INSERT/UPDATE/DELETE/DDL。\n"
        "2. 禁止 SELECT *，必须显式列出字段或聚合表达式。\n"
        "3. 只能使用候选元数据里的真实表名和字段名，不允许编造表或字段。\n"
        "4. 聚合查询中，非聚合字段必须出现在 GROUP BY 中。\n"
        "5. 统计金额、数量、均值、最大最小值时优先使用 SUM/COUNT/AVG/MAX/MIN 等聚合。\n"
        "6. 趋势、按月、按天、时间范围问题优先使用 date/time/timestamp 类型字段。\n"
        "7. ORDER BY 必须引用 SELECT 中的字段、别名或可解释聚合表达式。\n"
        "8. 不确定字段含义时，选择最接近字段，并写入 assumptions。\n"
        "9. 没有明确 LIMIT 时可以不写 LIMIT，Java 会统一追加。\n"
        "10. 如果有上次校验反馈，必须修正反馈中指出的问题。\n"
        "\n"
        f"问题: {req.question}\n"
        f"SQL 方言: {req.dialect}\n"
        f"可用表名: {valid_tables}\n"
        f"候选表元数据: {req.metadata}\n"
        f"业务术语: {req.glossary}\n"
        f"参考样例 SQL: {req.examples}\n"
        f"上次校验反馈: {req.feedback}\n"
        f"JSON 输出格式: {json.dumps(output_contract, ensure_ascii=False)}\n"
    )
    data = await call_openai_chat(
        req.model,
        messages=[
            {"role": "system", "content": "Return only valid JSON for a safe read-only MySQL SELECT query."},
            {"role": "user", "content": prompt},
        ],
        temperature=0.1,
    )
    content = data["choices"][0]["message"]["content"]
    return normalize_sql_generation(parse_generation_content(content), content)


async def call_openai_json(model: ModelConfig, prompt: str, system: str) -> dict[str, Any]:
    data = await call_openai_chat(
        model,
        messages=[
            {"role": "system", "content": system + " Return JSON only."},
            {"role": "user", "content": prompt},
        ],
        temperature=0.0,
    )
    content = data["choices"][0]["message"]["content"]
    return parse_json_content(content)


async def call_openai_chat(model: ModelConfig, messages: list[dict[str, str]], temperature: float) -> dict[str, Any]:
    url = model.baseUrl.rstrip("/") + "/chat/completions"
    headers = {"Authorization": f"Bearer {model.apiKey}"}
    payload = {
        "model": model.modelName,
        "messages": messages,
        "temperature": temperature,
    }
    async with httpx.AsyncClient(timeout=20) as client:
        res = await client.post(url, headers=headers, json=payload)
        res.raise_for_status()
        return res.json()


def parse_json_content(content: str) -> dict[str, Any]:
    text = content.strip()
    text = re.sub(r"^```(?:json)?", "", text).strip()
    text = re.sub(r"```$", "", text).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if not match:
            raise ValueError("模型未返回 JSON")
        return json.loads(match.group(0))


def normalize_table_selection(data: dict[str, Any], max_tables: int) -> dict[str, Any]:
    raw_tables = data.get("tables", [])
    tables: list[dict[str, str]] = []
    if isinstance(raw_tables, list):
        for item in raw_tables:
            if len(tables) >= max(1, max_tables):
                break
            if isinstance(item, dict):
                table_name = str(item.get("tableName", "")).strip()
                reason = str(item.get("reason", "")).strip()
            else:
                table_name = str(item).strip()
                reason = ""
            if table_name:
                tables.append({"tableName": table_name, "reason": reason})
    assumptions = data.get("assumptions", [])
    return {"tables": tables, "assumptions": assumptions if isinstance(assumptions, list) else []}


def parse_generation_content(content: str) -> dict[str, Any]:
    try:
        return parse_json_content(content)
    except Exception:
        return {"sql": extract_sql_content(content), "plan": {}, "assumptions": [], "explanation": ""}


def normalize_sql_generation(data: dict[str, Any], raw_content: str) -> dict[str, Any]:
    sql = str(data.get("sql") or "").strip()
    if not sql:
        sql = extract_sql_content(raw_content)
    plan = data.get("plan")
    assumptions = data.get("assumptions")
    explanation = data.get("explanation")
    return {
        "sql": strip_sql(sql),
        "plan": plan if isinstance(plan, dict) else {},
        "assumptions": assumptions if isinstance(assumptions, list) else [],
        "explanation": str(explanation or "由 OpenAI 兼容模型生成。"),
    }


def extract_sql_content(content: str) -> str:
    text = content.strip()
    sql_block = re.search(r"```sql\s*(.*?)```", text, re.IGNORECASE | re.DOTALL)
    if sql_block:
        return strip_sql(sql_block.group(1))
    generic_block = re.search(r"```\s*(.*?)```", text, re.DOTALL)
    if generic_block:
        text = generic_block.group(1).strip()
    select_match = re.search(r"\b(with|select)\b.*", text, re.IGNORECASE | re.DOTALL)
    if select_match:
        return strip_sql(select_match.group(0))
    return strip_sql(text)


def elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def sanitize(message: str) -> str:
    return re.sub(r"(?i)(api[_-]?key|authorization|bearer|password|token)=?\s*\S+", r"\1=***", message or "")


def heuristic_sql(question: str, metadata: list[dict[str, Any]]) -> str:
    q = question.lower()
    table = choose_table(q, metadata)
    columns = table.get("columns", []) if table else []
    table_name = table.get("tableName", "sales_orders") if table else "sales_orders"
    numeric = first_col(columns, ["amount", "price", "total", "revenue", "数量", "金额"], numeric=True)
    date = first_col(columns, ["date", "time", "created", "日期", "时间"], date=True)
    category = first_col(columns, ["region", "city", "category", "status", "customer", "地区", "分类", "状态"])

    if ("趋势" in question or "按月" in question or "month" in q) and date and numeric:
        return f"SELECT DATE_FORMAT({date}, '%Y-%m') AS month, SUM({numeric}) AS total_value FROM {table_name} GROUP BY month ORDER BY month"
    if ("多少" in question or "数量" in question or "count" in q) and category:
        return f"SELECT {category}, COUNT(*) AS total_count FROM {table_name} GROUP BY {category} ORDER BY total_count DESC"
    if ("销售" in question or "金额" in question or "收入" in question or "top" in q) and category and numeric:
        return f"SELECT {category}, SUM({numeric}) AS total_amount FROM {table_name} GROUP BY {category} ORDER BY total_amount DESC"
    selected = ", ".join([c.get("columnName") for c in columns[:6]]) if columns else "*"
    return f"SELECT {selected} FROM {table_name} ORDER BY 1 DESC"


def choose_table(question: str, metadata: list[dict[str, Any]]) -> dict[str, Any] | None:
    if not metadata:
        return None
    best = metadata[0]
    best_score = -1
    for table in metadata:
        haystack = (str(table.get("tableName", "")) + " " + str(table.get("tableComment", ""))).lower()
        haystack += " " + " ".join(str(c.get("columnName", "")) + " " + str(c.get("columnComment", "")) for c in table.get("columns", []))
        score = sum(1 for token in re.findall(r"[\w\u4e00-\u9fa5]+", question.lower()) if token and token in haystack)
        if score > best_score:
            best = table
            best_score = score
    return best


def first_col(columns: list[dict[str, Any]], hints: list[str], numeric: bool = False, date: bool = False) -> str | None:
    for col in columns:
        name = str(col.get("columnName", ""))
        typ = str(col.get("dataType", ""))
        if numeric and not is_numeric_type(typ):
            continue
        if date and not is_date_type(typ):
            continue
        text = (name + " " + str(col.get("columnComment", ""))).lower()
        if any(h.lower() in text for h in hints):
            return name
    for col in columns:
        typ = str(col.get("dataType", ""))
        if numeric and is_numeric_type(typ):
            return str(col.get("columnName"))
        if date and is_date_type(typ):
            return str(col.get("columnName"))
        if not numeric and not date and not is_numeric_type(typ) and not is_date_type(typ):
            return str(col.get("columnName"))
    return None


def bar_option(x: str, y: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "type": "bar",
        "title": f"按{x}统计{y}",
        "option": {
            "tooltip": {"trigger": "axis"},
            "xAxis": {"type": "category", "data": [r.get(x) for r in rows]},
            "yAxis": {"type": "value"},
            "series": [{"type": "bar", "data": [r.get(y) for r in rows], "name": y}],
        },
    }


def line_option(x: str, y: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    option = bar_option(x, y, rows)
    option["type"] = "line"
    option["title"] = f"{y}趋势"
    option["option"]["series"][0]["type"] = "line"
    return option


def pie_option(x: str, y: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "type": "pie",
        "title": f"{y}占比",
        "option": {
            "tooltip": {"trigger": "item"},
            "series": [{"type": "pie", "radius": "58%", "data": [{"name": r.get(x), "value": r.get(y)} for r in rows]}],
        },
    }


def strip_sql(sql: str) -> str:
    text = sql.strip()
    while text.endswith(";"):
        text = text[:-1].strip()
    return text


def is_numeric_type(type_name: str) -> bool:
    return any(t in type_name.lower() for t in ["int", "decimal", "number", "float", "double", "numeric"])


def is_date_type(type_name: str) -> bool:
    return any(t in type_name.lower() for t in ["date", "time", "timestamp"])


def estimate_tokens(text: str) -> int:
    return max(1, len(text) // 4)
