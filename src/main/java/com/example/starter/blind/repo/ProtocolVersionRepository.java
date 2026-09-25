package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 协议版本数据访问；待生效唯一性由 (experiment_id, pending_key) 唯一索引保证。
 * 版本状态机：PENDING -> EFFECTIVE -> SUPERSEDED；PENDING -> REVOKED（记录保留）。
 */
@Repository
public class ProtocolVersionRepository {

    /** 协议版本行。pendingKey 为待生效去重列：PENDING 时等于 experimentId，其余状态为 null。 */
    public record ProtocolVersionRow(
            String experimentId,
            int version,
            int ratioA,
            int ratioB,
            long effectiveAt,
            String status,
            String createdBy,
            long createdAt,
            Long revokedAt,
            String revokedBy,
            Long supersededAt,
            String pendingKey) {
    }

    private static final RowMapper<ProtocolVersionRow> MAPPER = (rs, n) -> new ProtocolVersionRow(
            rs.getString("experiment_id"),
            rs.getInt("version"),
            rs.getInt("ratio_a"),
            rs.getInt("ratio_b"),
            rs.getLong("effective_at"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getLong("created_at"),
            (Long) rs.getObject("revoked_at"),
            rs.getString("revoked_by"),
            (Long) rs.getObject("superseded_at"),
            rs.getString("pending_key"));

    private static final String COLUMNS =
            "experiment_id, version, ratio_a, ratio_b, effective_at, status, created_by, created_at, "
                    + "revoked_at, revoked_by, superseded_at, pending_key";

    private final JdbcTemplate jdbc;

    public ProtocolVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入版本行；PENDING 携带 pending_key=experimentId 参与唯一约束，其它状态 pending_key 为 NULL。 */
    public void insert(ProtocolVersionRow row) {
        jdbc.update("INSERT INTO protocol_version ("
                        + "experiment_id, version, ratio_a, ratio_b, effective_at, status, created_by, "
                        + "created_at, revoked_at, revoked_by, superseded_at, pending_key"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.version(), row.ratioA(), row.ratioB(), row.effectiveAt(),
                row.status(), row.createdBy(), row.createdAt(), row.revokedAt(), row.revokedBy(),
                row.supersededAt(), row.pendingKey());
    }

    public ProtocolVersionRow find(String experimentId, int version) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND version = ?",
                MAPPER, experimentId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定版本行，生效/撤销时串行化。 */
    public ProtocolVersionRow lock(String experimentId, int version) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND version = ? FOR UPDATE",
                MAPPER, experimentId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ProtocolVersionRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? ORDER BY version",
                MAPPER, experimentId);
    }

    /** 当前有效版本（status=EFFECTIVE）；不存在返回 null。 */
    public ProtocolVersionRow findEffective(String experimentId) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND status = 'EFFECTIVE'",
                MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定当前有效版本。 */
    public ProtocolVersionRow lockEffective(String experimentId) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND status = 'EFFECTIVE' FOR UPDATE",
                MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 唯一待生效版本；不存在返回 null。 */
    public ProtocolVersionRow findPending(String experimentId) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND status = 'PENDING'",
                MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countPending(String experimentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM protocol_version WHERE experiment_id = ? AND status = 'PENDING'",
                Long.class, experimentId);
        return count == null ? 0 : count;
    }

    /**
     * 生效：仅 PENDING 可转为 EFFECTIVE，并释放待生效去重列。
     *
     * @return 受影响行数；0 表示版本不存在或状态不符
     */
    public int markEffective(String experimentId, int version) {
        return jdbc.update(
                "UPDATE protocol_version SET status = 'EFFECTIVE', pending_key = NULL "
                        + "WHERE experiment_id = ? AND version = ? AND status = 'PENDING'",
                experimentId, version);
    }

    /**
     * 旧版本归 SUPERSEDED：仅当前 EFFECTIVE 可被取代。
     *
     * @return 受影响行数
     */
    public int markSuperseded(String experimentId, int version, long supersededAt) {
        return jdbc.update(
                "UPDATE protocol_version SET status = 'SUPERSEDED', superseded_at = ? "
                        + "WHERE experiment_id = ? AND version = ? AND status = 'EFFECTIVE'",
                supersededAt, experimentId, version);
    }

    /**
     * 撤销未生效修订：仅 PENDING 可撤销，记录保留并释放待生效去重列。
     *
     * @return 受影响行数；0 表示版本不存在或已生效
     */
    public int markRevoked(String experimentId, int version, long revokedAt, String revokedBy) {
        return jdbc.update(
                "UPDATE protocol_version SET status = 'REVOKED', pending_key = NULL, "
                        + "revoked_at = ?, revoked_by = ? "
                        + "WHERE experiment_id = ? AND version = ? AND status = 'PENDING'",
                revokedAt, revokedBy, experimentId, version);
    }

    /** 插入待生效版本时若已存在 PENDING，唯一索引 uq_protocol_pending 触发异常。 */
    public boolean isDuplicatePending(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_protocol_pending");
    }
}
