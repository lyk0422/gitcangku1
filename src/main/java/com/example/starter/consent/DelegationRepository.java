package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 委托边持久化访问：所有查询限定 subject/purpose/epoch 边界并使用参数化 SQL。
 *
 * <p>加锁查询 {@link #findActiveEdgesForUpdate} 按 delegation_key 排序锁定当代全部有效边，
 * 与最新授权行锁配合，使委托、撤边、续建与写入在同一授权域内按提交顺序串行化。
 */
@Repository
public class DelegationRepository {

    private static final RowMapper<DelegationRow> MAPPER = (rs, rowNum) -> new DelegationRow(
            rs.getString("delegation_key"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("from_key"),
            rs.getString("to_key"),
            rs.getInt("version"),
            DelegationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getString("request_id"));

    private final JdbcTemplate jdbc;

    public DelegationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 委托边行。
     *
     * @param delegationKey 全局唯一键
     * @param subjectKey    所属授权主体
     * @param purpose       所属用途
     * @param epoch         所属授权代次
     * @param fromKey       边起点
     * @param toKey         边终点
     * @param version       版本号，从 1 递增
     * @param status        状态：ACTIVE / REVOKED
     * @param expiresAt     到期时刻（UTC）
     * @param requestId     创建本边的幂等请求标识
     */
    public record DelegationRow(String delegationKey, String subjectKey, Purpose purpose, int epoch,
                                String fromKey, String toKey, int version, DelegationStatus status,
                                Instant expiresAt, String requestId) {
    }

    void insert(DelegationRow row) {
        jdbc.update(
                "INSERT INTO consent_delegation (delegation_key, subject_key, purpose, epoch, from_key, to_key,"
                        + " version, status, expires_at, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                row.delegationKey(), row.subjectKey(), row.purpose().name(), row.epoch(),
                row.fromKey(), row.toKey(), row.version(), Timestamp.from(row.expiresAt()), row.requestId());
    }

    Optional<DelegationRow> findByKey(String delegationKey) {
        List<DelegationRow> rows = jdbc.query(
                "SELECT delegation_key, subject_key, purpose, epoch, from_key, to_key, version, status,"
                        + " expires_at, request_id FROM consent_delegation WHERE delegation_key = ?",
                MAPPER, delegationKey);
        return rows.stream().findFirst();
    }

    /**
     * 锁定指定委托边行，供撤销流程在持有授权行锁后二次校验状态。
     */
    Optional<DelegationRow> findByKeyForUpdate(String delegationKey) {
        List<DelegationRow> rows = jdbc.query(
                "SELECT delegation_key, subject_key, purpose, epoch, from_key, to_key, version, status,"
                        + " expires_at, request_id FROM consent_delegation WHERE delegation_key = ? FOR UPDATE",
                MAPPER, delegationKey);
        return rows.stream().findFirst();
    }

    /**
     * 同一对起止点的最新版本（含已撤销版本），用于计算续建版本号。
     */
    Optional<DelegationRow> findLatestByEndpoints(String subjectKey, Purpose purpose, int epoch,
                                                  String fromKey, String toKey) {
        List<DelegationRow> rows = jdbc.query(
                "SELECT delegation_key, subject_key, purpose, epoch, from_key, to_key, version, status,"
                        + " expires_at, request_id FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND from_key = ? AND to_key = ?"
                        + " ORDER BY version DESC LIMIT 1",
                MAPPER, subjectKey, purpose.name(), epoch, fromKey, toKey);
        return rows.stream().findFirst();
    }

    /**
     * 当代全部有效边（只读快照），供有效链查询做最短路径计算。
     */
    List<DelegationRow> findActiveEdges(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT delegation_key, subject_key, purpose, epoch, from_key, to_key, version, status,"
                        + " expires_at, request_id FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'"
                        + " ORDER BY delegation_key",
                MAPPER, subjectKey, purpose.name(), epoch);
    }

    /**
     * 在当前事务内锁定当代全部有效边（按 delegation_key 排序以避免死锁），
     * 供委托与写入在同一快照下做图校验，阻止撤边/续建并发穿透。
     */
    List<DelegationRow> findActiveEdgesForUpdate(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT delegation_key, subject_key, purpose, epoch, from_key, to_key, version, status,"
                        + " expires_at, request_id FROM consent_delegation"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'"
                        + " ORDER BY delegation_key FOR UPDATE",
                MAPPER, subjectKey, purpose.name(), epoch);
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
