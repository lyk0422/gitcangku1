package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代次、子范围与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")));

    private static final RowMapper<ScopeRow> SCOPE_MAPPER = (rs, rowNum) -> new ScopeRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("scope_key"),
            rs.getString("label"),
            ScopeStatus.valueOf(rs.getString("status")));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("scope_key"),
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
     * @param status     状态：ACTIVE 有效 / REVOKED 已整体撤回
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status) {
    }

    /**
     * 子范围行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param scopeKey   子范围标识；默认子范围不落表
     * @param label      非空标签
     * @param status     状态：ACTIVE 有效 / REVOKED 已独立撤回
     */
    public record ScopeRow(String subjectKey, Purpose purpose, int epoch,
                           String scopeKey, String label, ScopeStatus status) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param scopeKey   所属子范围标识；null 表示默认子范围
     * @param recordKey  记录键
     * @param payload    记录内容
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch,
                            String scopeKey, String recordKey, String payload) {
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
     * 读取代次并持有行锁（SELECT ... FOR UPDATE），用于事务内与整体撤回按提交顺序互斥。
     */
    Optional<GrantRow> findGrantForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    void insertGrant(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, requestId);
    }

    /**
     * 仅当代次当前为 ACTIVE 时整体撤回；返回是否实际发生状态变更。
     */
    boolean revokeGrant(String subjectKey, Purpose purpose, int epoch) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    Optional<ScopeRow> findScope(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        List<ScopeRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, label, status FROM consent_scope"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND scope_key = ?",
                SCOPE_MAPPER, subjectKey, purpose.name(), epoch, scopeKey);
        return rows.stream().findFirst();
    }

    /**
     * 读取子范围并持有行锁（SELECT ... FOR UPDATE），用于写入事务与子范围独立撤回按提交顺序互斥。
     */
    Optional<ScopeRow> findScopeForUpdate(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        List<ScopeRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, label, status FROM consent_scope"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND scope_key = ? FOR UPDATE",
                SCOPE_MAPPER, subjectKey, purpose.name(), epoch, scopeKey);
        return rows.stream().findFirst();
    }

    /**
     * 列出某代次下全部已创建的子范围（不含默认子范围），按 scope_key 排序保证结果稳定。
     */
    List<ScopeRow> listScopes(String subjectKey, Purpose purpose, int epoch) {        return jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, label, status FROM consent_scope"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY scope_key",
                SCOPE_MAPPER, subjectKey, purpose.name(), epoch);
    }

    void insertScope(String subjectKey, Purpose purpose, int epoch,
                     String scopeKey, String label, String requestId) {
        jdbc.update(
                "INSERT INTO consent_scope (subject_key, purpose, epoch, scope_key, label, status, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, scopeKey, label, requestId);
    }

    /**
     * 仅当子范围当前为 ACTIVE 时独立撤回；返回是否实际发生状态变更。
     */
    boolean revokeScope(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        int updated = jdbc.update(
                "UPDATE consent_scope SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND scope_key = ? AND status = 'ACTIVE'",
                subjectKey, purpose.name(), epoch, scopeKey);
        return updated > 0;
    }

    Optional<RecordRow> findRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, record_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 列出某代次下全部记录（含所有子范围与默认子范围），已撤回记录不做过滤，按 record_key 排序。
     */
    List<RecordRow> listRecordsByEpoch(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, scope_key, record_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY record_key",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch);
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String scopeKey,
                      String recordKey, String payload, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, scope_key, record_key, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, scopeKey, recordKey, payload, requestId);
    }
}
