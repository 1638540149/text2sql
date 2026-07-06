package com.text2sql.service;

import com.text2sql.mapper.CoreMapper;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class JdbcDatasourceService {
    private static final Set<String> MYSQL_SYSTEM_DATABASES = Set.of("information_schema", "mysql", "performance_schema", "sys");
    private final CoreMapper mapper;
    private final CryptoService crypto;

    public JdbcDatasourceService(CoreMapper mapper, CryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public Connection connect(Map<String, Object> ds) throws Exception {
        requireMysql(String.valueOf(ds.getOrDefault("dbType", "MYSQL")));
        String url = "jdbc:mysql://" + ds.get("host") + ":" + ds.get("port") + "/" + ds.get("databaseName")
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&useInformationSchema=true&serverTimezone=Asia/Shanghai";
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(url, String.valueOf(ds.get("username")), crypto.decrypt(String.valueOf(ds.get("passwordCipher"))));
    }

    public List<String> listDatabases(String dbType, String host, int port, String username, String password) throws Exception {
        requireMysql(dbType);
        String url = "jdbc:mysql://" + host + ":" + port
            + "/?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        DriverManager.setLoginTimeout(5);
        List<String> databases = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                String database = rs.getString(1);
                if (!MYSQL_SYSTEM_DATABASES.contains(database.toLowerCase(Locale.ROOT))) {
                    databases.add(database);
                }
            }
        }
        return databases;
    }

    private void requireMysql(String dbType) {
        if (!"MYSQL".equalsIgnoreCase(dbType)) {
            throw new IllegalArgumentException("第一版仅支持 MySQL，更多数据库类型将在 V2 支持");
        }
    }

    public Map<String, Object> test(Map<String, Object> ds) {
        long start = System.nanoTime();
        try (Connection ignored = connect(ds)) {
            return Map.of("success", true, "durationMs", Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage(), "durationMs", Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
    }

    public Map<String, Object> refreshMetadata(Long dataSourceId, Map<String, Object> ds) throws Exception {
        mapper.deleteIndexes(dataSourceId);
        mapper.deleteColumns(dataSourceId);
        mapper.deleteTables(dataSourceId);
        int tableCount = 0;
        int columnCount = 0;
        int indexCount = 0;
        String database = String.valueOf(ds.get("databaseName"));
        try (Connection connection = connect(ds)) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet tables = meta.getTables(database, null, "%", new String[]{"TABLE", "VIEW"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    String comment = tables.getString("REMARKS");
                    mapper.insertTable(dataSourceId, database, tableName, comment == null ? "" : comment);
                    tableCount++;
                    try (ResultSet columns = meta.getColumns(database, null, tableName, "%")) {
                        while (columns.next()) {
                            mapper.insertColumn(dataSourceId, tableName, columns.getString("COLUMN_NAME"),
                                columns.getString("TYPE_NAME"), columns.getString("IS_NULLABLE"),
                                "", columns.getString("REMARKS"), columns.getInt("ORDINAL_POSITION"));
                            columnCount++;
                        }
                    }
                    try (ResultSet indexes = meta.getIndexInfo(database, null, tableName, false, false)) {
                        while (indexes.next()) {
                            String columnName = indexes.getString("COLUMN_NAME");
                            if (columnName == null) {
                                continue;
                            }
                            mapper.insertIndex(dataSourceId, tableName, indexes.getString("INDEX_NAME"),
                                columnName, indexes.getBoolean("NON_UNIQUE"));
                            indexCount++;
                        }
                    }
                }
            }
        }
        return Map.of("tableCount", tableCount, "columnCount", columnCount, "indexCount", indexCount);
    }

    public Map<String, Object> metadata(Long dataSourceId) {
        List<Map<String, Object>> tables = mapper.listTables(dataSourceId);
        List<Map<String, Object>> columns = mapper.listColumns(dataSourceId);
        List<Map<String, Object>> indexes = mapper.listIndexes(dataSourceId);
        Map<String, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>();
        for (Map<String, Object> column : columns) {
            columnsByTable.computeIfAbsent(String.valueOf(column.get("tableName")), k -> new ArrayList<>()).add(column);
        }
        for (Map<String, Object> table : tables) {
            table.put("columns", columnsByTable.getOrDefault(String.valueOf(table.get("tableName")), List.of()));
        }
        return Map.of("tables", tables, "indexes", indexes);
    }

    public List<Map<String, Object>> metadataSummary(Long dataSourceId, List<String> selectedTables, String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> tables = mapper.listTables(dataSourceId);
        List<Map<String, Object>> columns = mapper.listColumns(dataSourceId);
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String tableName = String.valueOf(table.get("tableName"));
            boolean selected = selectedTables != null && selectedTables.contains(tableName);
            boolean matched = selected || selectedTables == null || selectedTables.isEmpty();
            if (!selected && selectedTables != null && !selectedTables.isEmpty()) {
                matched = false;
            }
            if (!matched) {
                continue;
            }
            if ((selectedTables == null || selectedTables.isEmpty()) && !normalized.isBlank()) {
                matched = tableName.toLowerCase(Locale.ROOT).contains(normalized);
                if (!matched) {
                    for (Map<String, Object> column : columns) {
                        if (tableName.equals(column.get("tableName"))) {
                            String text = (column.get("columnName") + " " + column.get("columnComment")).toLowerCase(Locale.ROOT);
                            if (normalized.contains(String.valueOf(column.get("columnName")).toLowerCase(Locale.ROOT)) || text.contains(normalized)) {
                                matched = true;
                                break;
                            }
                        }
                    }
                }
            }
            if (!matched && summary.size() >= 8) {
                continue;
            }
            List<Map<String, Object>> tableColumns = new ArrayList<>();
            for (Map<String, Object> column : columns) {
                if (tableName.equals(column.get("tableName"))) {
                    tableColumns.add(column);
                }
            }
            Object comment = table.get("tableComment");
            summary.add(Map.of("tableName", tableName, "tableComment", comment == null ? "" : comment, "columns", tableColumns));
            if ((selectedTables == null || selectedTables.isEmpty()) && summary.size() >= 8) {
                break;
            }
        }
        return summary;
    }

    public Map<String, Object> explain(Map<String, Object> ds, String sql, long warnRows, int timeoutSeconds) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        long estimatedRows = 0;
        try (Connection connection = connect(ds); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    Object value = row.get("rows");
                    if (value instanceof Number number) {
                        estimatedRows += number.longValue();
                    }
                    rows.add(row);
                }
            }
        }
        boolean highRisk = estimatedRows > warnRows;
        return Map.of("highRisk", highRisk, "estimatedRows", estimatedRows, "rows", rows,
            "message", highRisk ? "EXPLAIN 估算扫描行数超过阈值，需要确认后执行" : "执行计划检查通过");
    }

    public Map<String, Object> executeSelect(Map<String, Object> ds, String sql, int maxRows, int timeoutSeconds) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        try (Connection connection = connect(ds); Statement statement = connection.createStatement()) {
            statement.setMaxRows(maxRows);
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    fields.add(Map.of("name", meta.getColumnLabel(i), "type", meta.getColumnTypeName(i)));
                }
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return Map.of("fields", fields, "rows", rows, "rowCount", rows.size());
    }
}
