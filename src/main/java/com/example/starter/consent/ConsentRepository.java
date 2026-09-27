package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代次与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class ConsentRepository {

    private static final String GRANT_COLUMNS =
            "subject_key, purpose, epoch, status, region_code, consent_version";

    private static final String RECORD_COLUMNS =
            "subject_key, purpose, epoch, record_key, payload, region_code, erased_at";

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getString("region_code"),
            rs.getInt("consent_version"));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> {
        Timestamp erasedAt = rs.getTimestamp("erased_at");
        return new RecordRow(
                rs.getString("subject_key"),
                Purpose.valueOf(rs.getString("purpose")),
                rs.getInt("epoch"),
                rs.getString("record_key"),
                rs.getString("payload"),
                rs.getString("region_code"),
                erasedAt == null ? null : erasedAt.toInstant());
    };

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权代次行。
     *
     * @param subjectKey     主体标识
     * @param purpose        用途
     * @param epoch          代次，从 1 开始
     * @param status         状态：ACTIVE 有效 / REVOKED 已撤回
     * @param regionCode     本代驻留区域代码，NULL 表示尚未绑定区域
     * @param consentVersion 授权版本：直接授权为 1，迁移产生的新代为源代版本+1
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status,
                           String regionCode, int consentVersion) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param recordKey  记录键
     * @param payload    记录内容；已清除为 NULL
     * @param regionCode 写入时的驻留区域路由；所属代次未绑定区域时为 NULL
     * @param erasedAt   数据清除时间（UTC），未清除为 NULL
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String recordKey,
                            String payload, String regionCode, Instant erasedAt) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<GrantRow> findLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 查询主体在指定用途下的全部授权代次，按代次升序。
     */
    List<GrantRow> findGrants(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch",
                GRANT_MAPPER, subjectKey, purpose.name());
    }

    /**
     * 查询最新代次并加行级写锁（SELECT ... FOR UPDATE），用于迁移按提交顺序裁决。
     */
    Optional<GrantRow> findLatestGrantForUpdate(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1 FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    void insertGrant(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, requestId);
    }

    /**
     * 迁移产生的新代授权：授权版本为源代版本+1，驻留区域为目标区域。
     */
    void insertMigratedGrant(String subjectKey, Purpose purpose, int epoch,
                             int consentVersion, String regionCode, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, region_code,"
                        + " consent_version, request_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)",
                subjectKey, purpose.name(), epoch, regionCode, consentVersion, requestId);
    }

    /**
     * 仅当代次尚未绑定区域时绑定驻留区域；返回是否实际发生绑定。
     */
    boolean bindRegion(String subjectKey, Purpose purpose, int epoch, String regionCode) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET region_code = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND region_code IS NULL",
                regionCode, subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    /**
     * 仅当代次当前为 ACTIVE 时撤回；返回是否实际发生状态变更。
     */
    boolean revokeGrant(String subjectKey, Purpose purpose, int epoch) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    Optional<RecordRow> findRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT " + RECORD_COLUMNS + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 按记录键集合批量查询指定代次内的记录。
     */
    List<RecordRow> findRecords(String subjectKey, Purpose purpose, int epoch, List<String> recordKeys) {
        StringJoiner placeholders = new StringJoiner(", ");
        for (int i = 0; i < recordKeys.size(); i++) {
            placeholders.add("?");
        }
        Object[] args = new Object[recordKeys.size() + 3];
        args[0] = subjectKey;
        args[1] = purpose.name();
        args[2] = epoch;
        for (int i = 0; i < recordKeys.size(); i++) {
            args[3 + i] = recordKeys.get(i);
        }
        return jdbc.query(
                "SELECT " + RECORD_COLUMNS + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND record_key IN (" + placeholders + ")",
                RECORD_MAPPER, args);
    }

    /**
     * 跨全部代次查询同一记录键的所有记录（用于数据清除的存在性与状态判断）。
     */
    List<RecordRow> findRecordsAllEpochs(String subjectKey, Purpose purpose, String recordKey) {
        return jdbc.query(
                "SELECT " + RECORD_COLUMNS + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), recordKey);
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey,
                      String payload, String regionCode, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload,"
                        + " region_code, request_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload, regionCode, requestId);
    }

    /**
     * 数据清除：将同一记录键在所有代次中尚未清除的记录内容置 NULL 并标记清除时间（UTC）；
     * 返回实际清除的记录数。
     */
    int eraseRecords(String subjectKey, Purpose purpose, String recordKey, Instant erasedAt) {
        return jdbc.update(
                "UPDATE consent_record SET payload = NULL, erased_at = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND record_key = ? AND erased_at IS NULL",
                Timestamp.from(erasedAt), subjectKey, purpose.name(), recordKey);
    }

    /**
     * 统计指定代次记录总数。
     */
    int countRecords(String subjectKey, Purpose purpose, int epoch) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                Integer.class, subjectKey, purpose.name(), epoch);
        return count == null ? 0 : count;
    }

    /**
     * 统计指定代次已清除记录数。
     */
    int countErasedRecords(String subjectKey, Purpose purpose, int epoch) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND erased_at IS NOT NULL",
                Integer.class, subjectKey, purpose.name(), epoch);
        return count == null ? 0 : count;
    }

    /**
     * 查询指定代次最近一条记录的创建时间（UTC）；无记录时为空。
     */
    Optional<Instant> findLatestRecordCreatedAt(String subjectKey, Purpose purpose, int epoch) {
        List<Timestamp> rows = jdbc.query(
                "SELECT created_at FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " ORDER BY created_at DESC, record_key LIMIT 1",
                (rs, rowNum) -> rs.getTimestamp("created_at"),
                subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst().map(Timestamp::toInstant);
    }
}
