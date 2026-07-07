package com.text2sql.service;

import com.text2sql.config.AppProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AiClient {
    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    private final AppProperties properties;
    private final RestTemplate restTemplate;
    private final CryptoService crypto;

    public AiClient(AppProperties properties, RestTemplate restTemplate, CryptoService crypto) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.crypto = crypto;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(String question, String dialect, List<Map<String, Object>> metadata, Map<String, Object> model) {
        return generate(question, dialect, metadata, model, "");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(String question, String dialect, List<Map<String, Object>> metadata,
                                        Map<String, Object> model, String feedback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("dialect", dialect);
        body.put("metadata", metadata);
        body.put("model", safeModel(model));
        body.put("feedback", feedback == null ? "" : feedback);
        body.put("glossary", List.of());
        body.put("examples", List.of());
        return post("/nl2sql/generate", body, model);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> selectTableCandidates(String question, String dialect, List<Map<String, Object>> tables,
                                                     Map<String, Object> model, List<String> invalidTables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("dialect", dialect);
        body.put("tables", tables);
        body.put("model", safeModel(model));
        body.put("invalidTables", invalidTables == null ? List.of() : invalidTables);
        body.put("maxTables", 20);
        return post("/nl2sql/select-table-candidates", body, model);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> refineTables(String question, String dialect, List<Map<String, Object>> candidateMetadata,
                                            Map<String, Object> model, List<String> invalidTables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("dialect", dialect);
        body.put("candidateMetadata", candidateMetadata);
        body.put("model", safeModel(model));
        body.put("invalidTables", invalidTables == null ? List.of() : invalidTables);
        body.put("maxTables", 8);
        return post("/nl2sql/refine-tables", body, model);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(String sql, List<Map<String, Object>> metadata) {
        Map<String, Object> body = Map.of("sql", sql, "metadata", metadata);
        return post("/sql/validate", body, Map.of("modelName", "validator"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> recommendChart(String question, List<Map<String, Object>> fields, List<Map<String, Object>> rows) {
        Map<String, Object> body = Map.of("question", question, "fields", fields, "rows", rows);
        return post("/chart/recommend", body, Map.of("modelName", "chart"));
    }

    private Map<String, Object> safeModel(Map<String, Object> model) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("name", model.get("name"));
        safe.put("baseUrl", model.get("baseUrl"));
        safe.put("modelName", model.get("modelName"));
        String cipher = String.valueOf(model.getOrDefault("apiKeyCipher", ""));
        safe.put("apiKey", cipher.isBlank() ? "" : crypto.decrypt(cipher));
        return safe;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, Map<String, Object> model) {
        long start = System.nanoTime();
        String modelName = String.valueOf(model.getOrDefault("modelName", model.getOrDefault("name", "unknown")));
        try {
            Map<String, Object> result = restTemplate.postForObject(properties.getAiServiceUrl() + path, body, Map.class);
            log.info("event=ai_service_call path={} modelName={} success=true durationMs={}",
                path, modelName, Duration.ofNanos(System.nanoTime() - start).toMillis());
            return result == null ? Map.of() : result;
        } catch (RuntimeException e) {
            log.warn("event=ai_service_call path={} modelName={} success=false durationMs={} reason={}",
                path, modelName, Duration.ofNanos(System.nanoTime() - start).toMillis(), sanitize(e.getMessage()));
            throw e;
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)(api[_-]?key|authorization|bearer|password)=?\\s*\\S+", "$1=***");
    }
}
