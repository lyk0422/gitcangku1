package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 委托边持久化访问。委托边按“主体＋用途＋代次”隔离，版本从 1 开始，
 * 撤销与到期续建产生新版本行；所有查询使用参数化 SQL。
 */
@Repository
public class DelegationRepository {

    private static final String COLUMNS =
            "delegation_key, subject_key, purpose, epoch, delegator_key, processor_key,"
                    + " version, status, expires_at";

    private static final RowMapper<DelegationRow> MAPPER = (rs, rowNum) -> new DelegationRow(
            rs.getString("delegation_key"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("delegator_key"),
            rs.getString("processor_key"),
            rs.getInt("version"),
            DelegationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());

    private final JdbcTemplate jdbc;

    public DelegationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 委托边行。
     *
     * @param delegationKey 委托边业务唯一键
     * @param subjectKey    授权主体标识
     * @param purpose       用途
     * @param epoch         所属授权代次
     * @param delegatorKey  委托方标识
     * @param processorKey  受托处理方标识
     * @param version       边版本，从 1 开始
     * @param status        状态：ACTIVE 有效 / REVOKED 已撤销（到期不改状态）
     * @param expiresAt     委托到期时刻（UTC）
     * @param revokedAt     撤销时刻（UTC），未撤销为 null
     */
    public record DelegationRow(String delegationKey, String subjectKey, Purpose purpose, int epoch,
                                String delegatorKey, String processorKey, int version,
                                DelegationStatus status, Instant expiresAt, Instant revokedAt) {
    }

    Optional<DelegationRow> findByKey(String delegationKey) {
        List<DelegationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + ", revoked_at FROM consent_delegation WHERE delegation_key = ?",
                MAPPER, delegationKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键锁定边行；调用方须已持有对应授权代次行锁，保证加锁顺序一致。
     */
    Optional<DelegationRow> findByKeyForUpdate(String delegationKey) {
        List<DelegationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + ", revoked_at FROM consent_delegation"
                        + " WHERE delegation_key = ? FOR UPDATE",
                MAPPER, delegationKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询某条有向边的指定版本行（含 REVOKED 版本）。
     */
    Optional<DelegationRow> findEdge(String subjectKey, Purpose purpose, int epoch,
                                     String delegatorKey, String processorKey, int version) {
        List<DelegationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + ", revoked_at FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND delegator_key = ? AND processor_key = ? AND version = ?",
                MAPPER, subjectKey, purpose.name(), epoch, delegatorKey, processorKey, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询某条有向边的最新版本行（不论状态）。
     */
    Optional<DelegationRow> findLatestEdge(String subjectKey, Purpose purpose, int epoch,
                                           String delegatorKey, String processorKey) {
        List<DelegationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + ", revoked_at FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND delegator_key = ? AND processor_key = ? ORDER BY version DESC LIMIT 1",
                MAPPER, subjectKey, purpose.name(), epoch, delegatorKey, processorKey);
        return rows.stream().findFirst();
    }

    /**
     * 加载某一代次全部 ACTIVE 状态的边并按主键顺序加锁，用于同一快照内的图计算
     * （环、深度、最短路径）。统一在授权代次行锁之后调用，保证加锁顺序确定。
     */
    List<DelegationRow> findActiveEdgesForUpdate(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT " + COLUMNS + ", revoked_at FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'"
                        + " ORDER BY id FOR UPDATE",
                MAPPER, subjectKey, purpose.name(), epoch);
    }

    /**
     * 只读加载某一代次全部 ACTIVE 状态的边，用于有效链查询。
     */
    List<DelegationRow> findActiveEdges(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT " + COLUMNS + ", revoked_at FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                MAPPER, subjectKey, purpose.name(), epoch);
    }

    int nextVersion(String subjectKey, Purpose purpose, int epoch,
                    String delegatorKey, String processorKey) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version) FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND delegator_key = ? AND processor_key = ?",
                Integer.class, subjectKey, purpose.name(), epoch, delegatorKey, processorKey);
        return max == null ? 1 : max + 1;
    }

    void insert(DelegationRow row, String requestId) {
        jdbc.update(
                "INSERT INTO consent_delegation (delegation_key, subject_key, purpose, epoch,"
                        + " delegator_key, processor_key, version, status, expires_at, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                row.delegationKey(), row.subjectKey(), row.purpose().name(), row.epoch(),
                row.delegatorKey(), row.processorKey(), row.version(),
                Timestamp.from(row.expiresAt()), requestId);
    }

    /**
     * 仅当边当前为 ACTIVE 时撤销；返回是否实际发生状态变更。
     */
    boolean revokeByKey(String delegationKey) {
        int updated = jdbc.update(
                "UPDATE consent_delegation SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE delegation_key = ? AND status = 'ACTIVE'",
                delegationKey);
        return updated > 0;
    }
}
