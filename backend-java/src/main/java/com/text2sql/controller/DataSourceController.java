package com.text2sql.controller;

import com.text2sql.mapper.CoreMapper;
import com.text2sql.security.LoginUser;
import com.text2sql.service.CryptoService;
import com.text2sql.service.JdbcDatasourceService;
import com.text2sql.util.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {
    private final CoreMapper mapper;
    private final CryptoService crypto;
    private final JdbcDatasourceService jdbc;

    public DataSourceController(CoreMapper mapper, CryptoService crypto, JdbcDatasourceService jdbc) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        LoginUser user = CurrentUser.get();
        return mapper.listDatasources(user.getId(), user.isAdmin());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody DataSourceRequest request) {
        String dbType = normalizeDbType(request.dbType());
        mapper.insertDatasource(request.name(), dbType, request.host(), request.port(), request.databaseName(), request.username(),
            crypto.encrypt(request.password()), request.remark(), CurrentUser.get().getId());
        Long id = mapper.lastInsertId();
        mapper.grantDatasource(CurrentUser.get().getId(), id);
        return Map.of("id", id);
    }

    @PostMapping("/databases")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> databases(@Valid @RequestBody DatabaseListRequest request) throws Exception {
        String dbType = normalizeDbType(request.dbType());
        List<String> databases = jdbc.listDatabases(dbType, request.host(), request.port(), request.username(), request.password());
        return Map.of("databases", databases);
    }

    @PostMapping("/{id}/grants/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> grant(@PathVariable Long id, @PathVariable Long userId) {
        mapper.grantDatasource(userId, id);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        Map<String, Object> ds = authorized(id);
        return jdbc.test(ds);
    }

    @PostMapping("/{id}/metadata/refresh")
    public Map<String, Object> refresh(@PathVariable Long id) throws Exception {
        Map<String, Object> ds = authorized(id);
        return jdbc.refreshMetadata(id, ds);
    }

    @GetMapping("/{id}/metadata")
    public Map<String, Object> metadata(@PathVariable Long id) {
        authorized(id);
        return jdbc.metadata(id);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> users() {
        return mapper.listUsers();
    }

    private Map<String, Object> authorized(Long id) {
        LoginUser user = CurrentUser.get();
        Map<String, Object> ds = user.isAdmin() ? mapper.findDatasource(id) : mapper.findAuthorizedDatasource(id, user.getId());
        if (ds == null) {
            throw new IllegalArgumentException("无权访问该数据源");
        }
        return ds;
    }

    private String normalizeDbType(String dbType) {
        String value = dbType == null ? "" : dbType.trim().toUpperCase(Locale.ROOT);
        if (!"MYSQL".equals(value)) {
            throw new IllegalArgumentException("第一版仅支持 MySQL，更多数据库类型将在 V2 支持");
        }
        return value;
    }

    public record DatabaseListRequest(@NotBlank String dbType, @NotBlank String host, @Min(1) @Max(65535) int port,
                                      @NotBlank String username, @NotBlank String password) {}

    public record DataSourceRequest(@NotBlank String name, @NotBlank String dbType, @NotBlank String host,
                                    @Min(1) @Max(65535) int port,
                                    @NotBlank String databaseName, @NotBlank String username,
                                    @NotBlank String password, String remark) {}
}
