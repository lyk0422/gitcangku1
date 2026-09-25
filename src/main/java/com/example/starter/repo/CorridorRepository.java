package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 航路走廊与时段预约数据访问。
 * 时段为 UTC 左闭右开区间 [start_millis, end_millis)；
 * 两区间重叠判定：start1 &lt; end2 且 start2 &lt; end1。
 */
@Repository
public class CorridorRepository {

    private final JdbcTemplate jdbc;

    public CorridorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CorridorPo> CORRIDOR_MAPPER = (rs, n) -> new CorridorPo(
            rs.getString("corridor_id"),
            rs.getInt("x_min"),
            rs.getInt("y_min"),
            rs.getInt("x_max"),
            rs.getInt("y_max"),
            rs.getInt("capacity"),
            rs.getLong("created_at"));

    private static final RowMapper<ReservationPo> RESERVATION_MAPPER = (rs, n) -> new ReservationPo(
            rs.getString("reservation_id"),
            rs.getString("corridor_id"),
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getLong("start_millis"),
            rs.getLong("end_millis"),
            rs.getString("status"),
            rs.getString("reservation_key"),
            rs.getLong("created_at"),
            (Long) rs.getObject("cancelled_at"));

    private static final String CORRIDOR_COLUMNS =
            "corridor_id, x_min, y_min, x_max, y_max, capacity, created_at";

    private static final String RESERVATION_COLUMNS =
            "reservation_id, corridor_id, review_id, route_id, start_millis, end_millis, "
                    + "status, reservation_key, created_at, cancelled_at";

    // ============================ 走廊 ============================

    /** 按 corridorId 查询走廊，不存在返回 null。 */
    public CorridorPo findCorridor(String corridorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + CORRIDOR_COLUMNS + " FROM corridor WHERE corridor_id = ?",
                    CORRIDOR_MAPPER, corridorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对走廊行做真实更新（touch 加一）取得行级排他锁，并读取走廊。
     *
     * <p>预约创建、取消与调容事务都先执行该更新，彼此按事务提交顺序串行，
     * 保证容量计数不会并发超卖或重复释放。</p>
     *
     * @return 走廊当前状态；不存在返回 null（更新 0 行）
     */
    public CorridorPo findCorridorForUpdate(String corridorId) {
        int locked = jdbc.update(
                "UPDATE corridor SET touch = touch + 1 WHERE corridor_id = ?", corridorId);
        if (locked == 0) {
            return null;
        }
        return findCorridor(corridorId);
    }

    /** 创建走廊（调用方负责事务）。 */
    public void insertCorridor(CorridorPo corridor) {
        jdbc.update("INSERT INTO corridor "
                        + "(corridor_id, x_min, y_min, x_max, y_max, capacity, touch, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                corridor.corridorId(), corridor.xMin(), corridor.yMin(),
                corridor.xMax(), corridor.yMax(), corridor.capacity(), corridor.createdAt());
    }

    /** 上调走廊容量（调用方负责事务与“仅可上调”校验）。 */
    public void updateCapacity(String corridorId, int newCapacity) {
        jdbc.update("UPDATE corridor SET capacity = ? WHERE corridor_id = ?",
                newCapacity, corridorId);
    }

    // ============================ 预约 ============================

    /** 按 reservationId 查询预约（含已取消历史），不存在返回 null。 */
    public ReservationPo findReservation(String reservationId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + RESERVATION_COLUMNS + " FROM corridor_reservation "
                            + "WHERE reservation_id = ?",
                    RESERVATION_MAPPER, reservationId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 插入预约（调用方负责事务与容量校验）。 */
    public void insertReservation(ReservationPo po) {
        jdbc.update("INSERT INTO corridor_reservation "
                        + "(reservation_id, corridor_id, review_id, route_id, start_millis, "
                        + "end_millis, status, reservation_key, created_at, cancelled_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reservationId(), po.corridorId(), po.reviewId(), po.routeId(),
                po.startMillis(), po.endMillis(), po.status(), po.reservationKey(),
                po.createdAt(), po.cancelledAt());
    }

    /** 取消预约：仅当仍为 ACTIVE 时置为 CANCELLED 并记录取消时间（调用方负责事务）。 */
    public int markCancelled(String reservationId, long cancelledAt) {
        return jdbc.update("UPDATE corridor_reservation SET status = 'CANCELLED', cancelled_at = ? "
                        + "WHERE reservation_id = ? AND status = 'ACTIVE'",
                cancelledAt, reservationId);
    }

    /** 统计与指定时段重叠的生效（ACTIVE）预约数。 */
    public int countActiveOverlapping(String corridorId, long startMillis, long endMillis) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM corridor_reservation "
                        + "WHERE corridor_id = ? AND status = 'ACTIVE' "
                        + "AND start_millis < ? AND end_millis > ?",
                Integer.class, corridorId, endMillis, startMillis);
        return count == null ? 0 : count;
    }

    /** 查询某一时刻生效中的预约（start &lt;= at &lt; end），按起始时刻、预约标识排序。 */
    public List<ReservationPo> findActiveAt(String corridorId, long atMillis) {
        return jdbc.query(
                "SELECT " + RESERVATION_COLUMNS + " FROM corridor_reservation "
                        + "WHERE corridor_id = ? AND status = 'ACTIVE' "
                        + "AND start_millis <= ? AND end_millis > ? "
                        + "ORDER BY start_millis, reservation_id",
                RESERVATION_MAPPER, corridorId, atMillis, atMillis);
    }

    /** 查询与指定时段重叠的全部预约（含已取消历史），按起始时刻、预约标识排序。 */
    public List<ReservationPo> findOverlapping(String corridorId, long fromMillis, long toMillis) {
        return jdbc.query(
                "SELECT " + RESERVATION_COLUMNS + " FROM corridor_reservation "
                        + "WHERE corridor_id = ? AND start_millis < ? AND end_millis > ? "
                        + "ORDER BY start_millis, reservation_id",
                RESERVATION_MAPPER, corridorId, toMillis, fromMillis);
    }
}
