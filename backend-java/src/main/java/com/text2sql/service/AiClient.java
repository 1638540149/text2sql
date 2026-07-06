package com.text2sql.service;

import com.text2sql.config.AppProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AiClient {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("dialect", dialect);
        body.put("metadata", metadata);
        body.put("model", safeModel(model));
        return restTemplate.postForObject(properties.getAiServiceUrl() + "/nl2sql/generate", body, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(String sql, List<Map<String, Object>> metadata) {
        Map<String, Object> body = Map.of("sql", sql, "metadata", metadata);
        return restTemplate.postForObject(properties.getAiServiceUrl() + "/sql/validate", body, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> recommendChart(String question, List<Map<String, Object>> fields, List<Map<String, Object>> rows) {
        Map<String, Object> body = Map.of("question", question, "fields", fields, "rows", rows);
        return restTemplate.postForObject(properties.getAiServiceUrl() + "/chart/recommend", body, Map.class);
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
}
