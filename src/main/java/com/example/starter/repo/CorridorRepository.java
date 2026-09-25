package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 走廊与走廊预约数据访问。
 *
 * <p>所有写操作的容量裁决都在业务事务内先对走廊行做真实更新（touch 加一）取得
 * 行级排他锁，使同一走廊的预约创建/取消按事务提交顺序串行化，杜绝并发超卖与
 * 重复释放。</p>
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
            rs.getLong("touch"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<ReservationPo> RESERVATION_MAPPER = (rs, n) ->
            new ReservationPo(
                    rs.getString("reservation_key"),
                    rs.getString("corridor_id"),
                    rs.getLong("start_time"),
                    rs.getLong("end_time"),
                    rs.getString("review_id"),
                    rs.getString("status"),
                    rs.getString("request_id"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("cancelled_at"));

    /** 创建走廊（调用方负责事务与重复校验）。 */
    public void insertCorridor(CorridorPo corridor) {
        jdbc.update("INSERT INTO corridor "
                        + "(corridor_id, x_min, y_min, x_max, y_max, capacity, touch, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                corridor.corridorId(), corridor.xMin(), corridor.yMin(), corridor.xMax(),
                corridor.yMax(), corridor.capacity(), corridor.touch(),
                corridor.createdAt(), corridor.updatedAt());
    }

    /** 按 corridorId 查询走廊，不存在返回 null。 */
    public CorridorPo findCorridor(String corridorId) {
        try {
            return jdbc.queryForObject(
                    "SELECT corridor_id, x_min, y_min, x_max, y_max, capacity, touch, "
                            + "created_at, updated_at FROM corridor WHERE corridor_id = ?",
                    CORRIDOR_MAPPER, corridorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对走廊行做真实更新（touch 加一）取得行级排他锁并读取走廊。
     *
     * <p>更新为不同的值确保 H2/MySQL 不跳过加锁；同一走廊的预约创建/取消/容量
     * 调整事务因此互斥，容量统计与插入在同一个已提交快照内完成，杜绝并发超卖。</p>
     *
     * @return 走廊当前状态；不存在（更新 0 行）返回 null
     */
    public CorridorPo lockCorridor(String corridorId) {
        int locked = jdbc.update(
                "UPDATE corridor SET touch = touch + 1 WHERE corridor_id = ?", corridorId);
        if (locked == 0) {
            return null;
        }
        return findCorridor(corridorId);
    }

    /**
     * 条件上调容量：仅当新容量严格大于当前容量时更新（同时推进 touch 持有行写锁）。
     *
     * @return 更新行数；0 表示走廊不存在或新容量不大于当前容量
     */
    public int increaseCapacity(String corridorId, int newCapacity, long updatedAt) {
        return jdbc.update(
                "UPDATE corridor SET capacity = ?, touch = touch + 1, updated_at = ? "
                        + "WHERE corridor_id = ? AND capacity < ?",
                newCapacity, updatedAt, corridorId, newCapacity);
    }

    /** 插入预约（调用方负责事务与全部业务校验）。 */
    public void insertReservation(ReservationPo reservation) {
        jdbc.update("INSERT INTO corridor_reservation "
                        + "(reservation_key, corridor_id, start_time, end_time, review_id, "
                        + "status, request_id, created_at, cancelled_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reservation.reservationKey(), reservation.corridorId(),
                reservation.startTime(), reservation.endTime(), reservation.reviewId(),
                reservation.status(), reservation.requestId(),
                reservation.createdAt(), reservation.cancelledAt());
    }

    /** 按 reservationKey 查询预约，不存在返回 null。 */
    public ReservationPo findReservation(String reservationKey) {
        return jdbc.query(
                "SELECT reservation_key, corridor_id, start_time, end_time, review_id, status, "
                        + "request_id, created_at, cancelled_at FROM corridor_reservation "
                        + "WHERE reservation_key = ?",
                RESERVATION_MAPPER, reservationKey).stream().findFirst().orElse(null);
    }

    /**
     * 统计与给定时间窗 [start,end) 时间重叠的 ACTIVE 预约数。
     * 左闭右开区间重叠条件：r.start &lt; end 且 r.end &gt; start（首尾相接不重叠）。
     * 调用方必须已持有走廊行写锁。
     */
    public int countOverlappingActive(String corridorId, long start, long end) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM corridor_reservation "
                        + "WHERE corridor_id = ? AND status = 'ACTIVE' "
                        + "AND start_time < ? AND end_time > ?",
                Integer.class, corridorId, end, start);
        return count == null ? 0 : count;
    }

    /** 查询某时刻 at 生效的 ACTIVE 预约（start_time &lt;= at &lt; end_time），
     * 按开始时刻、业务键排序。 */
    public List<ReservationPo> findActiveAt(String corridorId, long at) {
        return jdbc.query(
                "SELECT reservation_key, corridor_id, start_time, end_time, review_id, status, "
                        + "request_id, created_at, cancelled_at FROM corridor_reservation "
                        + "WHERE corridor_id = ? AND status = 'ACTIVE' "
                        + "AND start_time <= ? AND end_time > ? "
                        + "ORDER BY start_time, reservation_key",
                RESERVATION_MAPPER, corridorId, at, at);
    }

    /** 查询与 [from,to) 有任意时间重叠的 ACTIVE 预约，按开始时刻、业务键排序。 */
    public List<ReservationPo> findActiveOverlapping(String corridorId, long from, long to) {
        return jdbc.query(
                "SELECT reservation_key, corridor_id, start_time, end_time, review_id, status, "
                        + "request_id, created_at, cancelled_at FROM corridor_reservation "
                        + "WHERE corridor_id = ? AND status = 'ACTIVE' "
                        + "AND start_time < ? AND end_time > ? "
                        + "ORDER BY start_time, reservation_key",
                RESERVATION_MAPPER, corridorId, to, from);
    }

    /** 查询走廊全部预约（含已取消的历史记录），按创建时间、业务键排序。 */
    public List<ReservationPo> findHistory(String corridorId) {
        return jdbc.query(
                "SELECT reservation_key, corridor_id, start_time, end_time, review_id, status, "
                        + "request_id, created_at, cancelled_at FROM corridor_reservation "
                        + "WHERE corridor_id = ? ORDER BY created_at, reservation_key",
                RESERVATION_MAPPER, corridorId);
    }

    /**
     * 条件取消：仅当预约当前为 ACTIVE 时置为 CANCELLED 并记录取消时间。
     *
     * @return 更新行数；0 表示预约不存在或已取消
     */
    public int markCancelled(String reservationKey, long cancelledAt) {
        return jdbc.update(
                "UPDATE corridor_reservation SET status = 'CANCELLED', cancelled_at = ? "
                        + "WHERE reservation_key = ? AND status = 'ACTIVE'",
                cancelledAt, reservationKey);
    }
}
