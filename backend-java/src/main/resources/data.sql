INSERT IGNORE INTO sys_user (id, username, password_hash, role, display_name, enabled) VALUES
  (1, 'admin', '{noop}admin123', 'ADMIN', '管理员', 1),
  (2, 'user', '{noop}user123', 'USER', '普通用户', 1);

INSERT IGNORE INTO system_setting (setting_key, setting_value, remark) VALUES
  ('query.max_rows', '200', '默认查询最大返回行数'),
  ('query.timeout_seconds', '15', '查询执行超时秒数'),
  ('query.explain_warn_rows', '10000', 'EXPLAIN 估算行数风险阈值');
