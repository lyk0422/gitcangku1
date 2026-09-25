package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代理委托的持久化访问：委托主表、版本表与批次查询快照，所有查询使用参数化 SQL。
 */
@Repository
public class DelegateRepository {

    private static final RowMapper<DelegateRow> DELEGATE_MAPPER = (rs, rowNum) -> new DelegateRow(
            rs.getString("delegate_key"),
            rs.getString("subject_key"),
            rs.getString("agent_key"),
            rs.getInt("current_version"),
            DelegateStatus.valueOf(rs.getString("status")),
            rs.getString("fingerprint"));

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, rowNum) -> new VersionRow(
            rs.getInt("delegate_version"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getLong("valid_from_ms"),
            rs.getLong("valid_to_ms"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("query_id"),
            rs.getString("agent_key"),
            rs.getString("purposes"));

    private static final RowMapper<SnapshotSubjectRow> SNAPSHOT_SUBJECT_MAPPER = (rs, rowNum) -> new SnapshotSubjectRow(
            rs.getString("subject_key"),
            rs.getString("delegate_key"),
            rs.getInt("delegate_version"),
            rs.getString("grant_epochs"));

    private final JdbcTemplate jdbc;

    public DelegateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 委托主表行。
     *
     * @param delegateKey    委托键（幂等键）
     * @param subjectKey     数据主体标识
     * @param agentKey       代理人标识
     * @param currentVersion 当前委托版本，从 1 开始
     * @param status         状态：ACTIVE 有效 / REVOKED 已撤销
     * @param fingerprint    规范化参数指纹：主体|代理|授权代次|规范化用途|UTC区间|版本
     */
    public record DelegateRow(String delegateKey, String subjectKey, String agentKey,
                              int currentVersion, DelegateStatus status, String fingerprint) {
    }

    /**
     * 委托版本行：逐用途固化绑定的授权代次与 UTC 左闭右开有效期。
     *
     * @param delegateVersion 委托版本
     * @param purpose         委托用途
     * @param epoch           绑定的该用途授权代次
     * @param validFromMs     有效期起（UTC epoch 毫秒，左闭）
     * @param validToMs       有效期止（UTC epoch 毫秒，右开）
     */
    public record VersionRow(int delegateVersion, Purpose purpose, int epoch,
                             long validFromMs, long validToMs) {
    }

    /**
     * 批次查询快照行。
     *
     * @param queryId  批次查询标识
     * @param agentKey 代理人标识
     * @param purposes 规范化请求用途集合，逗号分隔
     */
    public record SnapshotRow(String queryId, String agentKey, String purposes) {
    }

    /**
     * 批次查询快照主体行。
     *
     * @param subjectKey      主体标识
     * @param delegateKey     命中的委托键
     * @param delegateVersion 固化的委托版本
     * @param grantEpochs     固化的授权代次，格式 PURPOSE=epoch 逗号分隔
     */
    public record SnapshotSubjectRow(String subjectKey, String delegateKey,
                                     int delegateVersion, String grantEpochs) {
    }

    Optional<DelegateRow> findDelegate(String delegateKey) {
        List<DelegateRow> rows = jdbc.query(
                "SELECT delegate_key, subject_key, agent_key, current_version, status, fingerprint"
                        + " FROM consent_delegate WHERE delegate_key = ?",
                DELEGATE_MAPPER, delegateKey);
        return rows.stream().findFirst();
    }

    /**
     * 按“主体＋代理”查找全部委托，按委托键排序保证裁决顺序稳定。
     */
    List<DelegateRow> findDelegatesFor(String subjectKey, String agentKey) {
        return jdbc.query(
                "SELECT delegate_key, subject_key, agent_key, current_version, status, fingerprint"
                        + " FROM consent_delegate WHERE subject_key = ? AND agent_key = ?"
                        + " ORDER BY delegate_key",
                DELEGATE_MAPPER, subjectKey, agentKey);
    }

    void insertDelegate(String delegateKey, String subjectKey, String agentKey, String fingerprint) {
        jdbc.update(
                "INSERT INTO consent_delegate (delegate_key, subject_key, agent_key, current_version, status, fingerprint)"
                        + " VALUES (?, ?, ?, 1, 'ACTIVE', ?)",
                delegateKey, subjectKey, agentKey, fingerprint);
    }

    /**
     * 仅当当前版本与状态符合预期时推进版本（乐观并发控制）；返回是否实际生效。
     */
    boolean advanceVersion(String delegateKey, int expectedVersion) {
        int updated = jdbc.update(
                "UPDATE consent_delegate SET current_version = current_version + 1"
                        + " WHERE delegate_key = ? AND current_version = ? AND status = 'ACTIVE'",
                delegateKey, expectedVersion);
        return updated > 0;
    }

    /**
     * 仅当委托当前有效时撤销；返回是否实际发生状态变更。
     */
    boolean revokeDelegate(String delegateKey) {
        int updated = jdbc.update(
                "UPDATE consent_delegate SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE delegate_key = ? AND status = 'ACTIVE'",
                delegateKey);
        return updated > 0;
    }

    void insertVersion(String delegateKey, int delegateVersion, Purpose purpose, int epoch,
                       long validFromMs, long validToMs) {
        jdbc.update(
                "INSERT INTO consent_delegate_version"
                        + " (delegate_key, delegate_version, purpose, epoch, valid_from_ms, valid_to_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                delegateKey, delegateVersion, purpose.name(), epoch, validFromMs, validToMs);
    }

    List<VersionRow> findVersion(String delegateKey, int delegateVersion) {
        return jdbc.query(
                "SELECT delegate_version, purpose, epoch, valid_from_ms, valid_to_ms"
                        + " FROM consent_delegate_version"
                        + " WHERE delegate_key = ? AND delegate_version = ? ORDER BY purpose",
                VERSION_MAPPER, delegateKey, delegateVersion);
    }

    List<VersionRow> findAllVersions(String delegateKey) {
        return jdbc.query(
                "SELECT delegate_version, purpose, epoch, valid_from_ms, valid_to_ms"
                        + " FROM consent_delegate_version"
                        + " WHERE delegate_key = ? ORDER BY delegate_version, purpose",
                VERSION_MAPPER, delegateKey);
    }

    void insertSnapshot(String queryId, String agentKey, String purposes) {
        jdbc.update(
                "INSERT INTO delegate_query_snapshot (query_id, agent_key, purposes) VALUES (?, ?, ?)",
                queryId, agentKey, purposes);
    }

    void insertSnapshotSubject(String queryId, String subjectKey, String delegateKey,
                               int delegateVersion, String grantEpochs) {
        jdbc.update(
                "INSERT INTO delegate_query_snapshot_subject"
                        + " (query_id, subject_key, delegate_key, delegate_version, grant_epochs)"
                        + " VALUES (?, ?, ?, ?, ?)",
                queryId, subjectKey, delegateKey, delegateVersion, grantEpochs);
    }

    Optional<SnapshotRow> findSnapshot(String queryId) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT query_id, agent_key, purposes FROM delegate_query_snapshot WHERE query_id = ?",
                SNAPSHOT_MAPPER, queryId);
        return rows.stream().findFirst();
    }

    List<SnapshotSubjectRow> findSnapshotSubjects(String queryId) {
        return jdbc.query(
                "SELECT subject_key, delegate_key, delegate_version, grant_epochs"
                        + " FROM delegate_query_snapshot_subject WHERE query_id = ? ORDER BY subject_key",
                SNAPSHOT_SUBJECT_MAPPER, queryId);
    }
}
