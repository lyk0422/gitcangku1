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

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("expires_at").toInstant());

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> {
        Timestamp evaluated = rs.getTimestamp("evaluated_at");
        return new RecordRow(
                rs.getString("subject_key"),
                Purpose.valueOf(rs.getString("purpose")),
                rs.getInt("epoch"),
                rs.getString("caller_key"),
                rs.getString("record_key"),
                rs.getString("payload"),
                rs.getString("delegation_path"),
                evaluated == null ? null : evaluated.toInstant());
    };

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
     * @param expiresAt  到期时刻（UTC），到期后状态不变但不再视为有效
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status, Instant expiresAt) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey     主体标识
     * @param purpose        用途
     * @param epoch          所属代次
     * @param callerKey      实际写入方标识
     * @param recordKey      记录键
     * @param payload        记录内容
     * @param delegationPath 写入依据快照（JSON 文本），主体直写为 null
     * @param evaluatedAt    评估时刻（UTC），主体直写为 null
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String callerKey,
                            String recordKey, String payload, String delegationPath, Instant evaluatedAt) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, expires_at FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<GrantRow> findLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, expires_at FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 在当前事务内锁定指定代次行，供撤销委托边时与授权撤回按同一把锁串行化。
     */
    Optional<GrantRow> findGrantForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, expires_at FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 在当前事务内锁定最新代次行，使撤回/续建/授权与写入并发按行锁提交顺序串行化，
     * 任何一方都不能基于过期快照穿透。调用方必须处于事务中。
     */
    Optional<GrantRow> findLatestGrantForUpdate(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, expires_at FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1 FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    void insertGrant(String subjectKey, Purpose purpose, int epoch, Instant expiresAt, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, expires_at, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                subjectKey, purpose.name(), epoch, Timestamp.from(expiresAt), requestId);
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
                "SELECT subject_key, purpose, epoch, caller_key, record_key, payload, delegation_path, evaluated_at"
                        + " FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String callerKey,
                      String recordKey, String payload, String delegationPath,
                      Instant evaluatedAt, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, caller_key, record_key, payload,"
                        + " delegation_path, evaluated_at, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, callerKey, recordKey, payload,
                delegationPath, evaluatedAt == null ? null : Timestamp.from(evaluatedAt), requestId);
    }
}
