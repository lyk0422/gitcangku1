package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 应急走廊、让渡历史与重新申请历史数据访问。
 * 让渡与重新申请记录不可变，只插不改；走廊仅创建与撤销两次状态写入。
 */
@Repository
public class CorridorRepository {

    private final JdbcTemplate jdbc;

    public CorridorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String CORRIDOR_COLUMNS =
            "corridor_id, x_min, y_min, x_max, y_max, start_utc, end_utc, alt_min, alt_max, "
                    + "priority, status, version, operator, created_at, revoked_at";

    private static final RowMapper<CorridorPo> CORRIDOR_MAPPER = (rs, n) -> new CorridorPo(
            rs.getString("corridor_id"),
            rs.getInt("x_min"),
            rs.getInt("y_min"),
            rs.getInt("x_max"),
            rs.getInt("y_max"),
            rs.getLong("start_utc"),
            rs.getLong("end_utc"),
            rs.getInt("alt_min"),
            rs.getInt("alt_max"),
            rs.getInt("priority"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("operator"),
            rs.getLong("created_at"),
            (Long) rs.getObject("revoked_at"));

    private static final RowMapper<YieldPo> YIELD_MAPPER = (rs, n) -> new YieldPo(
            rs.getString("yield_id"),
            rs.getString("corridor_id"),
            rs.getInt("corridor_version"),
            rs.getString("occupation_id"),
            rs.getString("route_id"),
            rs.getString("review_id"),
            rs.getInt("route_version"),
            rs.getString("reason"),
            rs.getLong("created_at"));

    private static final RowMapper<ReapplyPo> REAPPLY_MAPPER = (rs, n) -> new ReapplyPo(
            rs.getString("reapply_id"),
            rs.getString("source_occupation_id"),
            rs.getString("new_occupation_id"),
            rs.getString("review_id"),
            rs.getLong("created_at"));

    /** 按 corridorId 查询走廊，不存在返回 null。 */
    public CorridorPo findCorridor(String corridorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + CORRIDOR_COLUMNS + " FROM emergency_corridor WHERE corridor_id = ?",
                    CORRIDOR_MAPPER, corridorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部 ACTIVE 走廊（按 corridorId 字典序，结果稳定）。 */
    public List<CorridorPo> findActiveCorridors() {
        return jdbc.query(
                "SELECT " + CORRIDOR_COLUMNS + " FROM emergency_corridor "
                        + "WHERE status = 'ACTIVE' ORDER BY corridor_id",
                CORRIDOR_MAPPER);
    }

    /** 创建走廊（调用方负责事务与全部前置校验）。 */
    public void insertCorridor(CorridorPo corridor) {
        jdbc.update("INSERT INTO emergency_corridor "
                        + "(corridor_id, x_min, y_min, x_max, y_max, start_utc, end_utc, "
                        + "alt_min, alt_max, priority, status, version, operator, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                corridor.corridorId(), corridor.xMin(), corridor.yMin(),
                corridor.xMax(), corridor.yMax(), corridor.startUtc(), corridor.endUtc(),
                corridor.altMin(), corridor.altMax(), corridor.priority(), corridor.status(),
                corridor.version(), corridor.operator(), corridor.createdAt(), corridor.revokedAt());
    }

    /** 撤销走廊：状态置 REVOKED、版本加一并记录撤销时间（调用方负责事务）。 */
    public void markRevoked(String corridorId, long revokedAt) {
        jdbc.update("UPDATE emergency_corridor SET status = 'REVOKED', version = version + 1, "
                + "revoked_at = ? WHERE corridor_id = ? AND status = 'ACTIVE'", revokedAt, corridorId);
    }

    /** 插入不可变让渡记录（与走廊创建同事务）。 */
    public void insertYield(YieldPo yield) {
        jdbc.update("INSERT INTO corridor_yield "
                        + "(yield_id, corridor_id, corridor_version, occupation_id, route_id, "
                        + "review_id, route_version, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                yield.yieldId(), yield.corridorId(), yield.corridorVersion(), yield.occupationId(),
                yield.routeId(), yield.reviewId(), yield.routeVersion(), yield.reason(),
                yield.createdAt());
    }

    /** 查询某走廊的全部让渡记录（按让渡时间、yieldId 升序，结果稳定）。 */
    public List<YieldPo> findYieldsByCorridor(String corridorId) {
        return jdbc.query(
                "SELECT yield_id, corridor_id, corridor_version, occupation_id, route_id, "
                        + "review_id, route_version, reason, created_at "
                        + "FROM corridor_yield WHERE corridor_id = ? "
                        + "ORDER BY created_at, yield_id",
                YIELD_MAPPER, corridorId);
    }

    /** 查询某占用对应的让渡记录，未让渡返回 null。 */
    public YieldPo findYieldByOccupation(String occupationId) {
        return jdbc.query(
                "SELECT yield_id, corridor_id, corridor_version, occupation_id, route_id, "
                        + "review_id, route_version, reason, created_at "
                        + "FROM corridor_yield WHERE occupation_id = ?",
                YIELD_MAPPER, occupationId).stream().findFirst().orElse(null);
    }

    /** 插入不可变重新申请记录（与新占用同事务）。 */
    public void insertReapply(ReapplyPo reapply) {
        jdbc.update("INSERT INTO occupation_reapply "
                        + "(reapply_id, source_occupation_id, new_occupation_id, review_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                reapply.reapplyId(), reapply.sourceOccupationId(), reapply.newOccupationId(),
                reapply.reviewId(), reapply.createdAt());
    }

    /** 查询以某占用为来源的重新申请记录（按时间、reapplyId 升序）。 */
    public List<ReapplyPo> findReappliesBySource(String sourceOccupationId) {
        return jdbc.query(
                "SELECT reapply_id, source_occupation_id, new_occupation_id, review_id, created_at "
                        + "FROM occupation_reapply WHERE source_occupation_id = ? "
                        + "ORDER BY created_at, reapply_id",
                REAPPLY_MAPPER, sourceOccupationId);
    }

    /** 查询以某占用为新占用的重新申请记录，不存在返回 null。 */
    public ReapplyPo findReapplyByNewOccupation(String newOccupationId) {
        return jdbc.query(
                "SELECT reapply_id, source_occupation_id, new_occupation_id, review_id, created_at "
                        + "FROM occupation_reapply WHERE new_occupation_id = ?",
                REAPPLY_MAPPER, newOccupationId).stream().findFirst().orElse(null);
    }
}
