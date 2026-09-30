package com.example.starter.repo;

import com.example.starter.domain.ReservationStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 备降机场与预留数据访问。
 *
 * <p>并发裁决以机场行的真实更新（touch 加一）取得行级排他锁：同一机场的预留申请
 * 事务在该锁上完全串行，保证“按提交时容量裁决、不超卖”。挤出与起飞均使用
 * 带状态条件的更新（CAS），已起飞预留的挤出更新影响 0 行，调用方据此回滚。</p>
 */
@Repository
public class DiversionRepository {

    private final JdbcTemplate jdbc;

    public DiversionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DiversionAirportPo> AIRPORT_MAPPER = (rs, n) ->
            new DiversionAirportPo(rs.getString("airport_id"), rs.getInt("capacity"),
                    rs.getLong("touch"));

    private static final RowMapper<ReservationPo> RESERVATION_MAPPER = (rs, n) ->
            new ReservationPo(rs.getString("reservation_id"), rs.getString("airport_id"),
                    rs.getString("route_id"), rs.getInt("priority"),
                    rs.getLong("window_start"), rs.getLong("window_end"),
                    rs.getString("status"), rs.getString("replaced_by"),
                    rs.getString("request_id"), rs.getLong("created_at"),
                    rs.getLong("updated_at"));

    /** 创建备降机场（调用方负责事务）。 */
    public void insertAirport(DiversionAirportPo po) {
        jdbc.update("INSERT INTO diversion_airport (airport_id, capacity, touch) VALUES (?, ?, 0)",
                po.airportId(), po.capacity());
    }

    /** 按机场标识查询，不存在返回 null。 */
    public DiversionAirportPo findAirport(String airportId) {
        try {
            return jdbc.queryForObject(
                    "SELECT airport_id, capacity, touch FROM diversion_airport WHERE airport_id = ?",
                    AIRPORT_MAPPER, airportId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对机场行做真实更新取得行级排他锁。
     *
     * @return 锁定后读到的机场；机场不存在返回 null（更新 0 行）
     */
    public DiversionAirportPo lockAirport(String airportId) {
        int locked = jdbc.update(
                "UPDATE diversion_airport SET touch = touch + 1 WHERE airport_id = ?", airportId);
        if (locked == 0) {
            return null;
        }
        return findAirport(airportId);
    }

    /** 创建预留（状态 RESERVED，调用方负责事务）。 */
    public void insertReservation(ReservationPo po) {
        jdbc.update("INSERT INTO diversion_reservation "
                        + "(reservation_id, airport_id, route_id, priority, window_start, window_end, "
                        + "status, replaced_by, request_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reservationId(), po.airportId(), po.routeId(), po.priority(),
                po.windowStart(), po.windowEnd(), po.status(), po.replacedBy(), po.requestId(),
                po.createdAt(), po.updatedAt());
    }

    /** 按预留标识查询，不存在返回 null。 */
    public ReservationPo findReservation(String reservationId) {
        try {
            return jdbc.queryForObject(
                    "SELECT reservation_id, airport_id, route_id, priority, window_start, window_end, "
                            + "status, replaced_by, request_id, created_at, updated_at "
                            + "FROM diversion_reservation WHERE reservation_id = ?",
                    RESERVATION_MAPPER, reservationId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 查询与给定 UTC 左闭右开窗口重叠、且当前占用容量的预留：未起飞 RESERVED 与
     * 已起飞 DEPARTED 都占用窗口容量；已被挤出 DISPLACED 不再占用。
     * 重叠条件：start &lt; newEnd 且 end &gt; newStart。调用方必须已持有机场行锁。
     * 已起飞预留仍返回，由裁决逻辑按不可挤出处理（不能被任何申请挤出）。
     */
    public List<ReservationPo> findOccupyingOverlapping(String airportId, long windowStart,
                                                        long windowEnd) {
        return jdbc.query(
                "SELECT reservation_id, airport_id, route_id, priority, window_start, window_end, "
                        + "status, replaced_by, request_id, created_at, updated_at "
                        + "FROM diversion_reservation "
                        + "WHERE airport_id = ? AND status IN (?, ?) "
                        + "AND window_start < ? AND window_end > ? "
                        + "ORDER BY reservation_id",
                RESERVATION_MAPPER, airportId,
                ReservationStatus.RESERVED.name(), ReservationStatus.DEPARTED.name(),
                windowEnd, windowStart);
    }

    /**
     * 条件挤出：仅当预留仍为 RESERVED 时原子地置为 DISPLACED 并写入不可变替换关系。
     *
     * @return 更新行数；0 表示预留已起飞或已被挤出，挤出不合法，事务必须回滚
     */
    public int markDisplacedIfReserved(String reservationId, String replacedBy, long updatedAt) {
        return jdbc.update("UPDATE diversion_reservation SET status = ?, replaced_by = ?, "
                        + "updated_at = ? WHERE reservation_id = ? AND status = ?",
                ReservationStatus.DISPLACED.name(), replacedBy, updatedAt, reservationId,
                ReservationStatus.RESERVED.name());
    }

    /**
     * 条件起飞：仅当预留仍为 RESERVED 时置为 DEPARTED。
     *
     * @return 更新行数；0 表示预留不存在于 RESERVED 状态（已起飞/已被挤出）
     */
    public int markDepartedIfReserved(String reservationId, long updatedAt) {
        return jdbc.update("UPDATE diversion_reservation SET status = ?, updated_at = ? "
                        + "WHERE reservation_id = ? AND status = ?",
                ReservationStatus.DEPARTED.name(), updatedAt, reservationId,
                ReservationStatus.RESERVED.name());
    }

    /**
     * 查询与给定查询时段重叠的全部预留（含已起飞/已被挤出，用于展示状态与不可变替换链），
     * 按窗口开始、预留标识排序。重叠条件：start &lt; to 且 end &gt; from。
     */
    public List<ReservationPo> findOverlappingAnyStatus(String airportId, long from, long to) {
        return jdbc.query(
                "SELECT reservation_id, airport_id, route_id, priority, window_start, window_end, "
                        + "status, replaced_by, request_id, created_at, updated_at "
                        + "FROM diversion_reservation "
                        + "WHERE airport_id = ? AND window_start < ? AND window_end > ? "
                        + "ORDER BY window_start, reservation_id",
                RESERVATION_MAPPER, airportId, to, from);
    }
}
