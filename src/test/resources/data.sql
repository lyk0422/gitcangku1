-- 测试合成数据：与主 data.sql 相同的双方授权基线，使用独立命名库 consent-test 相互隔离
-- 使用 MERGE INTO 保证幂等：同 JVM 内脚本重复执行不产生主键冲突
MERGE INTO consent_grant (subject_key, purpose, epoch, status, request_id)
KEY (subject_key, purpose, epoch)
VALUES ('subj-a', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-subj-a-1');
MERGE INTO consent_grant (subject_key, purpose, epoch, status, request_id)
KEY (subject_key, purpose, epoch)
VALUES ('subj-b', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-subj-b-1');
