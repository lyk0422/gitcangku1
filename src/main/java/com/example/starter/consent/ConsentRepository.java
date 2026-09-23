package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代次与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            rs.getString("purpose"),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getLong("catalog_generation"));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            rs.getString("purpose"),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"),
            rs.getLong("record_attribute"),
            rs.getInt("version"),
            rs.getLong("catalog_generation"));

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
     * @param version           乐观锁版本，迁移预览与激活间检测变化
     * @param catalogGeneration 授权归属的目录代次
     */
    public record GrantRow(String subjectKey, String purpose, int epoch, GrantStatus status,
                           int version, long catalogGeneration) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey        主体标识
     * @param purpose           当前活动用途归属代码
     * @param epoch             所属授权代次
     * @param recordKey         记录键
     * @param payload           记录内容
     * @param recordAttribute   记录属性（处理空间内整数值）
     * @param version           乐观锁版本
     * @param catalogGeneration 记录当前归属的目录代次
     */
    public record RecordRow(String subjectKey, String purpose, int epoch, String recordKey, String payload,
                            long recordAttribute, int version, long catalogGeneration) {
    }

    public Optional<GrantRow> findGrant(String subjectKey, String purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, version, catalog_generation FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose, epoch);
        return rows.stream().findFirst();
    }

    public Optional<GrantRow> findLatestGrant(String subjectKey, String purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, version, catalog_generation FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose);
        return rows.stream().findFirst();
    }

    public List<GrantRow> findAllGrantsByPurpose(String purpose) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, status, version, catalog_generation FROM consent_grant"
                        + " WHERE purpose = ? ORDER BY subject_key, epoch",
                GRANT_MAPPER, purpose);
    }

    /**
     * 行级锁定绑定某用途的全部授权代次，用于迁移激活期间阻止并发授权/撤回/写入。
     */
    public List<GrantRow> lockAllGrantsByPurpose(String purpose) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, status, version, catalog_generation FROM consent_grant"
                        + " WHERE purpose = ? ORDER BY subject_key, epoch FOR UPDATE",
                GRANT_MAPPER, purpose);
    }

    public void insertGrant(String subjectKey, String purpose, int epoch, String requestId, long catalogGeneration) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id, catalog_generation)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                subjectKey, purpose, epoch, requestId, catalogGeneration);
    }

    /**
     * 仅当代次当前为 ACTIVE 时撤回；返回是否实际发生状态变更。
     */
    public boolean revokeGrant(String subjectKey, String purpose, int epoch) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                subjectKey, purpose, epoch);
        return updated > 0;
    }

    /**
     * 迁移激活：仅当授权仍为 ACTIVE 且版本未变时置为 MIGRED；返回是否更新成功。
     */
    public boolean markGrantMigrated(String subjectKey, String purpose, int epoch, int expectedVersion) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'MIGRATED', migrated_at = CURRENT_TIMESTAMP,"
                        + " version = version + 1"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND status = 'ACTIVE' AND version = ?",
                subjectKey, purpose, epoch, expectedVersion);
        return updated > 0;
    }

    public Optional<RecordRow> findRecord(String subjectKey, String purpose, int epoch, String recordKey) {
        List<RecordRow> rows = jdbc.query(recordColumns() + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose, epoch, recordKey);
        return rows.stream().findFirst();
    }

    public List<RecordRow> findAllRecordsByPurpose(String purpose) {
        return jdbc.query(recordColumns() + " FROM consent_record WHERE purpose = ?"
                        + " ORDER BY subject_key, epoch, record_key",
                RECORD_MAPPER, purpose);
    }

    /**
     * 行级锁定绑定某用途的全部数据记录，迁移激活期间阻止并发写入与改绑。
     */
    public List<RecordRow> lockAllRecordsByPurpose(String purpose) {
        return jdbc.query(recordColumns() + " FROM consent_record WHERE purpose = ?"
                        + " ORDER BY subject_key, epoch, record_key FOR UPDATE",
                RECORD_MAPPER, purpose);
    }

    public void insertRecord(String subjectKey, String purpose, int epoch, String recordKey, String payload,
                      long recordAttribute, String requestId, long catalogGeneration) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload,"
                        + " record_attribute, request_id, catalog_generation)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose, epoch, recordKey, payload, recordAttribute, requestId, catalogGeneration);
    }

    /**
     * 迁移激活：把可迁移记录一次性改绑到新用途、新授权代次并升至新目录代次；
     * 仅当记录仍在旧用途且版本未变时成功，返回是否更新成功。
     */
    public boolean rebindRecord(String subjectKey, String oldPurpose, int oldEpoch, String recordKey,
                         int expectedVersion, String newPurpose, int newEpoch, long newCatalogGeneration) {
        int updated = jdbc.update(
                "UPDATE consent_record SET purpose = ?, epoch = ?, catalog_generation = ?, version = version + 1"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " AND version = ?",
                newPurpose, newEpoch, newCatalogGeneration, subjectKey, oldPurpose, oldEpoch, recordKey,
                expectedVersion);
        return updated > 0;
    }

    private static String recordColumns() {
        return "SELECT subject_key, purpose, epoch, record_key, payload, record_attribute, version,"
                + " catalog_generation";
    }
}
