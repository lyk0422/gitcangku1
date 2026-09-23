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

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"),
            rs.getString("delegation_path"),
            rs.getString("edge_versions"),
            rs.getTimestamp("evaluated_at").toInstant());

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
     * @param status     状态：ACTIVE 有效 / REVOKED 已撤回（到期不改状态）
     * @param expiresAt  授权到期时刻（UTC），当前时刻大于等于该值即视为到期
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch,
                           GrantStatus status, Instant expiresAt) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey     主体标识
     * @param purpose        用途
     * @param epoch          所属代次
     * @param recordKey      记录键
     * @param payload        记录内容
     * @param delegationPath 写入时使用的处理方键有序链 JSON
     * @param edgeVersions   写入时使用的委托链各边版本 JSON
     * @param evaluatedAt    同一事务快照内的授权评估时刻（UTC）
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload,
                            String delegationPath, String edgeVersions, Instant evaluatedAt) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, expires_at FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 锁定授权代次行（悲观写锁）：使授权撤回、委托变更与写入按持有该锁的提交顺序串行化。
     */
    Optional<GrantRow> findGrantForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, expires_at FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
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
                "SELECT subject_key, purpose, epoch, record_key, payload,"
                        + " delegation_path, edge_versions, evaluated_at FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload,
                      String delegationPath, String edgeVersions, Instant evaluatedAt, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload,"
                        + " delegation_path, edge_versions, evaluated_at, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload,
                delegationPath, edgeVersions, Timestamp.from(evaluatedAt), requestId);
    }
}
