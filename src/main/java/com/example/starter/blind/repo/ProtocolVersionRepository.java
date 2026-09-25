package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 盲法协议版本数据访问；同一实验至多一条 PENDING 由 pending_dedup 唯一列保证。
 * 时间列均为 Unix 毫秒 UTC。
 */
@Repository
public class ProtocolVersionRepository {

    /** 协议版本行；effectiveEventAt/revokedAt 为 null 表示尚未生效/撤销。 */
    public record ProtocolVersionRow(
            String experimentId,
            int versionNo,
            int ratioA,
            int ratioB,
            long effectiveAt,
            String status,
            String createdByActor,
            long createdAt,
            Long effectiveEventAt,
            Long revokedAt) {
    }

    private static final RowMapper<ProtocolVersionRow> MAPPER = (rs, n) -> new ProtocolVersionRow(
            rs.getString("experiment_id"),
            rs.getInt("version_no"),
            rs.getInt("ratio_a"),
            rs.getInt("ratio_b"),
            rs.getLong("effective_at"),
            rs.getString("status"),
            rs.getString("created_by_actor"),
            rs.getLong("created_at"),
            (Long) rs.getObject("effective_event_at"),
            (Long) rs.getObject("revoked_at"));

    private static final String COLUMNS =
            "experiment_id, version_no, ratio_a, ratio_b, effective_at, status, "
                    + "created_by_actor, created_at, effective_event_at, revoked_at";

    private final JdbcTemplate jdbc;

    public ProtocolVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入协议版本；PENDING 时写入实验范围去重常量，生效/撤销后置空。
     */
    public void insert(ProtocolVersionRow row) {
        boolean pending = "PENDING".equals(row.status());
        jdbc.update("INSERT INTO protocol_version ("
                        + "experiment_id, version_no, ratio_a, ratio_b, effective_at, status, "
                        + "created_by_actor, created_at, effective_event_at, revoked_at, pending_dedup"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                row.experimentId(), row.versionNo(), row.ratioA(), row.ratioB(),
                row.effectiveAt(), row.status(), row.createdByActor(), row.createdAt(),
                row.effectiveEventAt(), pending ? row.experimentId() : null);
    }

    public boolean isDuplicatePending(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_protocol_pending");
    }

    public List<ProtocolVersionRow> findAll(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version WHERE experiment_id = ? "
                        + "ORDER BY version_no",
                MAPPER, experimentId);
    }

    public ProtocolVersionRow find(String experimentId, int versionNo) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND version_no = ?",
                MAPPER, experimentId, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ProtocolVersionRow lock(String experimentId, int versionNo) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND version_no = ? FOR UPDATE",
                MAPPER, experimentId, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 当前唯一待生效版本；无则 null。 */
    public ProtocolVersionRow findPending(String experimentId) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND status = 'PENDING'",
                MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 已到生效时刻但尚未裁决的待生效版本，按生效时刻、版本号升序。 */
    public List<ProtocolVersionRow> findDuePending(String experimentId, long nowMillis) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND status = 'PENDING' AND effective_at <= ? "
                        + "ORDER BY effective_at, version_no",
                MAPPER, experimentId, nowMillis);
    }

    /** 当前有效版本：effective_at 不晚于给定时刻的最大版本号 ACTIVE 版本。 */
    public ProtocolVersionRow findEffective(String experimentId, long nowMillis) {
        List<ProtocolVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM protocol_version "
                        + "WHERE experiment_id = ? AND status = 'ACTIVE' AND effective_at <= ? "
                        + "ORDER BY version_no DESC LIMIT 1",
                MAPPER, experimentId, nowMillis);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int maxVersionNo(String experimentId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version_no) FROM protocol_version WHERE experiment_id = ?",
                Integer.class, experimentId);
        return max == null ? 0 : max;
    }

    /**
     * 裁决生效：PENDING -> ACTIVE，记录裁决时刻并释放待生效去重占位。
     *
     * @return 受影响行数；0 表示版本不存在或非 PENDING
     */
    public int markEffective(String experimentId, int versionNo, long eventTime) {
        return jdbc.update(
                "UPDATE protocol_version SET status = 'ACTIVE', effective_event_at = ?, "
                        + "pending_dedup = NULL "
                        + "WHERE experiment_id = ? AND version_no = ? AND status = 'PENDING'",
                eventTime, experimentId, versionNo);
    }

    /**
     * 撤销未生效修订：PENDING -> REVOKED，记录保留并释放待生效去重占位。
     *
     * @return 受影响行数；0 表示版本不存在或非 PENDING
     */
    public int revoke(String experimentId, int versionNo, long revokedAt) {
        return jdbc.update(
                "UPDATE protocol_version SET status = 'REVOKED', revoked_at = ?, "
                        + "pending_dedup = NULL "
                        + "WHERE experiment_id = ? AND version_no = ? AND status = 'PENDING'",
                revokedAt, experimentId, versionNo);
    }
}
