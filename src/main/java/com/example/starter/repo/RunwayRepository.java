package com.example.starter.repo;

import com.example.starter.domain.FlightPlan;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 跑道、跑道关闭窗口与航线跑道风险数据访问。
 */
@Repository
public class RunwayRepository {

    private final JdbcTemplate jdbc;

    public RunwayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RunwayPo> RUNWAY_MAPPER = (rs, n) -> new RunwayPo(
            rs.getString("runway_id"), rs.getInt("version"), rs.getInt("capacity_per_hour"));

    private static final RowMapper<ClosurePo> CLOSURE_MAPPER = (rs, n) -> new ClosurePo(
            rs.getString("closure_id"), rs.getString("runway_id"),
            rs.getLong("start_utc"), rs.getLong("end_utc"),
            rs.getBoolean("allow_emergency"), rs.getString("operator"),
            rs.getInt("runway_version"), rs.getString("closure_key"), rs.getLong("created_at"));

    private static final RowMapper<RouteRiskPo> RISK_MAPPER = (rs, n) -> new RouteRiskPo(
            rs.getString("route_id"), rs.getString("closure_id"), rs.getString("runway_id"),
            rs.getString("segment"), rs.getLong("start_utc"), rs.getLong("end_utc"),
            rs.getBoolean("allow_emergency"), rs.getString("operator"),
            rs.getInt("runway_version"), rs.getLong("created_at"));

    private static final String CLOSURE_COLUMNS =
            "closure_id, runway_id, start_utc, end_utc, allow_emergency, operator, "
                    + "runway_version, closure_key, created_at";

    // ============================ 跑道 ============================

    /** 按 runwayId 查询跑道，不存在返回 null。 */
    public RunwayPo findRunway(String runwayId) {
        try {
            return jdbc.queryForObject(
                    "SELECT runway_id, version, capacity_per_hour FROM runway WHERE runway_id = ?",
                    RUNWAY_MAPPER, runwayId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对跑道行做真实更新（touch 加一）取得行级排他锁并读取跑道。
     *
     * @return 跑道当前状态；不存在返回 null（更新 0 行）
     */
    public RunwayPo findRunwayForUpdate(String runwayId) {
        int locked = jdbc.update(
                "UPDATE runway SET touch = touch + 1 WHERE runway_id = ?", runwayId);
        if (locked == 0) {
            return null;
        }
        return findRunway(runwayId);
    }

    /** 创建跑道（初始版本 1，调用方负责事务）。 */
    public void insertRunway(String runwayId, int capacityPerHour) {
        jdbc.update("INSERT INTO runway (runway_id, version, capacity_per_hour, touch) "
                + "VALUES (?, 1, ?, 0)", runwayId, capacityPerHour);
    }

    /** 跑道版本加一（调用方负责事务与行锁）。 */
    public int incrementRunwayVersion(String runwayId) {
        return jdbc.update("UPDATE runway SET version = version + 1 WHERE runway_id = ?", runwayId);
    }

    // ============================ 关闭窗口 ============================

    /** 插入关闭窗口（调用方负责事务）。 */
    public void insertClosure(ClosurePo po) {
        jdbc.update("INSERT INTO runway_closure "
                        + "(closure_id, runway_id, start_utc, end_utc, allow_emergency, operator, "
                        + "runway_version, closure_key, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.closureId(), po.runwayId(), po.startUtc(), po.endUtc(), po.allowEmergency(),
                po.operator(), po.runwayVersion(), po.closureKey(), po.createdAt());
    }

    /** 查询某跑道全部关闭窗口（按开始时刻、closureId 升序）。 */
    public List<ClosurePo> findClosures(String runwayId) {
        return jdbc.query(
                "SELECT " + CLOSURE_COLUMNS + " FROM runway_closure WHERE runway_id = ? "
                        + "ORDER BY start_utc, closure_id",
                CLOSURE_MAPPER, runwayId);
    }

    /**
     * 查询某跑道上与候选窗口 [startUtc, endUtc) 重叠（不含端点相接）的既有窗口。
     * 重叠判定：existing.start < newEnd 且 newStart < existing.end；端点相接（=）合法。
     */
    public List<ClosurePo> findOverlappingClosures(String runwayId, long startUtc, long endUtc) {
        return jdbc.query(
                "SELECT " + CLOSURE_COLUMNS + " FROM runway_closure "
                        + "WHERE runway_id = ? AND start_utc < ? AND ? < end_utc "
                        + "ORDER BY start_utc, closure_id",
                CLOSURE_MAPPER, runwayId, endUtc, startUtc);
    }

    /** 查询某跑道上覆盖时刻 t 的关闭窗口（start <= t < end，左闭右开）。 */
    public List<ClosurePo> findClosuresAt(String runwayId, long timeUtc) {
        return jdbc.query(
                "SELECT " + CLOSURE_COLUMNS + " FROM runway_closure "
                        + "WHERE runway_id = ? AND start_utc <= ? AND ? < end_utc "
                        + "ORDER BY start_utc, closure_id",
                CLOSURE_MAPPER, runwayId, timeUtc, timeUtc);
    }

    // ============================ 容量 ============================

    /**
     * 统计某跑道在 [bucketStart, bucketEnd) 时段内已批准（APPROVED）航线的起降段数，
     * 排除指定航线（用于审查时剔除自身或同批航线）。
     */
    public int countApprovedSegments(String runwayId, long bucketStart, long bucketEnd,
                                     List<String> excludedRouteIds) {
        String exclusion = "";
        if (!excludedRouteIds.isEmpty()) {
            StringBuilder sb = new StringBuilder(" AND route_id NOT IN (");
            for (int i = 0; i < excludedRouteIds.size(); i++) {
                sb.append(i == 0 ? "?" : ", ?");
            }
            sb.append(")");
            exclusion = sb.toString();
        }
        String sql = "SELECT "
                + "(SELECT COUNT(*) FROM route WHERE status = 'APPROVED' AND dep_runway_id = ? "
                + " AND dep_time_utc >= ? AND dep_time_utc < ?" + exclusion + ") + "
                + "(SELECT COUNT(*) FROM route WHERE status = 'APPROVED' AND arr_runway_id = ? "
                + " AND arr_time_utc >= ? AND arr_time_utc < ?" + exclusion + ")";
        Object[] params = new Object[2 * (3 + excludedRouteIds.size())];
        int idx = 0;
        for (int half = 0; half < 2; half++) {
            params[idx++] = runwayId;
            params[idx++] = bucketStart;
            params[idx++] = bucketEnd;
            for (String id : excludedRouteIds) {
                params[idx++] = id;
            }
        }
        Integer count = jdbc.queryForObject(sql, Integer.class, params);
        return count == null ? 0 : count;
    }

    // ============================ 风险 ============================

    /**
     * 查询受新关闭窗口影响的未来已批准 NORMAL 航线：
     * 状态 APPROVED、类别非 EMERGENCY、计划起飞时刻晚于 nowUtc，
     * 且起飞或降落段落在该跑道的 [startUtc, endUtc) 窗口内。
     */
    public List<RoutePo> findApprovedNormalRoutesIntersecting(String runwayId, long startUtc,
                                                              long endUtc, long nowUtc) {
        return jdbc.query(
                "SELECT route_id, version, status, category, event_no, dep_runway_id, dep_time_utc, "
                        + "arr_runway_id, arr_time_utc FROM route "
                        + "WHERE status = 'APPROVED' AND (category IS NULL OR category = 'NORMAL') "
                        + "AND dep_time_utc IS NOT NULL AND dep_time_utc > ? "
                        + "AND ((dep_runway_id = ? AND dep_time_utc >= ? AND dep_time_utc < ?) "
                        + "OR (arr_runway_id = ? AND arr_time_utc >= ? AND arr_time_utc < ?)) "
                        + "ORDER BY route_id",
                (rs, n) -> new RoutePo(
                        rs.getString("route_id"),
                        rs.getInt("version"),
                        List.of(),
                        rs.getString("status"),
                        new FlightPlan(
                                rs.getString("category"), rs.getString("event_no"),
                                rs.getString("dep_runway_id"), (Long) rs.getObject("dep_time_utc"),
                                rs.getString("arr_runway_id"), (Long) rs.getObject("arr_time_utc"))),
                nowUtc, runwayId, startUtc, endUtc, runwayId, startUtc, endUtc);
    }

    /** 固化航线风险快照（调用方负责事务）。 */
    public void insertRisk(RouteRiskPo po) {
        jdbc.update("INSERT INTO route_risk "
                        + "(route_id, closure_id, runway_id, segment, start_utc, end_utc, "
                        + "allow_emergency, operator, runway_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.routeId(), po.closureId(), po.runwayId(), po.segment(), po.startUtc(),
                po.endUtc(), po.allowEmergency(), po.operator(), po.runwayVersion(), po.createdAt());
    }

    /** 查询航线风险快照，不存在返回 null。 */
    public RouteRiskPo findRisk(String routeId) {
        return jdbc.query(
                "SELECT route_id, closure_id, runway_id, segment, start_utc, end_utc, "
                        + "allow_emergency, operator, runway_version, created_at "
                        + "FROM route_risk WHERE route_id = ?",
                RISK_MAPPER, routeId).stream().findFirst().orElse(null);
    }

    /** 删除航线风险快照（改航、取消或转紧急例外时清除，调用方负责事务）。 */
    public void deleteRisk(String routeId) {
        jdbc.update("DELETE FROM route_risk WHERE route_id = ?", routeId);
    }
}
