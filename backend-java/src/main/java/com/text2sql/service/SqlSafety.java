package com.text2sql.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;

public final class SqlSafety {
    private static final Pattern BLOCKED_KEYWORDS = Pattern.compile(
        ".*\\b(insert|update|delete|drop|alter|truncate|create|replace|grant|revoke)\\b.*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
        "\\b(?:from|join)\\s+(?!\\()(?:(?:`?([a-zA-Z_][\\w]*)`?)\\.)?`?([a-zA-Z_][\\w]*)`?",
        Pattern.CASE_INSENSITIVE
    );

    private SqlSafety() {}

    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    public static void requireSelectOnly(String sql) {
        validateSelect(sql);
    }

    public static ParsedSql validateSelect(String sql) {
        String normalized = normalize(sql);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains(";") || BLOCKED_KEYWORDS.matcher(lower).matches()) {
            throw new IllegalArgumentException("SQL 包含非只读或多语句风险");
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(normalized);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("SQL parser 解析失败: " + e.getMessage(), e);
        }
        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException("只允许执行 SELECT 查询");
        }
        if (!lower.startsWith("select") && !lower.startsWith("with ")) {
            throw new IllegalArgumentException("只允许执行 SELECT 查询");
        }
        return new ParsedSql(normalized, referencedTables(normalized));
    }

    public static String ensureLimit(String sql, int limit) {
        String normalized = normalize(sql);
        if (normalized.toLowerCase(Locale.ROOT).matches("(?s).*\\blimit\\s+\\d+.*")) {
            return normalized;
        }
        return normalized + " LIMIT " + limit;
    }

    private static List<String> referencedTables(String sql) {
        Matcher matcher = TABLE_REFERENCE.matcher(sql);
        List<String> tables = new ArrayList<>();
        while (matcher.find()) {
            String table = matcher.group(2);
            if (table != null && !table.isBlank()) {
                tables.add(table);
            }
        }
        return tables;
    }

    public record ParsedSql(String sql, List<String> referencedTables) {}
}
