package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 应急走廊及其不可变事件历史的数据访问。
 */
@Repository
public class CorridorRepository {

    private final JdbcTemplate jdbc;

    public CorridorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String CORRIDOR_COLUMNS =
            "corridor_id, x_min, y_min, x_max, y_max, start_ms, end_ms, alt_min, alt_max, "
                    + "priority, status, version, revoke_reason, revoked_ms, created_ms ";

    private static final RowMapper<CorridorPo> CORRIDOR_MAPPER = (rs, n) -> new CorridorPo(
            rs.getString("corridor_id"),
            rs.getInt("x_min"),
            rs.getInt("y_min"),
            rs.getInt("x_max"),
            rs.getInt("y_max"),
            rs.getLong("start_ms"),
            rs.getLong("end_ms"),
            rs.getInt("alt_min"),
            rs.getInt("alt_max"),
            rs.getInt("priority"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("revoke_reason"),
            (Long) rs.getObject("revoked_ms"),
            rs.getLong("created_ms"));

    private static final String EVENT_COLUMNS =
            "seq, corridor_id, version, action, reason, request_id, event_ms, "
                    + "x_min, y_min, x_max, y_max, start_ms, end_ms, alt_min, alt_max, priority ";

    private static final RowMapper<CorridorEventPo> EVENT_MAPPER = (rs, n) -> new CorridorEventPo(
            rs.getLong("seq"),
            rs.getString("corridor_id"),
            rs.getInt("version"),
            rs.getString("action"),
            rs.getString("reason"),
            rs.getString("request_id"),
            rs.getLong("event_ms"),
            rs.getInt("x_min"),
            rs.getInt("y_min"),
            rs.getInt("x_max"),
            rs.getInt("y_max"),
            rs.getLong("start_ms"),
            rs.getLong("end_ms"),
            rs.getInt("alt_min"),
            rs.getInt("alt_max"),
            rs.getInt("priority"));

    /** 按 corridorId 查询走廊，不存在返回 null。 */
    public CorridorPo findCorridor(String corridorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + CORRIDOR_COLUMNS + "FROM emergency_corridor WHERE corridor_id = ?",
                    CORRIDOR_MAPPER, corridorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部未撤销（ACTIVE）走廊；是否在有效期内由时间窗另行判定。 */
    public List<CorridorPo> findActiveCorridors() {
        return jdbc.query(
                "SELECT " + CORRIDOR_COLUMNS + "FROM emergency_corridor WHERE status = 'ACTIVE' "
                        + "ORDER BY corridor_id",
                CORRIDOR_MAPPER);
    }

    /** 建立走廊（版本 1，ACTIVE；调用方负责事务与串行化）。 */
    public void insertCorridor(CorridorPo po) {
        jdbc.update("INSERT INTO emergency_corridor "
                        + "(corridor_id, x_min, y_min, x_max, y_max, start_ms, end_ms, alt_min, alt_max, "
                        + "priority, status, version, revoke_reason, revoked_ms, created_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.corridorId(), po.xMin(), po.yMin(), po.xMax(), po.yMax(),
                po.startMs(), po.endMs(), po.altMin(), po.altMax(), po.priority(),
                po.status(), po.version(), po.revokeReason(), po.revokedMs(), po.createdMs());
    }

    /**
     * 条件撤销走廊：仅当当前为 ACTIVE 时置为 REVOKED、版本加一并固化撤销原因与时刻。
     *
     * @return 更新行数；0 表示走廊不存在或已撤销
     */
    public int compareAndRevoke(String corridorId, String reason, long revokedMs) {
        return jdbc.update("UPDATE emergency_corridor "
                        + "SET status = 'REVOKED', version = version + 1, "
                        + "revoke_reason = ?, revoked_ms = ? "
                        + "WHERE corridor_id = ? AND status = 'ACTIVE'",
                reason, revokedMs, corridorId);
    }

    /** 追加不可变走廊事件（调用方负责事务）。 */
    public void insertEvent(CorridorEventPo po) {
        jdbc.update("INSERT INTO corridor_event "
                        + "(corridor_id, version, action, reason, request_id, event_ms, "
                        + "x_min, y_min, x_max, y_max, start_ms, end_ms, alt_min, alt_max, priority) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.corridorId(), po.version(), po.action(), po.reason(), po.requestId(), po.eventMs(),
                po.xMin(), po.yMin(), po.xMax(), po.yMax(),
                po.startMs(), po.endMs(), po.altMin(), po.altMax(), po.priority());
    }

    /** 按 seq 升序查询走廊全部不可变事件（稳定排序）。 */
    public List<CorridorEventPo> findEvents(String corridorId) {
        return jdbc.query(
                "SELECT " + EVENT_COLUMNS + "FROM corridor_event WHERE corridor_id = ? "
                        + "ORDER BY seq ASC",
                EVENT_MAPPER, corridorId);
    }
}
