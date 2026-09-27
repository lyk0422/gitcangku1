-- 合成演示数据：供本地入口直接体验附件脱敏流程。全部为虚构数据，可重复启动（幂等插入）。

-- 一条现场观测（当前版本 v1）。
INSERT INTO observation_current (observation_id, location, reading, note, version, deleted, updated_at)
SELECT 'obs-demo', '示范站点 1 号井', '36.750', '例行巡查，设备运行正常', 1, FALSE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM observation_current WHERE observation_id = 'obs-demo');

INSERT INTO observation_version (observation_id, version, location, reading, note, deleted, created_at)
SELECT 'obs-demo', 1, '示范站点 1 号井', '36.750', '例行巡查，设备运行正常', FALSE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM observation_version WHERE observation_id = 'obs-demo' AND version = 1);

-- 两个已登记的脱敏规则版本。
INSERT INTO redaction_rule (rule_code, rule_version, rule_name, revoked, revoked_at_utc,
                            registered_at_utc, created_at)
SELECT 'PII', 1, '个人信息脱敏规则 v1（身份证号整体替换）', FALSE, NULL,
       TIMESTAMP '2026-09-01 00:00:00', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM redaction_rule WHERE rule_code = 'PII' AND rule_version = 1);

INSERT INTO redaction_rule (rule_code, rule_version, rule_name, revoked, revoked_at_utc,
                            registered_at_utc, created_at)
SELECT 'PII', 2, '个人信息脱敏规则 v2（身份证号保留前六后四）', FALSE, NULL,
       TIMESTAMP '2026-09-15 00:00:00', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM redaction_rule WHERE rule_code = 'PII' AND rule_version = 2);

-- 挂在示范观测下的附件元数据（原始摘要含虚构敏感信息）。
INSERT INTO observation_attachment (attachment_id, observation_id, file_name, content_type, size_bytes,
                                    original_summary, content_cleared, cleared_at_utc, created_at)
SELECT 'att-demo-1', 'obs-demo', '巡检口述记录.txt', 'text/plain', 2048,
       '巡查员张三，身份证 110101199001011234，联系电话 13800000000，记录井口温度 36.75 度。',
       FALSE, NULL, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM observation_attachment WHERE attachment_id = 'att-demo-1');
