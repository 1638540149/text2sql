package com.text2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.mapper.CoreMapper;
import com.text2sql.security.LoginUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
    private final CoreMapper mapper;
    private final JdbcDatasourceService jdbc;
    private final AiClient ai;
    private final ObjectMapper json;

    public QueryService(CoreMapper mapper, JdbcDatasourceService jdbc, AiClient ai, ObjectMapper json) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.ai = ai;
        this.json = json;
    }

    public Map<String, Object> run(QueryRequest request, LoginUser user) throws Exception {
        long start = System.nanoTime();
        Map<String, Object> ds = user.isAdmin()
            ? mapper.findDatasource(request.dataSourceId())
            : mapper.findAuthorizedDatasource(request.dataSourceId(), user.getId());
        if (ds == null) {
            throw new IllegalArgumentException("无权访问该数据源");
        }
        Map<String, Object> model = mapper.findModel(request.modelId());
        if (model == null) {
            throw new IllegalArgumentException("模型不存在或未启用");
        }

        int maxRows = intSetting("query.max_rows", 200);
        int timeoutSeconds = intSetting("query.timeout_seconds", 15);
        long warnRows = longSetting("query.explain_warn_rows", 10000L);
        List<Map<String, Object>> metadata = jdbc.metadataSummary(request.dataSourceId(), request.selectedTables(), request.question());
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(step("发起请求", "success", "收到自然语言问题"));
        trace.add(step("元数据摘要", "success", "已选择 " + metadata.size() + " 张候选表"));

        String generatedSql = request.editedSql();
        Map<String, Object> generation = Map.of();
        if (generatedSql == null || generatedSql.isBlank()) {
            try {
                generation = ai.generate(request.question(), "MYSQL", metadata, model);
                generatedSql = String.valueOf(generation.getOrDefault("sql", ""));
                trace.add(step("转化SQL语句", "success", "模型生成 SQL"));
            } catch (Exception e) {
                trace.add(step("转化SQL语句", "error", "模型调用失败：" + e.getMessage()));
                Long historyId = saveHistory(user, request, "", "", "FAILED", "LLM_FAILED",
                    start, generation, 0, null, trace, model);
                return Map.of("success", false, "historyId", historyId, "failureReason", "LLM_FAILED",
                    "message", "模型调用失败，请检查 Python AI 服务或模型配置", "trace", trace);
            }
        } else {
            trace.add(step("转化SQL语句", "success", "使用用户编辑后的 SQL"));
        }

        String finalSql = generatedSql;
        try {
            SqlSafety.requireSelectOnly(finalSql);
            Map<String, Object> validation = ai.validate(finalSql, metadata);
            if (Boolean.FALSE.equals(validation.get("valid")) && validation.get("repairedSql") != null) {
                finalSql = String.valueOf(validation.get("repairedSql"));
                SqlSafety.requireSelectOnly(finalSql);
                trace.add(step("SQL校验", "warning", "AI 自动修复一次"));
            } else if (Boolean.FALSE.equals(validation.get("valid"))) {
                throw new IllegalArgumentException(String.valueOf(validation.getOrDefault("message", "SQL 校验失败")));
            } else {
                trace.add(step("SQL校验", "success", "语法、只读和表字段校验通过"));
            }
            finalSql = SqlSafety.ensureLimit(finalSql, maxRows);
        } catch (Exception e) {
            trace.add(step("SQL校验", "error", e.getMessage()));
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", "SQL_VALIDATION_FAILED",
                start, generation, 0, null, trace, model);
            return Map.of("success", false, "historyId", historyId, "failureReason", "SQL_VALIDATION_FAILED", "message", e.getMessage(), "trace", trace);
        }

        Map<String, Object> explain;
        try {
            explain = jdbc.explain(ds, finalSql, warnRows, timeoutSeconds);
            trace.add(step("EXPLAIN检查", Boolean.TRUE.equals(explain.get("highRisk")) ? "warning" : "success", String.valueOf(explain.get("message"))));
        } catch (Exception e) {
            trace.add(step("EXPLAIN检查", "error", e.getMessage()));
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", "EXECUTION_FAILED",
                start, generation, 0, null, trace, model);
            return Map.of("success", false, "historyId", historyId, "failureReason", "EXECUTION_FAILED", "message", e.getMessage(), "trace", trace);
        }

        if (Boolean.TRUE.equals(explain.get("highRisk")) && !request.confirmHighRisk()) {
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "NEEDS_CONFIRMATION", "EXPLAIN_HIGH_RISK",
                start, generation, 0, null, trace, model);
            return Map.of("success", false, "needsConfirmation", true, "historyId", historyId,
                "sql", finalSql, "explain", explain, "trace", trace);
        }

        try {
            Map<String, Object> result = jdbc.executeSelect(ds, finalSql, maxRows, timeoutSeconds);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
            Map<String, Object> chart = ai.recommendChart(request.question(), fields, rows);
            trace.add(step("查询结果", "success", "返回 " + rows.size() + " 行结果"));
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "SUCCESS", null,
                start, generation, rows.size(), chart, trace, model);
            return Map.of("success", true, "historyId", historyId, "sql", finalSql, "fields", fields,
                "rows", rows, "chart", chart, "trace", trace, "explain", explain);
        } catch (Exception e) {
            trace.add(step("查询结果", "error", e.getMessage()));
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", "EXECUTION_FAILED",
                start, generation, 0, null, trace, model);
            return Map.of("success", false, "historyId", historyId, "failureReason", "EXECUTION_FAILED", "message", e.getMessage(), "trace", trace);
        }
    }

    public Map<String, Object> analytics(Long modelId, Long dataSourceId, String from, String to) {
        List<Map<String, Object>> rows = mapper.analyticsRows(modelId, dataSourceId, from, to);
        Map<String, Stats> byModel = new LinkedHashMap<>();
        Map<String, Long> failures = new LinkedHashMap<>();
        Map<String, Map<String, Object>> daily = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String modelName = String.valueOf(row.getOrDefault("modelName", "未知模型"));
            Stats stats = byModel.computeIfAbsent(modelName, Stats::new);
            stats.add(row);
            String failure = row.get("failureReason") == null ? "SUCCESS" : String.valueOf(row.get("failureReason"));
            failures.put(failure, failures.getOrDefault(failure, 0L) + 1);
            String day = String.valueOf(row.get("createdAt")).substring(0, 10);
            Map<String, Object> d = daily.computeIfAbsent(day, k -> new LinkedHashMap<>(Map.of("date", k, "total", 0L, "success", 0L)));
            d.put("total", ((Number) d.get("total")).longValue() + 1);
            if ("SUCCESS".equals(row.get("status"))) {
                d.put("success", ((Number) d.get("success")).longValue() + 1);
            }
        }
        List<Map<String, Object>> models = byModel.values().stream()
            .map(Stats::toMap)
            .sorted(Comparator.comparing(m -> String.valueOf(m.get("modelName"))))
            .collect(Collectors.toList());
        long total = rows.size();
        long success = rows.stream().filter(row -> "SUCCESS".equals(row.get("status"))).count();
        return Map.of(
            "summary", Map.of("total", total, "success", success, "successRate", total == 0 ? 0 : success * 1.0 / total),
            "models", models,
            "daily", daily.values(),
            "failures", failures.entrySet().stream().map(e -> Map.of("name", e.getKey(), "value", e.getValue())).toList()
        );
    }

    private Long saveHistory(LoginUser user, QueryRequest request, String generatedSql, String finalSql, String status,
                             String failureReason, long start, Map<String, Object> generation, int rowCount,
                             Map<String, Object> chart, List<Map<String, Object>> trace, Map<String, Object> model) throws Exception {
        int promptTokens = intValue(generation.get("promptTokens"), Math.max(1, request.question().length() / 4));
        int completionTokens = intValue(generation.get("completionTokens"), Math.max(1, generatedSql.length() / 4));
        BigDecimal cost = cost(promptTokens, completionTokens, model);
        mapper.insertHistory(user.getId(), request.dataSourceId(), request.modelId(), request.question(), generatedSql, finalSql,
            status, failureReason, Duration.ofNanos(System.nanoTime() - start).toMillis(), promptTokens, completionTokens,
            cost, rowCount, chart == null ? null : json.writeValueAsString(chart), json.writeValueAsString(trace));
        return mapper.lastInsertId();
    }

    private BigDecimal cost(int promptTokens, int completionTokens, Map<String, Object> model) {
        BigDecimal promptPrice = decimal(model.get("promptPricePer1k"));
        BigDecimal completionPrice = decimal(model.get("completionPricePer1k"));
        return promptPrice.multiply(BigDecimal.valueOf(promptTokens)).add(completionPrice.multiply(BigDecimal.valueOf(completionTokens)))
            .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private int intSetting(String key, int fallback) {
        try { return Integer.parseInt(mapper.getSetting(key)); } catch (Exception e) { return fallback; }
    }

    private long longSetting(String key, long fallback) {
        try { return Long.parseLong(mapper.getSetting(key)); } catch (Exception e) { return fallback; }
    }

    private int intValue(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private Map<String, Object> step(String name, String status, String message) {
        return Map.of("name", name, "status", status, "message", message);
    }

    private static class Stats {
        private final String modelName;
        private long total;
        private long success;
        private long durationSum;
        private long tokens;
        private BigDecimal cost = BigDecimal.ZERO;
        private long rows;
        private long feedbackCount;
        private long feedbackSum;
        private final List<Long> durations = new ArrayList<>();

        private Stats(String modelName) { this.modelName = modelName; }

        private void add(Map<String, Object> row) {
            total++;
            if ("SUCCESS".equals(row.get("status"))) {
                success++;
            }
            long duration = row.get("durationMs") instanceof Number n ? n.longValue() : 0;
            durationSum += duration;
            durations.add(duration);
            tokens += (row.get("promptTokens") instanceof Number p ? p.longValue() : 0)
                + (row.get("completionTokens") instanceof Number c ? c.longValue() : 0);
            if (row.get("costEstimate") != null) {
                cost = cost.add(new BigDecimal(String.valueOf(row.get("costEstimate"))));
            }
            rows += row.get("resultRowCount") instanceof Number r ? r.longValue() : 0;
            if (row.get("feedbackScore") instanceof Number s) {
                feedbackCount++;
                feedbackSum += s.longValue();
            }
        }

        private Map<String, Object> toMap() {
            durations.sort(Long::compareTo);
            long p95 = durations.isEmpty() ? 0 : durations.get(Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * 0.95) - 1));
            return Map.of(
                "modelName", modelName,
                "total", total,
                "successRate", total == 0 ? 0 : success * 1.0 / total,
                "failureRate", total == 0 ? 0 : (total - success) * 1.0 / total,
                "avgDurationMs", total == 0 ? 0 : durationSum / total,
                "p95DurationMs", p95,
                "tokens", tokens,
                "cost", cost,
                "avgRows", total == 0 ? 0 : rows * 1.0 / total,
                "avgFeedback", feedbackCount == 0 ? 0 : feedbackSum * 1.0 / feedbackCount
            );
        }
    }

    public record QueryRequest(Long dataSourceId, Long modelId, String question, List<String> selectedTables,
                               String editedSql, boolean confirmHighRisk) {}
}
