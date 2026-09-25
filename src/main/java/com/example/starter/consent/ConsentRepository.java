package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代次、子范围与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 *
 * <p>写入、子范围创建与撤回路径使用 SELECT ... FOR UPDATE 行锁读取授权代次与子范围行，
 * 使并发操作按数据库事务提交顺序裁决。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("scope_key"),
            rs.getString("payload"));

    private static final RowMapper<ScopeRow> SCOPE_MAPPER = (rs, rowNum) -> new ScopeRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("scope_key"),
            rs.getString("label"),
            GrantStatus.valueOf(rs.getString("status")));

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
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param recordKey  记录键
     * @param scopeKey   所属子范围标识，default 表示默认子范围
     * @param payload    记录内容
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch,
                            String recordKey, String scopeKey, String payload) {
    }

    /**
     * 子范围行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param scopeKey   子范围标识，同一 epoch 内唯一
     * @param label      子范围标签
     * @param status     状态：ACTIVE 有效 / REVOKED 已撤回
     */
    public record ScopeRow(String subjectKey, Purpose purpose, int epoch,
                           String scopeKey, String label, GrantStatus status) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<GrantRow> findLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 行锁读取指定代次：与整体撤回的 UPDATE 互斥，按事务提交顺序裁决。
     */
    Optional<GrantRow> findGrantForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 行锁读取最新代次：与整体撤回的 UPDATE 互斥，按事务提交顺序裁决。
     */
    Optional<GrantRow> findLatestGrantForUpdate(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
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
                "SELECT subject_key, purpose, epoch, record_key, scope_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 按代次列出全部记录（含已撤回子范围的记录），供聚合查询标明可用性。
     */
    List<RecordRow> listRecords(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, scope_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY record_key",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch);
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey,
                      String scopeKey, String payload, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, scope_key, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, scopeKey, payload, requestId);
    }

    Optional<ScopeRow> findScope(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        List<ScopeRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, label, status FROM consent_scope"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND scope_key = ?",
                SCOPE_MAPPER, subjectKey, purpose.name(), epoch, scopeKey);
        return rows.stream().findFirst();
    }

    /**
     * 行锁读取子范围：与子范围撤回的 UPDATE 互斥，按事务提交顺序裁决。
     */
    Optional<ScopeRow> findScopeForUpdate(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        List<ScopeRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, label, status FROM consent_scope"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND scope_key = ? FOR UPDATE",
                SCOPE_MAPPER, subjectKey, purpose.name(), epoch, scopeKey);
        return rows.stream().findFirst();
    }

    /**
     * 按代次列出全部显式子范围（不含默认子范围）。
     */
    List<ScopeRow> listScopes(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, label, status FROM consent_scope"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY scope_key",
                SCOPE_MAPPER, subjectKey, purpose.name(), epoch);
    }

    void insertScope(String subjectKey, Purpose purpose, int epoch, String scopeKey,
                     String label, String requestId) {
        jdbc.update(
                "INSERT INTO consent_scope (subject_key, purpose, epoch, scope_key, label, status, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, scopeKey, label, requestId);
    }

    /**
     * 仅当子范围当前为 ACTIVE 时撤回；返回是否实际发生状态变更。
     */
    boolean revokeScope(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        int updated = jdbc.update(
                "UPDATE consent_scope SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND scope_key = ?"
                        + " AND status = 'ACTIVE'",
                subjectKey, purpose.name(), epoch, scopeKey);
        return updated > 0;
    }
}
