package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 航线时空占用、让渡历史与重新申请历史的数据访问。
 */
@Repository
public class OccupancyRepository {

    private final JdbcTemplate jdbc;

    public OccupancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String OCCUPANCY_COLUMNS =
            "occupancy_id, route_id, kind, status, yieldable, start_ms, end_ms, alt_min, alt_max, "
                    + "review_id, corridor_id, request_id, created_ms, yielded_ms, "
                    + "yield_corridor_id, yield_reason, departed_ms ";

    private static final RowMapper<OccupancyPo> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("occupancy_id"),
            rs.getString("route_id"),
            rs.getString("kind"),
            rs.getString("status"),
            rs.getBoolean("yieldable"),
            rs.getLong("start_ms"),
            rs.getLong("end_ms"),
            rs.getInt("alt_min"),
            rs.getInt("alt_max"),
            rs.getString("review_id"),
            rs.getString("corridor_id"),
            rs.getString("request_id"),
            rs.getLong("created_ms"),
            (Long) rs.getObject("yielded_ms"),
            rs.getString("yield_corridor_id"),
            rs.getString("yield_reason"),
            (Long) rs.getObject("departed_ms"));

    private static final RowMapper<YieldHistoryPo> YIELD_MAPPER = (rs, n) -> new YieldHistoryPo(
            rs.getLong("seq"),
            rs.getString("corridor_id"),
            rs.getInt("corridor_version"),
            rs.getString("occupancy_id"),
            rs.getString("route_id"),
            rs.getString("review_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("reason"),
            rs.getString("request_id"),
            rs.getLong("yielded_ms"));

    private static final RowMapper<ReapplyHistoryPo> REAPPLY_MAPPER = (rs, n) ->
            new ReapplyHistoryPo(
                    rs.getLong("seq"),
                    rs.getString("corridor_id"),
                    rs.getString("old_occupancy_id"),
                    rs.getString("new_occupancy_id"),
                    rs.getString("route_id"),
                    rs.getString("review_id"),
                    rs.getString("result"),
                    rs.getString("request_id"),
                    rs.getLong("applied_ms"));

    /** 按 occupancyId 查询占用，不存在返回 null。 */
    public OccupancyPo findOccupancy(String occupancyId) {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + "FROM route_occupancy WHERE occupancy_id = ?",
                OCCUPANCY_MAPPER, occupancyId).stream().findFirst().orElse(null);
    }

    /** 查询某航线的全部占用（含已让渡/已起飞），按创建时刻、occupancyId 升序，稳定排序。 */
    public List<OccupancyPo> findByRoute(String routeId) {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + "FROM route_occupancy WHERE route_id = ? "
                        + "ORDER BY created_ms ASC, occupancy_id ASC",
                OCCUPANCY_MAPPER, routeId);
    }

    /** 查询全部 ACTIVE 占用（走廊建立时让渡裁决的候选集）。 */
    public List<OccupancyPo> findActiveOccupancies() {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + "FROM route_occupancy WHERE status = 'ACTIVE' "
                        + "ORDER BY occupancy_id",
                OCCUPANCY_MAPPER);
    }

    /**
     * 查询走廊建立裁决所需的全部未让渡占用：ACTIVE（可让渡或不可让渡）与 DEPARTED。
     * YIELDED 占用已为其他走廊让渡，不再参与新走廊裁决。
     */
    public List<OccupancyPo> findActiveAndDepartedOccupancies() {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS
                        + "FROM route_occupancy WHERE status IN ('ACTIVE', 'DEPARTED') "
                        + "ORDER BY occupancy_id",
                OCCUPANCY_MAPPER);
    }

    /** 创建占用（调用方负责事务）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO route_occupancy "
                        + "(occupancy_id, route_id, kind, status, yieldable, "
                        + "start_ms, end_ms, alt_min, alt_max, "
                        + "review_id, corridor_id, request_id, created_ms, yielded_ms, "
                        + "yield_corridor_id, yield_reason, departed_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.routeId(), po.kind(), po.status(), po.yieldable(),
                po.startMs(), po.endMs(), po.altMin(), po.altMax(),
                po.reviewId(), po.corridorId(), po.requestId(), po.createdMs(),
                po.yieldedMs(), po.yieldCorridorId(), po.yieldReason(), po.departedMs());
    }

    /**
     * 在当前事务内条件让渡占用：仅 ACTIVE 行可迁移为 YIELDED，
     * 更新本身取得行级排他锁并固化让渡时刻、走廊与原因。
     *
     * @return 更新行数；0 表示占用状态已不是 ACTIVE（并发下由调用方整次失败回滚）
     */
    public int compareAndYield(String occupancyId, String corridorId, String reason, long nowMs) {
        return jdbc.update("UPDATE route_occupancy "
                        + "SET status = 'YIELDED', yielded_ms = ?, yield_corridor_id = ?, "
                        + "yield_reason = ? WHERE occupancy_id = ? AND status = 'ACTIVE'",
                nowMs, corridorId, reason, occupancyId);
    }

    /**
     * 在当前事务内条件起飞登记：仅 ACTIVE 行可迁移为 DEPARTED。
     *
     * @return 更新行数；0 表示占用已让渡或已起飞（原因由调用方查行区分）
     */
    public int compareAndDepart(String occupancyId, long departedMs) {
        return jdbc.update("UPDATE route_occupancy SET status = 'DEPARTED', departed_ms = ? "
                + "WHERE occupancy_id = ? AND status = 'ACTIVE'", departedMs, occupancyId);
    }

    /** 追加让渡不可变历史（调用方负责事务）。 */
    public void insertYieldHistory(YieldHistoryPo po) {
        jdbc.update("INSERT INTO yield_history "
                        + "(corridor_id, corridor_version, occupancy_id, route_id, review_id, "
                        + "route_version, airspace_version, reason, request_id, yielded_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.corridorId(), po.corridorVersion(), po.occupancyId(), po.routeId(),
                po.reviewId(), po.routeVersion(), po.airspaceVersion(), po.reason(),
                po.requestId(), po.yieldedMs());
    }

    /** 按 seq 升序查询某走廊的全部让渡历史。 */
    public List<YieldHistoryPo> findYieldsByCorridor(String corridorId) {
        return jdbc.query(
                "SELECT seq, corridor_id, corridor_version, occupancy_id, route_id, review_id, "
                        + "route_version, airspace_version, reason, request_id, yielded_ms "
                        + "FROM yield_history WHERE corridor_id = ? ORDER BY seq ASC",
                YIELD_MAPPER, corridorId);
    }

    /** 按 seq 升序查询某原占用关联的全部重新申请历史。 */
    public List<ReapplyHistoryPo> findReappliesByOccupancy(String oldOccupancyId) {
        return jdbc.query(
                "SELECT seq, corridor_id, old_occupancy_id, new_occupancy_id, route_id, review_id, "
                        + "result, request_id, applied_ms "
                        + "FROM reapply_history WHERE old_occupancy_id = ? ORDER BY seq ASC",
                REAPPLY_MAPPER, oldOccupancyId);
    }

    /** 追加重新申请不可变历史（调用方负责事务）。 */
    public void insertReapplyHistory(ReapplyHistoryPo po) {
        jdbc.update("INSERT INTO reapply_history "
                        + "(corridor_id, old_occupancy_id, new_occupancy_id, route_id, review_id, "
                        + "result, request_id, applied_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.corridorId(), po.oldOccupancyId(), po.newOccupancyId(), po.routeId(),
                po.reviewId(), po.result(), po.requestId(), po.appliedMs());
    }
}
