package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
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

    private static final String GRANT_COLUMNS = "subject_key, purpose, epoch, status, purged_at";

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            toInstant(rs.getTimestamp("purged_at")));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权代次行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      代次，从 1 开始
     * @param status     状态：ACTIVE 有效 / REVOKED 已撤回
     * @param purgedAt   物理清除时间（UTC），未清除为 null；清除后该代次数据不可恢复
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status, Instant purgedAt) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param recordKey  记录键
     * @param payload    记录内容
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 按主键锁定授权代次行（SELECT ... FOR UPDATE），用于序列化冻结创建与清除等并发操作。
     */
    Optional<GrantRow> findGrantForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT " + GRANT_COLUMNS + " FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
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

    void insertGrant(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, requestId);
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
                "SELECT subject_key, purpose, epoch, record_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload, requestId);
    }

    /**
     * 统计指定代次现存（未物理清除）记录数。
     */
    long countRecords(String subjectKey, Purpose purpose, int epoch) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                Long.class, subjectKey, purpose.name(), epoch);
        return count == null ? 0 : count;
    }

    /**
     * 物理删除指定代次全部记录，返回删除行数。
     */
    int deleteRecords(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.update(
                "DELETE FROM consent_record WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                subjectKey, purpose.name(), epoch);
    }

    /**
     * 标记代次数据已物理清除（UTC 时间戳），清除后该代次数据不可恢复。
     */
    void markPurged(String subjectKey, Purpose purpose, int epoch, Instant purgedAt) {
        jdbc.update(
                "UPDATE consent_grant SET purged_at = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                Timestamp.from(purgedAt), subjectKey, purpose.name(), epoch);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
