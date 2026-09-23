package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代次与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 *
 * <p>用途为目录驱动的字符串代码；迁移后旧授权状态置为 MIGRATED，
 * 数据记录通过 {@link #rebindRecordIfVersion} 一次性改绑到新用途（始终保持单一活动用途归属）。
 * 行版本 row_version 用于预览与激活之间的并发变化（撤回/写入/另一迁移）检测。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            rs.getString("purpose"),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getLong("row_version"),
            (Integer) rs.getObject("catalog_generation"));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            rs.getString("purpose"),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"),
            rs.getString("attribute_value"),
            rs.getLong("row_version"));

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权代次行。
     *
     * @param subjectKey        主体标识
     * @param purpose           用途代码
     * @param epoch             代次，从 1 开始
     * @param status            状态：ACTIVE / REVOKED / MIGRATED
     * @param rowVersion        行版本，撤回或迁移时递增
     * @param catalogGeneration 归属目录代次，历史授权可能为空
     */
    public record GrantRow(String subjectKey, String purpose, int epoch, GrantStatus status,
                           long rowVersion, Integer catalogGeneration) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey     主体标识
     * @param purpose        当前活动用途代码
     * @param epoch          记录写入时所属授权代次（迁移不改写）
     * @param recordKey      记录键
     * @param payload        记录内容
     * @param attributeValue 记录属性取值，用于迁移目标判定，可为空
     * @param rowVersion     行版本，改绑时递增
     */
    public record RecordRow(String subjectKey, String purpose, int epoch, String recordKey,
                            String payload, String attributeValue, long rowVersion) {
    }

    private static final String GRANT_COLUMNS =
            "subject_key, purpose, epoch, status, row_version, catalog_generation";

    private static final String RECORD_COLUMNS =
            "subject_key, purpose, epoch, record_key, payload, attribute_value, row_version";

    public Optional<GrantRow> findGrant(String subjectKey, String purpose, int epoch) {
        return jdbc.query("SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose, epoch).stream().findFirst();
    }

    public Optional<GrantRow> findLatestGrant(String subjectKey, String purpose) {
        return jdbc.query("SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose).stream().findFirst();
    }

    /**
     * 锁定指定授权行并读取最新已提交状态；写入路径借此与目录迁移串行化。
     */
    public Optional<GrantRow> lockGrant(String subjectKey, String purpose, int epoch) {
        return jdbc.query("SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose, epoch).stream().findFirst();
    }

    /**
     * 锁定某主体＋用途的最新授权行并读取。
     */
    public Optional<GrantRow> lockLatestGrant(String subjectKey, String purpose) {
        return jdbc.query("SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1 FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose).stream().findFirst();
    }

    public void insertGrant(String subjectKey, String purpose, int epoch, String requestId,
                            Integer catalogGeneration) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id, catalog_generation)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                subjectKey, purpose, epoch, requestId, catalogGeneration);
    }

    /**
     * 迁移时为新用途插入首个有效授权代次。
     */
    public void insertMigratedGrant(String subjectKey, String purpose, int epoch, String requestId,
                             int catalogGeneration) {
        jdbc.update(
                "INSERT INTO consent_grant"
                        + " (subject_key, purpose, epoch, status, request_id, catalog_generation)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                subjectKey, purpose, epoch, requestId, catalogGeneration);
    }

    /**
     * 仅当代次当前为 ACTIVE 时撤回；返回是否实际发生状态变更。
     */
    public boolean revokeGrant(String subjectKey, String purpose, int epoch) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,"
                        + " row_version = row_version + 1"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                subjectKey, purpose, epoch);
        return updated > 0;
    }

    /**
     * 迁移提交：仅当旧授权仍为 ACTIVE 且行版本未变时置为 MIGRATED。
     */
    public boolean markGrantMigratedIfVersion(String subjectKey, String purpose, int epoch, long expectedVersion) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'MIGRATED', migrated_at = CURRENT_TIMESTAMP,"
                        + " row_version = row_version + 1"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND status = 'ACTIVE' AND row_version = ?",
                subjectKey, purpose, epoch, expectedVersion);
        return updated > 0;
    }

    /**
     * 预览只读扫描绑定某用途的全部授权代次（含已撤回、已迁移历史行）。
     */
    public List<GrantRow> findAllGrantsByPurpose(String purpose) {
        return jdbc.query("SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                + " WHERE purpose = ? ORDER BY subject_key, epoch", GRANT_MAPPER, purpose);
    }

    /**
     * 激活时锁定并扫描绑定某用途的全部授权代次（含已撤回、已迁移历史行）。
     */
    public List<GrantRow> findAllGrantsByPurposeForUpdate(String purpose) {
        return jdbc.query("SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                + " WHERE purpose = ? ORDER BY subject_key, epoch FOR UPDATE", GRANT_MAPPER, purpose);
    }

    /**
     * 预览只读扫描绑定某用途的全部数据记录。
     */
    public List<RecordRow> findAllRecordsByPurpose(String purpose) {
        return jdbc.query("SELECT " + RECORD_COLUMNS + " FROM consent_record"
                + " WHERE purpose = ? ORDER BY subject_key, epoch, record_key", RECORD_MAPPER, purpose);
    }

    /**
     * 激活时锁定并扫描绑定某用途的全部数据记录。
     */
    public List<RecordRow> findAllRecordsByPurposeForUpdate(String purpose) {
        return jdbc.query("SELECT " + RECORD_COLUMNS + " FROM consent_record"
                + " WHERE purpose = ? ORDER BY subject_key, epoch, record_key FOR UPDATE",
                RECORD_MAPPER, purpose);
    }

    public Optional<RecordRow> findRecord(String subjectKey, String purpose, int epoch, String recordKey) {
        return jdbc.query("SELECT " + RECORD_COLUMNS + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose, epoch, recordKey).stream().findFirst();
    }

    public void insertRecord(String subjectKey, String purpose, int epoch, String recordKey, String payload,
                      String attributeValue, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload,"
                        + " attribute_value, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose, epoch, recordKey, payload, attributeValue, requestId);
    }

    /**
     * 数据改绑：按主键且校验旧用途与行版本后改绑到新用途；
     * 条件含旧用途名，保证任何数据行始终只有一个活动用途归属，并发改绑由行锁串行化。
     */
    public boolean rebindRecordIfVersion(String subjectKey, int epoch, String recordKey, String oldPurpose,
                                  String newPurpose, long expectedVersion) {
        int updated = jdbc.update(
                "UPDATE consent_record SET purpose = ?, row_version = row_version + 1"
                        + " WHERE subject_key = ? AND epoch = ? AND record_key = ?"
                        + " AND purpose = ? AND row_version = ?",
                newPurpose, subjectKey, epoch, recordKey, oldPurpose, expectedVersion);
        return updated > 0;
    }
}
