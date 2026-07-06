package com.text2sql.service;

import java.util.Locale;

public final class SqlSafety {
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
        String normalized = normalize(sql);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select ")) {
            throw new IllegalArgumentException("只允许执行 SELECT 查询");
        }
        if (lower.contains(";") || lower.matches(".*\\b(insert|update|delete|drop|alter|truncate|create|replace|grant|revoke)\\b.*")) {
            throw new IllegalArgumentException("SQL 包含非只读或多语句风险");
        }
    }

    public static String ensureLimit(String sql, int limit) {
        String normalized = normalize(sql);
        if (normalized.toLowerCase(Locale.ROOT).matches("(?s).*\\blimit\\s+\\d+.*")) {
            return normalized;
        }
        return normalized + " LIMIT " + limit;
    }
}
