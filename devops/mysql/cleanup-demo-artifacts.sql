USE NL2SQL;

CREATE TEMPORARY TABLE IF NOT EXISTS tmp_text2sql_demo_datasource_ids AS
SELECT id
FROM data_source
WHERE name = 'NL2SQL 演示销售库';

DELETE FROM user_data_source
WHERE data_source_id IN (SELECT id FROM tmp_text2sql_demo_datasource_ids);

DELETE FROM datasource_index_info
WHERE data_source_id IN (SELECT id FROM tmp_text2sql_demo_datasource_ids);

DELETE FROM datasource_column
WHERE data_source_id IN (SELECT id FROM tmp_text2sql_demo_datasource_ids);

DELETE FROM datasource_table
WHERE data_source_id IN (SELECT id FROM tmp_text2sql_demo_datasource_ids);

DELETE FROM data_source
WHERE id IN (SELECT id FROM tmp_text2sql_demo_datasource_ids);

DELETE FROM ai_model_config
WHERE name = '演示模型' OR model_name = 'demo-nl2sql';

DROP TABLE IF EXISTS demo_orders;

DROP TEMPORARY TABLE IF EXISTS tmp_text2sql_demo_datasource_ids;
