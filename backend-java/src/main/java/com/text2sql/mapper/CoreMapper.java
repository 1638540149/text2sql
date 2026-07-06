package com.text2sql.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CoreMapper {
    @Select("SELECT id, username, password_hash AS passwordHash, role, display_name AS displayName FROM sys_user WHERE username=#{username} AND enabled=1")
    Map<String, Object> findUserByUsername(@Param("username") String username);

    @Select("SELECT id, username, role, display_name AS displayName FROM sys_user WHERE enabled=1 ORDER BY id")
    List<Map<String, Object>> listUsers();

    @Select("SELECT COUNT(*) FROM data_source WHERE name=#{name}")
    int countDatasourceByName(@Param("name") String name);

    @Insert("""
        INSERT INTO data_source (name, db_type, host, port, database_name, username, password_cipher, remark, enabled, created_by)
        VALUES (#{name}, #{dbType}, #{host}, #{port}, #{databaseName}, #{username}, #{passwordCipher}, #{remark}, 1, #{createdBy})
        """)
    void insertDatasource(@Param("name") String name, @Param("dbType") String dbType,
                          @Param("host") String host, @Param("port") int port,
                          @Param("databaseName") String databaseName, @Param("username") String username,
                          @Param("passwordCipher") String passwordCipher, @Param("remark") String remark,
                          @Param("createdBy") Long createdBy);

    @Select("SELECT LAST_INSERT_ID()")
    Long lastInsertId();

    @Insert("INSERT IGNORE INTO user_data_source (user_id, data_source_id) VALUES (#{userId}, #{dataSourceId})")
    void grantDatasource(@Param("userId") Long userId, @Param("dataSourceId") Long dataSourceId);

    @Select("""
        SELECT ds.id, ds.name, ds.db_type AS dbType, ds.host, ds.port, ds.database_name AS databaseName,
               ds.username, ds.remark, ds.enabled, ds.created_at AS createdAt, ds.updated_at AS updatedAt
        FROM data_source ds
        WHERE ds.enabled=1
          AND (#{admin}=true OR EXISTS (
            SELECT 1 FROM user_data_source uds WHERE uds.data_source_id=ds.id AND uds.user_id=#{userId}
          ))
        ORDER BY ds.id DESC
        """)
    List<Map<String, Object>> listDatasources(@Param("userId") Long userId, @Param("admin") boolean admin);

    @Select("""
        SELECT id, name, db_type AS dbType, host, port, database_name AS databaseName, username,
               password_cipher AS passwordCipher, remark, enabled, created_by AS createdBy,
               created_at AS createdAt, updated_at AS updatedAt
        FROM data_source WHERE id=#{id} AND enabled=1
        """)
    Map<String, Object> findDatasource(@Param("id") Long id);

    @Select("""
        SELECT ds.id, ds.name, ds.db_type AS dbType, ds.host, ds.port, ds.database_name AS databaseName,
               ds.username, ds.password_cipher AS passwordCipher, ds.remark, ds.enabled,
               ds.created_by AS createdBy, ds.created_at AS createdAt, ds.updated_at AS updatedAt
        FROM data_source ds
        WHERE ds.id=#{id} AND ds.enabled=1
          AND EXISTS (SELECT 1 FROM user_data_source uds WHERE uds.data_source_id=ds.id AND uds.user_id=#{userId})
        """)
    Map<String, Object> findAuthorizedDatasource(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("DELETE FROM datasource_table WHERE data_source_id=#{dataSourceId}")
    void deleteTables(@Param("dataSourceId") Long dataSourceId);

    @Delete("DELETE FROM datasource_column WHERE data_source_id=#{dataSourceId}")
    void deleteColumns(@Param("dataSourceId") Long dataSourceId);

    @Delete("DELETE FROM datasource_index_info WHERE data_source_id=#{dataSourceId}")
    void deleteIndexes(@Param("dataSourceId") Long dataSourceId);

    @Insert("""
        INSERT INTO datasource_table (data_source_id, table_schema, table_name, table_comment)
        VALUES (#{dataSourceId}, #{schema}, #{tableName}, #{comment})
        """)
    void insertTable(@Param("dataSourceId") Long dataSourceId, @Param("schema") String schema,
                     @Param("tableName") String tableName, @Param("comment") String comment);

    @Insert("""
        INSERT INTO datasource_column (data_source_id, table_name, column_name, data_type, nullable_flag, column_key, column_comment, ordinal_position)
        VALUES (#{dataSourceId}, #{tableName}, #{columnName}, #{dataType}, #{nullableFlag}, #{columnKey}, #{comment}, #{ordinal})
        """)
    void insertColumn(@Param("dataSourceId") Long dataSourceId, @Param("tableName") String tableName,
                      @Param("columnName") String columnName, @Param("dataType") String dataType,
                      @Param("nullableFlag") String nullableFlag, @Param("columnKey") String columnKey,
                      @Param("comment") String comment, @Param("ordinal") int ordinal);

    @Insert("""
        INSERT INTO datasource_index_info (data_source_id, table_name, index_name, column_name, non_unique)
        VALUES (#{dataSourceId}, #{tableName}, #{indexName}, #{columnName}, #{nonUnique})
        """)
    void insertIndex(@Param("dataSourceId") Long dataSourceId, @Param("tableName") String tableName,
                     @Param("indexName") String indexName, @Param("columnName") String columnName,
                     @Param("nonUnique") boolean nonUnique);

    @Select("SELECT table_name AS tableName, table_comment AS tableComment, synced_at AS syncedAt FROM datasource_table WHERE data_source_id=#{dataSourceId} ORDER BY table_name")
    List<Map<String, Object>> listTables(@Param("dataSourceId") Long dataSourceId);

    @Select("SELECT table_name AS tableName, column_name AS columnName, data_type AS dataType, nullable_flag AS nullableFlag, column_key AS columnKey, column_comment AS columnComment, ordinal_position AS ordinalPosition FROM datasource_column WHERE data_source_id=#{dataSourceId} ORDER BY table_name, ordinal_position")
    List<Map<String, Object>> listColumns(@Param("dataSourceId") Long dataSourceId);

    @Select("SELECT table_name AS tableName, index_name AS indexName, column_name AS columnName, non_unique AS nonUnique FROM datasource_index_info WHERE data_source_id=#{dataSourceId} ORDER BY table_name, index_name")
    List<Map<String, Object>> listIndexes(@Param("dataSourceId") Long dataSourceId);

    @Select("""
        SELECT id, name, provider, base_url AS baseUrl, api_key_cipher AS apiKeyCipher,
               model_name AS modelName, enabled, prompt_price_per_1k AS promptPricePer1k,
               completion_price_per_1k AS completionPricePer1k, created_at AS createdAt
        FROM ai_model_config ORDER BY id DESC
        """)
    List<Map<String, Object>> listModels();

    @Select("SELECT id, name, provider, base_url AS baseUrl, model_name AS modelName, enabled, prompt_price_per_1k AS promptPricePer1k, completion_price_per_1k AS completionPricePer1k FROM ai_model_config WHERE enabled=1 ORDER BY id")
    List<Map<String, Object>> listEnabledModels();

    @Select("""
        SELECT id, name, provider, base_url AS baseUrl, api_key_cipher AS apiKeyCipher,
               model_name AS modelName, enabled, prompt_price_per_1k AS promptPricePer1k,
               completion_price_per_1k AS completionPricePer1k, created_at AS createdAt
        FROM ai_model_config WHERE id=#{id} AND enabled=1
        """)
    Map<String, Object> findModel(@Param("id") Long id);

    @Select("""
        SELECT id, name, provider, base_url AS baseUrl, api_key_cipher AS apiKeyCipher,
               model_name AS modelName, enabled, prompt_price_per_1k AS promptPricePer1k,
               completion_price_per_1k AS completionPricePer1k, created_at AS createdAt
        FROM ai_model_config WHERE id=#{id}
        """)
    Map<String, Object> findModelForAdmin(@Param("id") Long id);

    @Insert("""
        INSERT INTO ai_model_config (name, provider, base_url, api_key_cipher, model_name, enabled, prompt_price_per_1k, completion_price_per_1k)
        VALUES (#{name}, 'openai-compatible', #{baseUrl}, #{apiKeyCipher}, #{modelName}, #{enabled}, #{promptPrice}, #{completionPrice})
        """)
    void insertModel(@Param("name") String name, @Param("baseUrl") String baseUrl,
                     @Param("apiKeyCipher") String apiKeyCipher, @Param("modelName") String modelName,
                     @Param("enabled") boolean enabled, @Param("promptPrice") BigDecimal promptPrice,
                     @Param("completionPrice") BigDecimal completionPrice);

    @Update("""
        UPDATE ai_model_config
        SET name=#{name},
            base_url=#{baseUrl},
            api_key_cipher=#{apiKeyCipher},
            model_name=#{modelName},
            enabled=#{enabled},
            prompt_price_per_1k=#{promptPrice},
            completion_price_per_1k=#{completionPrice}
        WHERE id=#{id}
        """)
    int updateModel(@Param("id") Long id, @Param("name") String name, @Param("baseUrl") String baseUrl,
                    @Param("apiKeyCipher") String apiKeyCipher, @Param("modelName") String modelName,
                    @Param("enabled") boolean enabled, @Param("promptPrice") BigDecimal promptPrice,
                    @Param("completionPrice") BigDecimal completionPrice);

    @Select("SELECT setting_key AS settingKey, setting_value AS settingValue, remark FROM system_setting ORDER BY setting_key")
    List<Map<String, Object>> listSettings();

    @Select("SELECT setting_value FROM system_setting WHERE setting_key=#{key}")
    String getSetting(@Param("key") String key);

    @Update("UPDATE system_setting SET setting_value=#{value} WHERE setting_key=#{key}")
    void updateSetting(@Param("key") String key, @Param("value") String value);

    @Insert("""
        INSERT INTO query_history
        (user_id, data_source_id, model_id, question, generated_sql, final_sql, status, failure_reason, duration_ms,
         prompt_tokens, completion_tokens, cost_estimate, result_row_count, chart_config_json, trace_json)
        VALUES
        (#{userId}, #{dataSourceId}, #{modelId}, #{question}, #{generatedSql}, #{finalSql}, #{status}, #{failureReason},
         #{durationMs}, #{promptTokens}, #{completionTokens}, #{costEstimate}, #{resultRowCount}, #{chartConfigJson}, #{traceJson})
        """)
    void insertHistory(@Param("userId") Long userId, @Param("dataSourceId") Long dataSourceId, @Param("modelId") Long modelId,
                       @Param("question") String question, @Param("generatedSql") String generatedSql,
                       @Param("finalSql") String finalSql, @Param("status") String status,
                       @Param("failureReason") String failureReason, @Param("durationMs") long durationMs,
                       @Param("promptTokens") int promptTokens, @Param("completionTokens") int completionTokens,
                       @Param("costEstimate") BigDecimal costEstimate, @Param("resultRowCount") int resultRowCount,
                       @Param("chartConfigJson") String chartConfigJson, @Param("traceJson") String traceJson);

    @Select("""
        SELECT qh.id, qh.user_id AS userId, qh.data_source_id AS dataSourceId, qh.model_id AS modelId,
               qh.question, qh.generated_sql AS generatedSql, qh.final_sql AS finalSql, qh.status,
               qh.failure_reason AS failureReason, qh.duration_ms AS durationMs,
               qh.prompt_tokens AS promptTokens, qh.completion_tokens AS completionTokens,
               qh.cost_estimate AS costEstimate, qh.result_row_count AS resultRowCount,
               qh.chart_config_json AS chartConfigJson, qh.trace_json AS traceJson,
               qh.feedback_score AS feedbackScore, qh.feedback_tags AS feedbackTags,
               qh.feedback_comment AS feedbackComment, qh.created_at AS createdAt,
               u.username, ds.name AS dataSourceName, m.name AS modelName
        FROM query_history qh
        JOIN sys_user u ON u.id=qh.user_id
        JOIN data_source ds ON ds.id=qh.data_source_id
        LEFT JOIN ai_model_config m ON m.id=qh.model_id
        WHERE (#{admin}=true OR qh.user_id=#{userId})
        ORDER BY qh.id DESC
        LIMIT 200
        """)
    List<Map<String, Object>> listHistory(@Param("userId") Long userId, @Param("admin") boolean admin);

    @Update("UPDATE query_history SET feedback_score=#{score}, feedback_tags=#{tags}, feedback_comment=#{comment} WHERE id=#{id} AND (#{admin}=true OR user_id=#{userId})")
    int updateFeedback(@Param("id") Long id, @Param("userId") Long userId, @Param("admin") boolean admin,
                       @Param("score") int score, @Param("tags") String tags, @Param("comment") String comment);

    @Select("""
        SELECT qh.id, qh.user_id AS userId, qh.data_source_id AS dataSourceId, qh.model_id AS modelId,
               qh.question, qh.generated_sql AS generatedSql, qh.final_sql AS finalSql, qh.status,
               qh.failure_reason AS failureReason, qh.duration_ms AS durationMs,
               qh.prompt_tokens AS promptTokens, qh.completion_tokens AS completionTokens,
               qh.cost_estimate AS costEstimate, qh.result_row_count AS resultRowCount,
               qh.chart_config_json AS chartConfigJson, qh.trace_json AS traceJson,
               qh.feedback_score AS feedbackScore, qh.feedback_tags AS feedbackTags,
               qh.feedback_comment AS feedbackComment, qh.created_at AS createdAt,
               ds.name AS dataSourceName, m.name AS modelName
        FROM query_history qh
        JOIN data_source ds ON ds.id=qh.data_source_id
        LEFT JOIN ai_model_config m ON m.id=qh.model_id
        WHERE (#{modelId} IS NULL OR qh.model_id=#{modelId})
          AND (#{dataSourceId} IS NULL OR qh.data_source_id=#{dataSourceId})
          AND (#{from} IS NULL OR qh.created_at >= #{from})
          AND (#{to} IS NULL OR qh.created_at <= #{to})
        ORDER BY qh.created_at ASC
        """)
    List<Map<String, Object>> analyticsRows(@Param("modelId") Long modelId, @Param("dataSourceId") Long dataSourceId,
                                            @Param("from") String from, @Param("to") String to);
}
