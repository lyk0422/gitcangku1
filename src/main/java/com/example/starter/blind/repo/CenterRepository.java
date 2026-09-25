package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 研究中心数据访问；中心状态 ACTIVE/SUSPENDED，目标入组上限创建后不可改。
 */
@Repository
public class CenterRepository {

    /** 中心行。status 取值 ACTIVE/SUSPENDED；时间为 Unix 毫秒 UTC。 */
    public record CenterRow(
            String experimentId,
            String centerId,
            int targetCap,
            String status,
            long createdAt,
            long updatedAt) {
    }

    private static final RowMapper<CenterRow> MAPPER = (rs, n) -> new CenterRow(
            rs.getString("experiment_id"),
            rs.getString("center_id"),
            rs.getInt("target_cap"),
            rs.getString("status"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String COLUMNS =
            "experiment_id, center_id, target_cap, status, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public CenterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CenterRow row) {
        jdbc.update("INSERT INTO center ("
                        + "experiment_id, center_id, target_cap, status, created_at, updated_at"
                        + ") VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                row.experimentId(), row.centerId(), row.targetCap(),
                row.createdAt(), row.updatedAt());
    }

    public CenterRow find(String experimentId, String centerId) {
        List<CenterRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM center "
                        + "WHERE experiment_id = ? AND center_id = ?",
                MAPPER, experimentId, centerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定中心，串行化同一中心的登记、暂停与恢复。 */
    public CenterRow lock(String experimentId, String centerId) {
        List<CenterRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM center "
                        + "WHERE experiment_id = ? AND center_id = ? FOR UPDATE",
                MAPPER, experimentId, centerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<CenterRow> findAll(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM center WHERE experiment_id = ? "
                        + "ORDER BY center_id",
                MAPPER, experimentId);
    }

    /** 行级锁定实验全部中心；修订生效时在实验锁内调用，保证预留原子一致。 */
    public List<CenterRow> lockAll(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM center WHERE experiment_id = ? "
                        + "ORDER BY center_id FOR UPDATE",
                MAPPER, experimentId);
    }

    public List<CenterRow> findActive(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM center WHERE experiment_id = ? AND status = 'ACTIVE' "
                        + "ORDER BY center_id",
                MAPPER, experimentId);
    }

    /**
     * 暂停中心；仅 ACTIVE 可暂停。
     *
     * @return 受影响行数；0 表示不存在或已暂停
     */
    public int markSuspended(String experimentId, String centerId, long updatedAt) {
        return jdbc.update(
                "UPDATE center SET status = 'SUSPENDED', updated_at = ? "
                        + "WHERE experiment_id = ? AND center_id = ? AND status = 'ACTIVE'",
                updatedAt, experimentId, centerId);
    }

    /**
     * 恢复中心；仅 SUSPENDED 可恢复。
     *
     * @return 受影响行数；0 表示不存在或已激活
     */
    public int markActive(String experimentId, String centerId, long updatedAt) {
        return jdbc.update(
                "UPDATE center SET status = 'ACTIVE', updated_at = ? "
                        + "WHERE experiment_id = ? AND center_id = ? AND status = 'SUSPENDED'",
                updatedAt, experimentId, centerId);
    }
}
