-- 本题合成数据：主体 subj-a 与 subj-b 各持有效研究授权代次 1，用于共同研究记录场景
-- 使用 MERGE INTO 保证幂等：同 JVM 内脚本重复执行（内存库 DB_CLOSE_DELAY=-1 存活期间）不产生主键冲突
MERGE INTO consent_grant (subject_key, purpose, epoch, status, request_id)
KEY (subject_key, purpose, epoch)
VALUES ('subj-a', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-subj-a-1');
MERGE INTO consent_grant (subject_key, purpose, epoch, status, request_id)
KEY (subject_key, purpose, epoch)
VALUES ('subj-b', 'RESEARCH', 1, 'ACTIVE', 'seed-grant-subj-b-1');
