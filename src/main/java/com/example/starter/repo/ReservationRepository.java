package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 备降机场、预留与不可变替换事件数据访问。
 *
 * <p>预留裁决前必须先对机场行做真实更新（touch 加一）取得行级排他锁，
 * 使同机场的并发预留事务在 H2（MVStore）与 MySQL InnoDB 下串行化，
 * 竞争申请按提交时容量裁决，不超卖、不出现部分挤出。</p>
 */
@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AirportPo> AIRPORT_MAPPER = (rs, n) -> new AirportPo(
            rs.getString("airport_id"),
            rs.getInt("capacity"),
            rs.getLong("touch"),
            rs.getLong("created_at"));

    private static final RowMapper<ReservationPo> RESERVATION_MAPPER = (rs, n) ->
            new ReservationPo(
                    rs.getString("reservation_id"),
                    rs.getString("airport_id"),
                    rs.getString("route_id"),
                    rs.getInt("priority"),
                    rs.getLong("window_start"),
                    rs.getLong("window_end"),
                    rs.getString("status"),
                    rs.getBoolean("departed"),
                    rs.getString("request_id"),
                    rs.getLong("created_at"));

    /** 按 airportId 查询机场，不存在返回 null。 */
    public AirportPo findAirport(String airportId) {
        try {
            return jdbc.queryForObject(
                    "SELECT airport_id, capacity, touch, created_at "
                            + "FROM diversion_airport WHERE airport_id = ?",
                    AIRPORT_MAPPER, airportId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 创建机场（容量校验由调用方负责）。 */
    public void insertAirport(AirportPo airport) {
        jdbc.update("INSERT INTO diversion_airport (airport_id, capacity, touch, created_at) "
                        + "VALUES (?, ?, 0, ?)",
                airport.airportId(), airport.capacity(), airport.createdAt());
    }

    /**
     * 在当前事务内对机场行做真实更新（touch 加一）取得行级排他锁并读取机场。
     *
     * @return 机场当前配置；机场不存在返回 null（更新 0 行）
     */
    public AirportPo lockAirport(String airportId) {
        int locked = jdbc.update(
                "UPDATE diversion_airport SET touch = touch + 1 WHERE airport_id = ?", airportId);
        if (locked == 0) {
            return null;
        }
        return findAirport(airportId);
    }

    /** 按 reservationId 查询预留，不存在返回 null。 */
    public ReservationPo findReservation(String reservationId) {
        try {
            return jdbc.queryForObject(
                    "SELECT reservation_id, airport_id, route_id, priority, window_start, "
                            + "window_end, status, departed, request_id, created_at "
                            + "FROM diversion_reservation WHERE reservation_id = ?",
                    RESERVATION_MAPPER, reservationId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 按创建预留的 requestId 查询预留（幂等重放用），不存在返回 null。 */
    public ReservationPo findReservationByRequest(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT reservation_id, airport_id, route_id, priority, window_start, "
                            + "window_end, status, departed, request_id, created_at "
                            + "FROM diversion_reservation WHERE request_id = ?",
                    RESERVATION_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 查询机场在时段 [from, to) 内与之有重叠的全部预留（含已挤出与已起飞），
     * 必须在持有机场行锁的事务内调用，保证与容量裁决来自同一已提交状态。
     * 重叠条件为 UTC 左闭右开：start &lt; to 且 end &gt; from。
     */
    public List<ReservationPo> findReservationsOverlapping(String airportId, long from, long to) {
        return jdbc.query(
                "SELECT reservation_id, airport_id, route_id, priority, window_start, "
                        + "window_end, status, departed, request_id, created_at "
                        + "FROM diversion_reservation "
                        + "WHERE airport_id = ? AND window_start < ? AND window_end > ? "
                        + "ORDER BY window_start, reservation_id",
                RESERVATION_MAPPER, airportId, to, from);
    }

    /** 插入新预留（调用方负责事务）。 */
    public void insertReservation(ReservationPo po) {
        jdbc.update("INSERT INTO diversion_reservation "
                        + "(reservation_id, airport_id, route_id, priority, window_start, "
                        + "window_end, status, departed, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reservationId(), po.airportId(), po.routeId(), po.priority(),
                po.windowStart(), po.windowEnd(), po.status(), po.departed(),
                po.requestId(), po.createdAt());
    }

    /** 将预留整体置为已挤出（DISPLACED），仅更新尚未起飞且仍 ACTIVE 的行。 */
    public int markDisplaced(String reservationId) {
        return jdbc.update("UPDATE diversion_reservation SET status = 'DISPLACED' "
                + "WHERE reservation_id = ? AND status = 'ACTIVE' AND departed = FALSE",
                reservationId);
    }

    /**
     * 将预留标记为已起飞。仅当前仍 ACTIVE 的预留可起飞；
     * 已被挤出（DISPLACED）的预留不能起飞。
     *
     * @return 更新行数；0 表示预留不存在、已挤出或已起飞
     */
    public int markDeparted(String reservationId) {
        return jdbc.update("UPDATE diversion_reservation SET departed = TRUE "
                + "WHERE reservation_id = ? AND status = 'ACTIVE' AND departed = FALSE",
                reservationId);
    }

    /** 插入不可变替换（挤出）事件（调用方负责事务）。 */
    public void insertReplacement(ReplacementPo po) {
        jdbc.update("INSERT INTO reservation_replacement "
                        + "(replacement_id, airport_id, replaced_reservation_id, replaced_route_id, "
                        + "replaced_priority, replacement_reservation_id, replacement_route_id, "
                        + "replacement_priority, window_start, window_end, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.replacementId(), po.airportId(), po.replacedReservationId(),
                po.replacedRouteId(), po.replacedPriority(), po.replacementReservationId(),
                po.replacementRouteId(), po.replacementPriority(), po.windowStart(),
                po.windowEnd(), po.createdAt());
    }

    /** 查询以某预留为挤出方的全部替换事件（即它挤出了谁），按事件时间、id 排序。 */
    public List<ReplacementPo> findReplacementsByReplacement(String replacementReservationId) {
        return jdbc.query(
                "SELECT replacement_id, airport_id, replaced_reservation_id, replaced_route_id, "
                        + "replaced_priority, replacement_reservation_id, replacement_route_id, "
                        + "replacement_priority, window_start, window_end, created_at "
                        + "FROM reservation_replacement WHERE replacement_reservation_id = ? "
                        + "ORDER BY created_at, replacement_id",
                REPLACEMENT_MAPPER, replacementReservationId);
    }

    /** 查询某预留被谁挤出的事件链（0 或 1 行：一条预留最多被挤出一次），按事件时间倒序。 */
    public List<ReplacementPo> findReplacementsByReplaced(String replacedReservationId) {
        return jdbc.query(
                "SELECT replacement_id, airport_id, replaced_reservation_id, replaced_route_id, "
                        + "replaced_priority, replacement_reservation_id, replacement_route_id, "
                        + "replacement_priority, window_start, window_end, created_at "
                        + "FROM reservation_replacement WHERE replaced_reservation_id = ? "
                        + "ORDER BY created_at DESC, replacement_id DESC",
                REPLACEMENT_MAPPER, replacedReservationId);
    }

    private static final RowMapper<ReplacementPo> REPLACEMENT_MAPPER = (rs, n) ->
            new ReplacementPo(
                    rs.getString("replacement_id"),
                    rs.getString("airport_id"),
                    rs.getString("replaced_reservation_id"),
                    rs.getString("replaced_route_id"),
                    rs.getInt("replaced_priority"),
                    rs.getString("replacement_reservation_id"),
                    rs.getString("replacement_route_id"),
                    rs.getInt("replacement_priority"),
                    rs.getLong("window_start"),
                    rs.getLong("window_end"),
                    rs.getLong("created_at"));
}
