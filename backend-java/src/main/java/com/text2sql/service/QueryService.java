package com.text2sql.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2sql.mapper.CoreMapper;
import com.text2sql.security.LoginUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final int MAX_MODEL_SELECTED_TABLES = 8;
    private static final int MAX_COARSE_SELECTED_TABLES = 20;
    private static final int MAX_USER_SELECTED_TABLES = 20;

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
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> generation = Map.of();
        String generatedSql = "";
        String finalSql = "";
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
        log.info("event=query_run_start userId={} dataSourceId={} modelId={} questionLength={} selectedTableCount={} editedSql={}",
            user.getId(), request.dataSourceId(), request.modelId(), safeLength(request.question()),
            request.selectedTables() == null ? 0 : request.selectedTables().size(),
            request.editedSql() != null && !request.editedSql().isBlank());

        int maxRows = intSetting("query.max_rows", 200);
        int timeoutSeconds = intSetting("query.timeout_seconds", 15);
        long warnRows = longSetting("query.explain_warn_rows", 10000L);
        trace.add(step("发起请求", "success", "收到自然语言问题"));

        Set<String> knownTables = jdbc.knownTableNames(request.dataSourceId());
        List<String> candidateTables = List.of();
        List<Map<String, Object>> metadata = List.of();
        if (knownTables.isEmpty()) {
            String message = "当前数据源未同步元数据，请先刷新元数据";
            trace.add(step("元数据检查", "error", message));
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", "METADATA_NOT_READY",
                start, generation, 0, null, trace, model);
            log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason=METADATA_NOT_READY durationMs={}",
                user.getId(), request.dataSourceId(), Duration.ofNanos(System.nanoTime() - start).toMillis());
            return Map.of("success", false, "historyId", historyId, "failureReason", "METADATA_NOT_READY",
                "message", message, "trace", trace);
        }

        generatedSql = request.editedSql();
        boolean allowModelRepair = generatedSql == null || generatedSql.isBlank();
        if (allowModelRepair) {
            String modelPhase = "TABLE_SELECTION";
            try {
                candidateTables = chooseTables(request, model, knownTables, trace);
                metadata = jdbc.metadataForTables(request.dataSourceId(), candidateTables);
                trace.add(step("候选表元数据", "success", "已加载 " + metadata.size() + " 张最终候选表字段"));
                modelPhase = "SQL_GENERATION";
                GeneratedSql generated = generateSql(request.question(), metadata, model, "");
                generation = generated.generation();
                generatedSql = generated.sql();
                trace.add(step("转化SQL语句", "success", "模型生成 SQL"));
            } catch (ValidationFailure e) {
                trace.add(step("SQL校验", "error", sanitize(e.getMessage())));
                Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", e.failureReason(),
                    start, generation, 0, null, trace, model);
                log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason={} durationMs={} reason={}",
                    user.getId(), request.dataSourceId(), e.failureReason(), Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(e.getMessage()));
                return Map.of("success", false, "historyId", historyId, "failureReason", e.failureReason(),
                    "message", e.getMessage(), "trace", trace);
            } catch (Exception e) {
                String failureReason = "TABLE_SELECTION".equals(modelPhase) ? "TABLE_SELECTION_FAILED" : "LLM_FAILED";
                trace.add(step("转化SQL语句", "error", sanitize(e.getMessage())));
                Long historyId = saveHistory(user, request, "", "", "FAILED", failureReason,
                    start, generation, 0, null, trace, model);
                log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason={} durationMs={} reason={}",
                    user.getId(), request.dataSourceId(), failureReason, Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(e.getMessage()));
                String message = "TABLE_SELECTION_FAILED".equals(failureReason)
                    ? "模型选表失败：" + e.getMessage()
                    : "模型调用失败，请检查 Python AI 服务或模型配置";
                return Map.of("success", false, "historyId", historyId, "failureReason", failureReason,
                    "message", message, "trace", trace);
            }
        } else {
            candidateTables = validateUserSelectedTables(request.selectedTables(), knownTables);
            if (candidateTables.isEmpty()) {
                candidateTables = new ArrayList<>(knownTables);
            }
            metadata = jdbc.metadataForTables(request.dataSourceId(), candidateTables);
            trace.add(step("转化SQL语句", "success", "使用用户编辑后的 SQL"));
        }

        finalSql = generatedSql;
        boolean repairAvailable = allowModelRepair;
        try {
            finalSql = validateSqlForExecution(finalSql, metadata, knownTables, new LinkedHashSet<>(candidateTables), maxRows, trace);
        } catch (Exception e) {
            if (repairAvailable) {
                repairAvailable = false;
                try {
                    GeneratedSql repaired = repairSql(request.question(), metadata, model, "SQL 校验失败: " + sanitize(e.getMessage()), trace);
                    generation = repaired.generation();
                    finalSql = repaired.sql();
                    finalSql = validateSqlForExecution(finalSql, metadata, knownTables, new LinkedHashSet<>(candidateTables), maxRows, trace);
                    trace.add(step("SQL自动修复", "success", "模型根据校验反馈修复 SQL"));
                } catch (Exception repairedError) {
                    String failureReason = failureReason(repairedError);
                    trace.add(step("SQL校验", "error", sanitize(repairedError.getMessage())));
                    Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", failureReason,
                        start, generation, 0, null, trace, model);
                    log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason={} durationMs={} reason={}",
                        user.getId(), request.dataSourceId(), failureReason, Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(repairedError.getMessage()));
                    return Map.of("success", false, "historyId", historyId, "failureReason", failureReason, "message", repairedError.getMessage(), "trace", trace);
                }
            } else {
                String failureReason = failureReason(e);
                trace.add(step("SQL校验", "error", sanitize(e.getMessage())));
                Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", failureReason,
                    start, generation, 0, null, trace, model);
                log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason={} durationMs={} reason={}",
                    user.getId(), request.dataSourceId(), failureReason, Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(e.getMessage()));
                return Map.of("success", false, "historyId", historyId, "failureReason", failureReason, "message", e.getMessage(), "trace", trace);
            }
        }

        Map<String, Object> explain;
        try {
            explain = jdbc.explain(ds, finalSql, warnRows, timeoutSeconds);
            trace.add(step("EXPLAIN检查", Boolean.TRUE.equals(explain.get("highRisk")) ? "warning" : "success", String.valueOf(explain.get("message"))));
        } catch (Exception e) {
            if (repairAvailable) {
                repairAvailable = false;
                try {
                    GeneratedSql repaired = repairSql(request.question(), metadata, model, "EXPLAIN 失败: " + sanitize(e.getMessage()), trace);
                    generation = repaired.generation();
                    finalSql = repaired.sql();
                    finalSql = validateSqlForExecution(finalSql, metadata, knownTables, new LinkedHashSet<>(candidateTables), maxRows, trace);
                    explain = jdbc.explain(ds, finalSql, warnRows, timeoutSeconds);
                    trace.add(step("SQL自动修复", "success", "模型根据 EXPLAIN 错误修复 SQL"));
                    trace.add(step("EXPLAIN检查", Boolean.TRUE.equals(explain.get("highRisk")) ? "warning" : "success", String.valueOf(explain.get("message"))));
                } catch (Exception repairedError) {
                    String repairedFailureReason = repairedError instanceof ValidationFailure ? failureReason(repairedError) : "EXECUTION_FAILED";
                    trace.add(step("EXPLAIN检查", "error", sanitize(repairedError.getMessage())));
                    Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", repairedFailureReason,
                        start, generation, 0, null, trace, model);
                    log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason={} durationMs={} reason={}",
                        user.getId(), request.dataSourceId(), repairedFailureReason, Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(repairedError.getMessage()));
                    return Map.of("success", false, "historyId", historyId, "failureReason", repairedFailureReason, "message", repairedError.getMessage(), "trace", trace);
                }
            } else {
                trace.add(step("EXPLAIN检查", "error", sanitize(e.getMessage())));
                Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", "EXECUTION_FAILED",
                    start, generation, 0, null, trace, model);
                log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason=EXECUTION_FAILED durationMs={} reason={}",
                    user.getId(), request.dataSourceId(), Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(e.getMessage()));
                return Map.of("success", false, "historyId", historyId, "failureReason", "EXECUTION_FAILED", "message", e.getMessage(), "trace", trace);
            }
        }

        if (Boolean.TRUE.equals(explain.get("highRisk")) && !request.confirmHighRisk()) {
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "NEEDS_CONFIRMATION", "EXPLAIN_HIGH_RISK",
                start, generation, 0, null, trace, model);
            log.info("event=query_run_end userId={} dataSourceId={} status=NEEDS_CONFIRMATION failureReason=EXPLAIN_HIGH_RISK durationMs={}",
                user.getId(), request.dataSourceId(), Duration.ofNanos(System.nanoTime() - start).toMillis());
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
            log.info("event=query_run_end userId={} dataSourceId={} status=SUCCESS rowCount={} durationMs={}",
                user.getId(), request.dataSourceId(), rows.size(), Duration.ofNanos(System.nanoTime() - start).toMillis());
            return Map.of("success", true, "historyId", historyId, "sql", finalSql, "fields", fields,
                "rows", rows, "chart", chart, "trace", trace, "explain", explain);
        } catch (Exception e) {
            trace.add(step("查询结果", "error", sanitize(e.getMessage())));
            Long historyId = saveHistory(user, request, generatedSql, finalSql, "FAILED", "EXECUTION_FAILED",
                start, generation, 0, null, trace, model);
            log.warn("event=query_run_end userId={} dataSourceId={} status=FAILED failureReason=EXECUTION_FAILED durationMs={} reason={}",
                user.getId(), request.dataSourceId(), Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(e.getMessage()));
            return Map.of("success", false, "historyId", historyId, "failureReason", "EXECUTION_FAILED", "message", e.getMessage(), "trace", trace);
        }
    }

    private List<String> chooseTables(QueryRequest request, Map<String, Object> model, Set<String> knownTables,
                                      List<Map<String, Object>> trace) {
        List<String> manualTables = validateUserSelectedTables(request.selectedTables(), knownTables);
        if (!manualTables.isEmpty()) {
            trace.add(step("表范围", "success", "用户选择 " + manualTables.size() + " 张候选表"));
            log.info("event=user_table_scope dataSourceId={} tableCount={}", request.dataSourceId(), manualTables.size());
            return manualTables;
        }

        List<Map<String, Object>> tableSummaries = jdbc.tableSummaries(request.dataSourceId());
        trace.add(step("粗选表名", "success", "发送 " + tableSummaries.size() + " 张表摘要"));
        List<String> coarseTables = selectTableCandidatesWithRetry(request.question(), tableSummaries, model, knownTables);
        trace.add(step("粗选表名", "success", "模型粗选 " + coarseTables.size() + " 张表"));

        List<Map<String, Object>> coarseMetadata = jdbc.metadataForTables(request.dataSourceId(), coarseTables);
        List<String> finalTables = refineTablesWithRetry(request.question(), coarseMetadata, model, new LinkedHashSet<>(coarseTables));
        trace.add(step("字段感知复选", "success", "模型复选 " + finalTables.size() + " 张最终候选表"));
        return finalTables;
    }

    private List<String> selectTableCandidatesWithRetry(String question, List<Map<String, Object>> tableSummaries,
                                                        Map<String, Object> model, Set<String> knownTables) {
        List<String> invalidTables = List.of();
        for (int attempt = 1; attempt <= 2; attempt++) {
            Map<String, Object> result = ai.selectTableCandidates(question, "MYSQL", tableSummaries, model, invalidTables);
            CandidateValidation validation = validateCandidateTables(extractTableNames(result), knownTables, MAX_COARSE_SELECTED_TABLES);
            log.info("event=coarse_table_selection attempt={} candidateCount={} invalidCount={}",
                attempt, validation.validTables().size(), validation.invalidTables().size());
            if (validation.invalidTables().isEmpty() && !validation.validTables().isEmpty()) {
                return validation.validTables();
            }
            if (validation.invalidTables().isEmpty()) {
                throw new IllegalArgumentException("模型未选出候选表");
            }
            invalidTables = validation.invalidTables();
        }
        throw new IllegalArgumentException("模型返回不存在的表名: " + String.join(", ", invalidTables));
    }

    private List<String> refineTablesWithRetry(String question, List<Map<String, Object>> candidateMetadata,
                                               Map<String, Object> model, Set<String> knownTables) {
        List<String> invalidTables = List.of();
        for (int attempt = 1; attempt <= 2; attempt++) {
            Map<String, Object> result = ai.refineTables(question, "MYSQL", candidateMetadata, model, invalidTables);
            CandidateValidation validation = validateCandidateTables(extractTableNames(result), knownTables, MAX_MODEL_SELECTED_TABLES);
            log.info("event=field_aware_table_selection attempt={} candidateCount={} invalidCount={}",
                attempt, validation.validTables().size(), validation.invalidTables().size());
            if (validation.invalidTables().isEmpty() && !validation.validTables().isEmpty()) {
                return validation.validTables();
            }
            if (validation.invalidTables().isEmpty()) {
                throw new IllegalArgumentException("模型未选出最终候选表");
            }
            invalidTables = validation.invalidTables();
        }
        throw new IllegalArgumentException("模型返回不存在的表名: " + String.join(", ", invalidTables));
    }

    private GeneratedSql generateSql(String question, List<Map<String, Object>> metadata,
                                     Map<String, Object> model, String feedback) {
        Map<String, Object> generation = ai.generate(question, "MYSQL", metadata, model, feedback);
        return new GeneratedSql(String.valueOf(generation.getOrDefault("sql", "")), generation);
    }

    private String validateSqlForExecution(String sql, List<Map<String, Object>> metadata, Set<String> knownTables,
                                           Set<String> allowedTables, int maxRows, List<Map<String, Object>> trace) {
        parseAndValidateSql(sql, knownTables, allowedTables);
        trace.add(step("SQL parser校验", "success", "SQL 可解析且为单条 SELECT"));
        Map<String, Object> validation = ai.validate(sql, metadata);
        String finalSql = sql;
        if (Boolean.FALSE.equals(validation.get("valid")) && validation.get("repairedSql") != null) {
            finalSql = String.valueOf(validation.get("repairedSql"));
            parseAndValidateSql(finalSql, knownTables, tableNames(metadata));
            trace.add(step("SQL校验", "warning", "AI validate 返回修复 SQL"));
        } else if (Boolean.FALSE.equals(validation.get("valid"))) {
            throw new ValidationFailure("SQL_VALIDATION_FAILED", String.valueOf(validation.getOrDefault("message", "SQL 校验失败")));
        } else {
            trace.add(step("SQL校验", "success", "语法、只读和表字段校验通过"));
        }
        finalSql = SqlSafety.ensureLimit(finalSql, maxRows);
        parseAndValidateSql(finalSql, knownTables, tableNames(metadata));
        return finalSql;
    }

    private GeneratedSql repairSql(String question, List<Map<String, Object>> metadata, Map<String, Object> model,
                                   String feedback, List<Map<String, Object>> trace) {
        String sanitizedFeedback = sanitize(feedback);
        trace.add(step("SQL自动修复", "warning", "模型将根据错误反馈重新生成 SQL"));
        log.info("event=sql_repair_attempt tableCount={} feedbackLength={}", metadata.size(), sanitizedFeedback.length());
        GeneratedSql repaired = generateSql(question, metadata, model, sanitizedFeedback);
        log.info("event=sql_repair_generated sqlLength={}", repaired.sql().length());
        return repaired;
    }

    private SqlSafety.ParsedSql parseAndValidateSql(String sql, Set<String> knownTables, Set<String> allowedTables) {
        SqlSafety.ParsedSql parsed;
        try {
            parsed = SqlSafety.validateSelect(sql);
        } catch (IllegalArgumentException e) {
            String reason = e.getMessage() != null && e.getMessage().startsWith("SQL parser") ? "SQL_PARSER_FAILED" : "SQL_VALIDATION_FAILED";
            throw new ValidationFailure(reason, e.getMessage());
        }
        Set<String> knownLower = lowerCaseSet(knownTables);
        Set<String> allowedLower = allowedTables == null || allowedTables.isEmpty() ? knownLower : lowerCaseSet(allowedTables);
        List<String> unknown = new ArrayList<>();
        List<String> outsideCandidate = new ArrayList<>();
        for (String table : parsed.referencedTables()) {
            String normalized = table.toLowerCase(Locale.ROOT);
            if (!knownLower.contains(normalized)) {
                unknown.add(table);
            } else if (!allowedLower.contains(normalized)) {
                outsideCandidate.add(table);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ValidationFailure("UNKNOWN_TABLE", "SQL 引用了不存在的表: " + String.join(", ", unknown));
        }
        if (!outsideCandidate.isEmpty()) {
            throw new ValidationFailure("SQL_VALIDATION_FAILED", "SQL 引用了未入选候选范围的表: " + String.join(", ", outsideCandidate));
        }
        log.info("event=sql_parser_validation tableReferenceCount={} success=true", parsed.referencedTables().size());
        return parsed;
    }

    private List<String> validateUserSelectedTables(List<String> selectedTables, Set<String> knownTables) {
        if (selectedTables == null || selectedTables.isEmpty()) {
            return List.of();
        }
        if (selectedTables.size() > MAX_USER_SELECTED_TABLES) {
            throw new IllegalArgumentException("手动选择表不能超过 " + MAX_USER_SELECTED_TABLES + " 张");
        }
        CandidateValidation validation = validateCandidateTables(selectedTables, knownTables, MAX_USER_SELECTED_TABLES);
        if (!validation.invalidTables().isEmpty()) {
            throw new IllegalArgumentException("选择了不存在的表: " + String.join(", ", validation.invalidTables()));
        }
        return validation.validTables();
    }

    private CandidateValidation validateCandidateTables(List<String> tableNames, Set<String> knownTables, int maxTables) {
        Map<String, String> canonical = canonicalTableMap(knownTables);
        List<String> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String tableName : tableNames) {
            if (tableName == null || tableName.isBlank()) {
                continue;
            }
            String key = tableName.trim().toLowerCase(Locale.ROOT);
            String canonicalName = canonical.get(key);
            if (canonicalName == null) {
                invalid.add(tableName.trim());
                continue;
            }
            if (seen.add(canonicalName) && valid.size() < maxTables) {
                valid.add(canonicalName);
            }
        }
        return new CandidateValidation(valid, invalid);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTableNames(Map<String, Object> result) {
        Object tables = result.get("tables");
        if (!(tables instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object tableName = map.get("tableName");
                if (tableName != null) {
                    names.add(String.valueOf(tableName));
                }
            } else if (item != null) {
                names.add(String.valueOf(item));
            }
        }
        return names;
    }

    private List<String> canonicalize(List<String> tableNames, Set<String> knownTables) {
        return validateCandidateTables(tableNames, knownTables, Integer.MAX_VALUE).validTables();
    }

    private Set<String> tableNames(List<Map<String, Object>> metadata) {
        return metadata.stream()
            .map(item -> String.valueOf(item.get("tableName")))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, String> canonicalTableMap(Set<String> knownTables) {
        Map<String, String> canonical = new LinkedHashMap<>();
        for (String tableName : knownTables) {
            canonical.put(tableName.toLowerCase(Locale.ROOT), tableName);
        }
        return canonical;
    }

    private Set<String> lowerCaseSet(Set<String> tableNames) {
        return tableNames.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private String failureReason(Exception e) {
        if (e instanceof ValidationFailure validationFailure) {
            return validationFailure.failureReason();
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith("SQL parser")) {
            return "SQL_PARSER_FAILED";
        }
        return "SQL_VALIDATION_FAILED";
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

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)(api[_-]?key|authorization|bearer|password|passwordCipher|token)=?\\s*\\S+", "$1=***");
    }

    private record CandidateValidation(List<String> validTables, List<String> invalidTables) {}

    private record GeneratedSql(String sql, Map<String, Object> generation) {}

    private static class ValidationFailure extends RuntimeException {
        private final String failureReason;

        private ValidationFailure(String failureReason, String message) {
            super(message);
            this.failureReason = failureReason;
        }

        private String failureReason() {
            return failureReason;
        }
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
