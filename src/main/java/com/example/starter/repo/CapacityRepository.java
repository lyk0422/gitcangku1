package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 时空容量桶、桶占用与紧急抢占快照数据访问。
 * 所有写操作由调用方在业务事务内执行，与审核/起飞共用协调锁串行化。
 */
@Repository
public class CapacityRepository {

    private final JdbcTemplate jdbc;

    public CapacityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CapacityBucketPo> BUCKET_MAPPER = (rs, n) -> new CapacityBucketPo(
            rs.getString("bucket_key"),
            rs.getInt("cell_x"),
            rs.getInt("cell_y"),
            rs.getLong("window_start_min"),
            rs.getLong("window_end_min"),
            rs.getInt("capacity"),
            rs.getLong("created_at"));

    private static final RowMapper<OccupancyPo> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("bucket_key"),
            rs.getString("route_id"),
            rs.getString("review_id"),
            rs.getString("priority"),
            rs.getString("event_no"),
            rs.getString("status"),
            rs.getLong("created_at"));

    private static final RowMapper<PreemptionPo> PREEMPTION_MAPPER = (rs, n) -> new PreemptionPo(
            rs.getString("preemption_id"),
            rs.getString("bucket_key"),
            rs.getString("route_id"),
            rs.getInt("displaced_route_version"),
            rs.getString("emergency_route_id"),
            rs.getString("emergency_event_no"),
            rs.getString("emergency_review_id"),
            rs.getString("state"),
            rs.getLong("created_at"),
            (Long) rs.getObject("processed_at"));

    // ============================ 容量桶 ============================

    /** 按桶键查询容量桶，不存在返回 null。 */
    public CapacityBucketPo findBucket(String bucketKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT bucket_key, cell_x, cell_y, window_start_min, window_end_min, capacity, created_at "
                            + "FROM capacity_bucket WHERE bucket_key = ?", BUCKET_MAPPER, bucketKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询全部容量桶（按桶键字典序）。 */
    public List<CapacityBucketPo> findAllBuckets() {
        return jdbc.query(
                "SELECT bucket_key, cell_x, cell_y, window_start_min, window_end_min, capacity, created_at "
                        + "FROM capacity_bucket ORDER BY bucket_key", BUCKET_MAPPER);
    }

    /** 创建容量桶（调用方负责事务）。 */
    public void insertBucket(CapacityBucketPo po) {
        jdbc.update("INSERT INTO capacity_bucket "
                        + "(bucket_key, cell_x, cell_y, window_start_min, window_end_min, capacity, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                po.bucketKey(), po.cellX(), po.cellY(), po.windowStartMin(), po.windowEndMin(),
                po.capacity(), po.createdAt());
    }

    // ============================ 桶占用 ============================

    /** 查询某桶的全部占用（按 routeId 字典序）。 */
    public List<OccupancyPo> findOccupancy(String bucketKey) {
        return jdbc.query(
                "SELECT bucket_key, route_id, review_id, priority, event_no, status, created_at "
                        + "FROM bucket_occupancy WHERE bucket_key = ? ORDER BY route_id",
                OCCUPANCY_MAPPER, bucketKey);
    }

    /** 统计某桶当前占用数。 */
    public int countOccupancy(String bucketKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bucket_occupancy WHERE bucket_key = ?", Integer.class, bucketKey);
        return count == null ? 0 : count;
    }

    /** 写入桶占用（调用方负责事务）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO bucket_occupancy "
                        + "(bucket_key, route_id, review_id, priority, event_no, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                po.bucketKey(), po.routeId(), po.reviewId(), po.priority(), po.eventNo(),
                po.status(), po.createdAt());
    }

    /** 删除某航线在某桶的占用（被置换时释放容量，调用方负责事务）。 */
    public void deleteOccupancy(String bucketKey, String routeId) {
        jdbc.update("DELETE FROM bucket_occupancy WHERE bucket_key = ? AND route_id = ?",
                bucketKey, routeId);
    }

    /** 起飞登记：把某航线全部 APPROVED 占用标记为 DEPARTED（调用方负责事务）。 */
    public void markOccupancyDeparted(String routeId) {
        jdbc.update("UPDATE bucket_occupancy SET status = 'DEPARTED' "
                + "WHERE route_id = ? AND status = 'APPROVED'", routeId);
    }

    // ============================ 抢占快照 ============================

    /** 写入抢占快照（不可变；open_key 唯一索引保证同一航线仅一条未处理记录）。 */
    public void insertPreemption(PreemptionPo po) {
        jdbc.update("INSERT INTO preemption "
                        + "(preemption_id, bucket_key, route_id, displaced_route_version, "
                        + "emergency_route_id, emergency_event_no, emergency_review_id, "
                        + "state, open_key, created_at, processed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.preemptionId(), po.bucketKey(), po.routeId(), po.displacedRouteVersion(),
                po.emergencyRouteId(), po.emergencyEventNo(), po.emergencyReviewId(),
                po.state(), openKeyOf(po), po.createdAt(), po.processedAt());
    }

    private static String openKeyOf(PreemptionPo po) {
        return "PENDING".equals(po.state()) ? po.routeId() : null;
    }

    /** 查询抢占记录；routeId 与 state 为 null 表示不过滤。 */
    public List<PreemptionPo> findPreemptions(String routeId, String state) {
        StringBuilder sql = new StringBuilder(
                "SELECT preemption_id, bucket_key, route_id, displaced_route_version, "
                        + "emergency_route_id, emergency_event_no, emergency_review_id, "
                        + "state, created_at, processed_at FROM preemption WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (routeId != null) {
            sql.append(" AND route_id = ?");
            args.add(routeId);
        }
        if (state != null) {
            sql.append(" AND state = ?");
            args.add(state);
        }
        sql.append(" ORDER BY created_at, preemption_id");
        return jdbc.query(sql.toString(), PREEMPTION_MAPPER, args.toArray());
    }

    /**
     * 被置换航线重新提交审查时，把其未处理抢占记录标记为已处理
     * （state → RESUBMITTED，open_key 置 NULL 释放唯一约束）。
     *
     * @return 更新行数（0 表示没有未处理记录）
     */
    public int closePendingPreemption(String routeId, long processedAt) {
        return jdbc.update("UPDATE preemption SET state = 'RESUBMITTED', open_key = NULL, "
                + "processed_at = ? WHERE route_id = ? AND state = 'PENDING'", processedAt, routeId);
    }
}
