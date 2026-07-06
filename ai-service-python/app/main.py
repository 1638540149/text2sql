from __future__ import annotations

import re
import time
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


app = FastAPI(title="text2sql AI Service", version="0.1.0")


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
    if not req.model or not req.model.apiKey or not req.model.baseUrl or not req.model.modelName:
        raise HTTPException(status_code=400, detail="未配置可用模型，请在模型配置中填写 Base URL、API Key 和 Model")
    try:
        sql = await call_openai_compatible(req)
    except httpx.HTTPStatusError as exc:
        body = exc.response.text[:300] if exc.response is not None else ""
        raise HTTPException(status_code=502, detail=f"模型接口返回错误: HTTP {exc.response.status_code} {body}") from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"模型调用失败: {exc}") from exc

    return {
        "sql": sql,
        "explanation": "由 OpenAI 兼容模型生成。",
        "assumptions": ["仅使用授权后的元数据摘要。"],
        "promptTokens": estimate_tokens(req.question + str(req.metadata)),
        "completionTokens": estimate_tokens(sql),
        "durationMs": int((time.perf_counter() - started) * 1000),
        "modelCall": {"mode": "openai-compatible", "model": req.model.modelName},
    }


@app.post("/sql/validate")
def validate(req: ValidateRequest) -> dict[str, Any]:
    sql = strip_sql(req.sql)
    lower = sql.lower()
    if not lower.startswith("select "):
        return {"valid": False, "message": "只允许 SELECT SQL"}
    if ";" in sql or re.search(r"\b(insert|update|delete|drop|alter|truncate|create|grant|revoke)\b", lower):
        return {"valid": False, "message": "SQL 包含危险关键字或多语句"}

    known_tables = {str(t.get("tableName", "")).lower() for t in req.metadata}
    if known_tables:
        referenced = {m.group(1).lower() for m in re.finditer(r"\b(?:from|join)\s+`?([a-zA-Z_][\w]*)`?", lower)}
        unknown = sorted(t for t in referenced if t not in known_tables)
        if unknown:
            return {"valid": False, "message": f"引用了未知表: {', '.join(unknown)}"}
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


async def call_openai_compatible(req: GenerateRequest) -> str:
    assert req.model is not None
    url = req.model.baseUrl.rstrip("/") + "/chat/completions"
    prompt = (
        "你是 text2sql 助手。只返回一条 MySQL SELECT SQL，不要返回 Markdown。\n"
        f"问题: {req.question}\n"
        f"元数据摘要: {req.metadata}\n"
    )
    headers = {"Authorization": f"Bearer {req.model.apiKey}"}
    payload = {
        "model": req.model.modelName,
        "messages": [
            {"role": "system", "content": "Generate safe read-only MySQL SELECT SQL only."},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.1,
    }
    async with httpx.AsyncClient(timeout=20) as client:
        res = await client.post(url, headers=headers, json=payload)
        res.raise_for_status()
        data = res.json()
    content = data["choices"][0]["message"]["content"]
    return strip_sql(content.replace("```sql", "").replace("```", ""))


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
