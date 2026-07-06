CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS data_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  db_type VARCHAR(32) NOT NULL DEFAULT 'MYSQL',
  host VARCHAR(255) NOT NULL,
  port INT NOT NULL,
  database_name VARCHAR(128) NOT NULL,
  username VARCHAR(128) NOT NULL,
  password_cipher TEXT NOT NULL,
  remark VARCHAR(500),
  enabled TINYINT NOT NULL DEFAULT 1,
  created_by BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_data_source (
  user_id BIGINT NOT NULL,
  data_source_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, data_source_id)
);

CREATE TABLE IF NOT EXISTS datasource_table (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  data_source_id BIGINT NOT NULL,
  table_schema VARCHAR(128),
  table_name VARCHAR(128) NOT NULL,
  table_comment VARCHAR(500),
  synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS datasource_column (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  data_source_id BIGINT NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  data_type VARCHAR(128),
  nullable_flag VARCHAR(16),
  column_key VARCHAR(32),
  column_comment VARCHAR(500),
  ordinal_position INT
);

CREATE TABLE IF NOT EXISTS datasource_index_info (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  data_source_id BIGINT NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  index_name VARCHAR(128),
  column_name VARCHAR(128),
  non_unique TINYINT
);

CREATE TABLE IF NOT EXISTS ai_model_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  provider VARCHAR(64) NOT NULL DEFAULT 'openai-compatible',
  base_url VARCHAR(500) NOT NULL,
  api_key_cipher TEXT,
  model_name VARCHAR(128) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  prompt_price_per_1k DECIMAL(12,6) NOT NULL DEFAULT 0,
  completion_price_per_1k DECIMAL(12,6) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS system_setting (
  setting_key VARCHAR(80) PRIMARY KEY,
  setting_value VARCHAR(255) NOT NULL,
  remark VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS query_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  data_source_id BIGINT NOT NULL,
  model_id BIGINT,
  question TEXT NOT NULL,
  generated_sql TEXT,
  final_sql TEXT,
  status VARCHAR(32) NOT NULL,
  failure_reason VARCHAR(64),
  duration_ms BIGINT DEFAULT 0,
  prompt_tokens INT DEFAULT 0,
  completion_tokens INT DEFAULT 0,
  cost_estimate DECIMAL(12,6) DEFAULT 0,
  result_row_count INT DEFAULT 0,
  chart_config_json TEXT,
  trace_json MEDIUMTEXT,
  feedback_score INT,
  feedback_tags VARCHAR(500),
  feedback_comment VARCHAR(1000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
