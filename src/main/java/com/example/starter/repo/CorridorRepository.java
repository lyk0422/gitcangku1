package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 应急走廊数据访问。
 */
@Repository
public class CorridorRepository {

    private final JdbcTemplate jdbc;

    public CorridorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "corridor_id, priority, x_min, y_min, x_max, y_max, "
            + "start_utc, end_utc, alt_min, alt_max, status, reason, created_by, "
            + "created_airspace_version, revoked_airspace_version, created_at, revoked_at";

    private static final RowMapper<CorridorPo> MAPPER = (rs, n) -> new CorridorPo(
            rs.getString("corridor_id"),
            rs.getInt("priority"),
            rs.getInt("x_min"),
            rs.getInt("y_min"),
            rs.getInt("x_max"),
            rs.getInt("y_max"),
            rs.getLong("start_utc"),
            rs.getLong("end_utc"),
            rs.getInt("alt_min"),
            rs.getInt("alt_max"),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getString("created_by"),
            rs.getLong("created_airspace_version"),
            (Long) rs.getObject("revoked_airspace_version"),
            rs.getLong("created_at"),
            (Long) rs.getObject("revoked_at"));

    /** 按 corridorId 查询走廊，不存在返回 null。 */
    public CorridorPo findCorridor(String corridorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM corridor WHERE corridor_id = ?",
                    MAPPER, corridorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部走廊，按 corridorId 升序（查询不修改状态）。 */
    public List<CorridorPo> findAll() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM corridor ORDER BY corridor_id", MAPPER);
    }

    /** 查询某优先级下全部 ACTIVE 走廊（含时间窗已自然到期但未撤销者，时间重叠由调用方判定）。 */
    public List<CorridorPo> findActiveByPriority(int priority) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM corridor WHERE status = 'ACTIVE' AND priority = ? "
                        + "ORDER BY corridor_id",
                MAPPER, priority);
    }

    /** 创建走廊（调用方负责事务与版本递增）。 */
    public void insertCorridor(CorridorPo po) {
        jdbc.update("INSERT INTO corridor "
                        + "(corridor_id, priority, x_min, y_min, x_max, y_max, start_utc, end_utc, "
                        + "alt_min, alt_max, status, reason, created_by, "
                        + "created_airspace_version, revoked_airspace_version, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.corridorId(), po.priority(), po.xMin(), po.yMin(), po.xMax(), po.yMax(),
                po.startUtc(), po.endUtc(), po.altMin(), po.altMax(), po.status(), po.reason(),
                po.createdBy(), po.createdAirspaceVersion(), po.revokedAirspaceVersion(),
                po.createdAt(), po.revokedAt());
    }

    /**
     * 条件撤销走廊：仅当前状态为 ACTIVE 时写入撤销版本与撤销时间。
     *
     * @return 更新行数；0 表示不存在或已撤销（调用方在此之前已做存在性判定）
     */
    public int markRevoked(String corridorId, long revokedAirspaceVersion, long revokedAt) {
        return jdbc.update("UPDATE corridor SET status = 'REVOKED', "
                        + "revoked_airspace_version = ?, revoked_at = ? "
                        + "WHERE corridor_id = ? AND status = 'ACTIVE'",
                revokedAirspaceVersion, revokedAt, corridorId);
    }
}
