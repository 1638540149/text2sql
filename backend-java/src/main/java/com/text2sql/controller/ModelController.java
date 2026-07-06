package com.text2sql.controller;

import com.text2sql.mapper.CoreMapper;
import com.text2sql.service.AiClient;
import com.text2sql.service.CryptoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ModelController {
    private final CoreMapper mapper;
    private final CryptoService crypto;
    private final AiClient aiClient;

    public ModelController(CoreMapper mapper, CryptoService crypto, AiClient aiClient) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.aiClient = aiClient;
    }

    @GetMapping("/models")
    public List<Map<String, Object>> enabled() {
        return mapper.listEnabledModels();
    }

    @GetMapping("/admin/models")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> all() {
        return mapper.listModels().stream().peek(row -> row.remove("apiKeyCipher")).toList();
    }

    @PostMapping("/admin/models")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody ModelRequest request) {
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        mapper.insertModel(request.name(), request.baseUrl(), crypto.encrypt(request.apiKey()), request.modelName(), request.enabled(),
            request.promptPricePer1k() == null ? BigDecimal.ZERO : request.promptPricePer1k(),
            request.completionPricePer1k() == null ? BigDecimal.ZERO : request.completionPricePer1k());
        return Map.of("id", mapper.lastInsertId());
    }

    @PutMapping("/admin/models/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody ModelRequest request) {
        Map<String, Object> existing = mapper.findModelForAdmin(id);
        if (existing == null) {
            throw new IllegalArgumentException("模型不存在");
        }
        Object existingCipher = existing.get("apiKeyCipher");
        String apiKeyCipher = request.apiKey() == null || request.apiKey().isBlank()
            ? existingCipher == null ? "" : String.valueOf(existingCipher)
            : crypto.encrypt(request.apiKey());
        mapper.updateModel(id, request.name(), request.baseUrl(), apiKeyCipher, request.modelName(), request.enabled(),
            request.promptPricePer1k() == null ? BigDecimal.ZERO : request.promptPricePer1k(),
            request.completionPricePer1k() == null ? BigDecimal.ZERO : request.completionPricePer1k());
        return Map.of("success", true);
    }

    @PostMapping("/admin/models/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> test(@PathVariable Long id) {
        Map<String, Object> model = mapper.findModelForAdmin(id);
        if (model == null) {
            throw new IllegalArgumentException("模型不存在");
        }
        List<Map<String, Object>> metadata = List.of(Map.of(
            "tableName", "model_connection_test",
            "tableComment", "模型连通性测试表",
            "columns", List.of(
                Map.of("columnName", "id", "dataType", "BIGINT", "columnComment", "主键"),
                Map.of("columnName", "name", "dataType", "VARCHAR", "columnComment", "名称")
            )
        ));
        try {
            Map<String, Object> result = aiClient.generate("查询 model_connection_test 表的前 5 条记录", "MYSQL", metadata, model);
            return Map.of("success", true, "result", result);
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @GetMapping("/admin/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> settings() {
        return mapper.listSettings();
    }

    @PutMapping("/admin/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateSetting(@RequestBody SettingRequest request) {
        mapper.updateSetting(request.key(), request.value());
        return Map.of("success", true);
    }

    public record ModelRequest(@NotBlank String name, @NotBlank String baseUrl, String apiKey,
                               @NotBlank String modelName, boolean enabled, BigDecimal promptPricePer1k,
                               BigDecimal completionPricePer1k) {}

    public record SettingRequest(@NotBlank String key, @NotBlank String value) {}
}
