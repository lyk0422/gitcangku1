package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心数据访问；中心按实验隔离，当前协议版本随修订原子生效或暂停恢复切换。
 */
@Repository
public class CenterRepository {

    /** 中心行。status 取值 ACTIVE/SUSPENDED；currentVersion 为中心当前协议版本号。 */
    public record CenterRow(
            String experimentId,
            String centerId,
            int targetCap,
            String status,
            int currentVersion,
            long createdAt,
            Long suspendedAt,
            Long resumedAt) {
    }

    private static final RowMapper<CenterRow> MAPPER = (rs, n) -> new CenterRow(
            rs.getString("experiment_id"),
            rs.getString("center_id"),
            rs.getInt("target_cap"),
            rs.getString("status"),
            rs.getInt("current_version"),
            rs.getLong("created_at"),
            (Long) rs.getObject("suspended_at"),
            (Long) rs.getObject("resumed_at"));

    private static final String COLUMNS =
            "experiment_id, center_id, target_cap, status, current_version, created_at, "
                    + "suspended_at, resumed_at";

    private final JdbcTemplate jdbc;

    public CenterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CenterRow row) {
        jdbc.update("INSERT INTO center ("
                        + "experiment_id, center_id, target_cap, status, current_version, created_at, "
                        + "suspended_at, resumed_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.centerId(), row.targetCap(), row.status(),
                row.currentVersion(), row.createdAt(), row.suspendedAt(), row.resumedAt());
    }

    public CenterRow find(String experimentId, String centerId) {
        List<CenterRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM center WHERE experiment_id = ? AND center_id = ?",
                MAPPER, experimentId, centerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定中心，串行化同中心的登记与版本切换。 */
    public CenterRow lock(String experimentId, String centerId) {
        List<CenterRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM center WHERE experiment_id = ? AND center_id = ? FOR UPDATE",
                MAPPER, experimentId, centerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 实验内全部中心，按中心编号排序。 */
    public List<CenterRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM center WHERE experiment_id = ? ORDER BY center_id",
                MAPPER, experimentId);
    }

    /**
     * 行级锁定实验内全部中心，修订生效时据此串行扫描并切换所有中心。
     */
    public List<CenterRow> lockByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM center WHERE experiment_id = ? ORDER BY center_id FOR UPDATE",
                MAPPER, experimentId);
    }

    /**
     * 修订生效：仅 ACTIVE 中心切换到新版本；暂停中心保持旧版本不变。
     *
     * @return 受影响行数
     */
    public int updateVersionForActiveCenters(String experimentId, int newVersion) {
        return jdbc.update(
                "UPDATE center SET current_version = ? WHERE experiment_id = ? AND status = 'ACTIVE'",
                newVersion, experimentId);
    }

    public int markSuspended(String experimentId, String centerId, long suspendedAt) {
        return jdbc.update(
                "UPDATE center SET status = 'SUSPENDED', suspended_at = ? "
                        + "WHERE experiment_id = ? AND center_id = ? AND status = 'ACTIVE'",
                suspendedAt, experimentId, centerId);
    }

    /**
     * 恢复：暂停中心恢复为 ACTIVE，并切换到当时有效版本（连同恢复时间一并更新）。
     *
     * @return 受影响行数；0 表示中心不存在或未暂停
     */
    public int markResumed(String experimentId, String centerId, int effectiveVersion, long resumedAt) {
        return jdbc.update(
                "UPDATE center SET status = 'ACTIVE', current_version = ?, resumed_at = ? "
                        + "WHERE experiment_id = ? AND center_id = ? AND status = 'SUSPENDED'",
                effectiveVersion, resumedAt, experimentId, centerId);
    }
}
