package com.example.starter.water;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 泵站与流量曲线数据访问层（JdbcTemplate，参数化 SQL）。
 * 曲线时刻为 UTC epoch 分钟（BIGINT），流量为 DECIMAL(19,3)，单位立方米每分钟。
 */
@Repository
public class PumpStationRepository {

    /** 泵站配置行；version 从 1 起每次成功调整递增。 */
    public record StationRow(long id, String stationKey, BigDecimal maxFlow, BigDecimal maxRamp,
                             long version, long createdNanos, long updatedNanos) {
    }

    /** 曲线节点行。 */
    public record CurveNodeRow(long allocationId, int nodeIndex, long minuteUtc, BigDecimal flow) {
    }

    /** 请求幂等行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record RequestRow(String requestKey, String operation, String actor, String resource,
                             String paramsJson, String response, long createdNanos) {
    }

    private static final RowMapper<StationRow> STATION_MAPPER = (rs, n) -> new StationRow(
            rs.getLong("id"), rs.getString("station_key"), rs.getBigDecimal("max_flow"),
            rs.getBigDecimal("max_ramp"), rs.getLong("version"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String STATION_SELECT =
            "SELECT id, station_key, max_flow, max_ramp, version, created_nanos, updated_nanos";

    private static final RowMapper<CurveNodeRow> CURVE_NODE_MAPPER = (rs, n) -> new CurveNodeRow(
            rs.getLong("allocation_id"), rs.getInt("node_index"), rs.getLong("minute_utc"),
            rs.getBigDecimal("flow"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getString("request_key"), rs.getString("operation"), rs.getString("actor"),
            rs.getString("resource"), rs.getString("params_json"), rs.getString("response"),
            rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public PumpStationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入泵站（版本从 1 起）并返回主键；并发同键由唯一约束裁决。 */
    public long insertStation(String stationKey, BigDecimal maxFlow, BigDecimal maxRamp, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pump_station (station_key, max_flow, max_ramp, version, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, 1, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, stationKey);
            ps.setBigDecimal(2, maxFlow);
            ps.setBigDecimal(3, maxRamp);
            ps.setLong(4, nowNanos);
            ps.setLong(5, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询泵站，不存在返回 null。 */
    public StationRow findByKey(String stationKey) {
        try {
            return jdbc.queryForObject(STATION_SELECT + " FROM pump_station WHERE station_key = ?",
                    STATION_MAPPER, stationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定泵站行（FOR UPDATE），串行化曲线批准、取消、改配额与参数调整。 */
    public StationRow lockByKey(String stationKey) {
        try {
            return jdbc.queryForObject(STATION_SELECT + " FROM pump_station WHERE station_key = ? FOR UPDATE",
                    STATION_MAPPER, stationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 更新泵站配置并递增版本。 */
    public void updateConfig(long id, BigDecimal maxFlow, BigDecimal maxRamp, long newVersion,
                             long updatedNanos) {
        jdbc.update("UPDATE pump_station SET max_flow = ?, max_ramp = ?, version = ?, updated_nanos = ?"
                + " WHERE id = ?", maxFlow, maxRamp, newVersion, updatedNanos, id);
    }

    /** 插入曲线节点（提交后冻结不可改）。 */
    public void insertCurveNode(long allocationId, int nodeIndex, long minuteUtc, BigDecimal flow) {
        jdbc.update("INSERT INTO allocation_curve_node (allocation_id, node_index, minute_utc, flow)"
                + " VALUES (?, ?, ?, ?)", allocationId, nodeIndex, minuteUtc, flow);
    }

    /** 申请的全部曲线节点，按序号升序；无曲线返回空列表。 */
    public List<CurveNodeRow> listCurveNodes(long allocationId) {
        return jdbc.query("SELECT allocation_id, node_index, minute_utc, flow FROM allocation_curve_node"
                        + " WHERE allocation_id = ? ORDER BY node_index",
                CURVE_NODE_MAPPER, allocationId);
    }

    /** 申请是否带流量曲线。 */
    public boolean hasCurve(long allocationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_curve_node WHERE allocation_id = ?", Integer.class,
                allocationId);
        return count != null && count > 0;
    }

    /**
     * 泵站全部已批准申请的流量曲线（排除 excludeAllocationId，传 0 不排除），
     * 按申请分组、组内按节点序号升序；取消的申请不再计入叠加。
     */
    public List<List<FlowCurve.Node>> listApprovedCurves(String stationKey, long excludeAllocationId) {
        List<CurveNodeRow> rows = jdbc.query(
                "SELECT c.allocation_id, c.node_index, c.minute_utc, c.flow FROM allocation_curve_node c"
                        + " JOIN allocation a ON a.id = c.allocation_id"
                        + " JOIN supply_window w ON w.id = a.window_id"
                        + " WHERE w.station_key = ? AND a.status = 'APPROVED' AND a.id <> ?"
                        + " ORDER BY c.allocation_id, c.node_index",
                CURVE_NODE_MAPPER, stationKey, excludeAllocationId);
        List<List<FlowCurve.Node>> curves = new ArrayList<>();
        List<FlowCurve.Node> current = null;
        long lastAllocation = -1;
        for (CurveNodeRow row : rows) {
            if (current == null || row.allocationId() != lastAllocation) {
                current = new ArrayList<>();
                curves.add(current);
                lastAllocation = row.allocationId();
            }
            current.add(new FlowCurve.Node(row.minuteUtc(), row.flow()));
        }
        return curves;
    }

    /** 按幂等键查询请求，不存在返回 null。 */
    public RequestRow findRequest(String requestKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT request_key, operation, actor, resource, params_json, response, created_nanos"
                            + " FROM request_log WHERE request_key = ?",
                    REQUEST_MAPPER, requestKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 先占位插入请求（响应为空），同事务内业务成功后写回响应。 */
    public void insertRequest(String requestKey, String operation, String actor, String resource,
                              String paramsJson, long createdNanos) {
        jdbc.update("INSERT INTO request_log (request_key, operation, actor, resource, params_json, response,"
                        + " created_nanos) VALUES (?, ?, ?, ?, ?, NULL, ?)",
                requestKey, operation, actor, resource, paramsJson, createdNanos);
    }

    /** 写回请求首次成功响应。 */
    public void updateRequestResponse(String requestKey, String response) {
        jdbc.update("UPDATE request_log SET response = ? WHERE request_key = ?", response, requestKey);
    }
}