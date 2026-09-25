package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 容量桶、审查批件与紧急抢占快照数据访问。
 *
 * <p>所有加锁方法都对目标行做真实更新（touch 加一或条件状态更新），
 * 确保持有 H2（MVStore）/MySQL InnoDB 行级排他锁至事务提交。</p>
 */
@Repository
public class CapacityRepository {

    private final JdbcTemplate jdbc;

    public CapacityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ClearancePo> CLEARANCE_MAPPER = (rs, n) -> new ClearancePo(
            rs.getString("clearance_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("priority"),
            rs.getString("event_no"),
            rs.getString("status"),
            rs.getString("review_id"),
            rs.getString("segments_canonical"),
            rs.getString("request_id"),
            rs.getLong("created_at"),
            (Long) rs.getObject("departed_at"),
            (Long) rs.getObject("displaced_at"));

    private static final RowMapper<BucketOccupancyPo> OCCUPANCY_MAPPER = (rs, n) ->
            new BucketOccupancyPo(
                    rs.getString("clearance_id"),
                    rs.getString("route_id"),
                    rs.getInt("cell_x"),
                    rs.getInt("cell_y"),
                    rs.getLong("time_bucket"),
                    rs.getString("priority"));

    private static final RowMapper<PreemptionPo> PREEMPTION_MAPPER = (rs, n) ->
            new PreemptionPo(
                    rs.getString("preemption_id"),
                    rs.getString("emergency_clearance_id"),
                    rs.getString("emergency_route_id"),
                    rs.getString("event_no"),
                    rs.getLong("airspace_version"),
                    ReviewRepository.decodeZoneIds(rs.getString("displaced_ids_canonical")),
                    rs.getString("request_id"),
                    rs.getLong("created_at"));

    private static final RowMapper<PreemptionItemPo> ITEM_MAPPER = (rs, n) ->
            new PreemptionItemPo(
                    (Long) rs.getObject("id"),
                    rs.getString("preemption_id"),
                    rs.getString("displaced_route_id"),
                    rs.getString("displaced_clearance_id"),
                    rs.getInt("route_version_snapshot"),
                    rs.getString("segments_canonical"),
                    rs.getString("status"),
                    rs.getString("pending_key"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("resolved_at"),
                    rs.getString("resolved_clearance_id"));

    private static final String CLEARANCE_COLUMNS =
            "clearance_id, route_id, route_version, airspace_version, priority, event_no, status, "
                    + "review_id, segments_canonical, request_id, created_at, departed_at, displaced_at";

    // ============================ 单元容量 ============================

    /** 查询单元容量配置；未配置返回 null（调用方按缺省容量 1 裁决）。 */
    public Integer findCapacity(int cellX, int cellY) {
        try {
            return jdbc.queryForObject(
                    "SELECT capacity FROM cell_capacity WHERE cell_x = ? AND cell_y = ?",
                    Integer.class, cellX, cellY);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 设置（覆盖）单元容量（调用方负责事务）。 */
    public void upsertCapacity(int cellX, int cellY, int capacity, long now) {
        int updated = jdbc.update(
                "UPDATE cell_capacity SET capacity = ?, updated_at = ? WHERE cell_x = ? AND cell_y = ?",
                capacity, now, cellX, cellY);
        if (updated == 0) {
            jdbc.update("INSERT INTO cell_capacity (cell_x, cell_y, capacity, updated_at) "
                    + "VALUES (?, ?, ?, ?)", cellX, cellY, capacity, now);
        }
    }

    // ============================ 批件 ============================

    /** 插入批件（初始 APPROVED，调用方负责事务）。 */
    public void insertClearance(ClearancePo po) {
        jdbc.update("INSERT INTO clearance "
                        + "(clearance_id, route_id, route_version, airspace_version, priority, event_no, "
                        + "status, review_id, segments_canonical, request_id, created_at, "
                        + "departed_at, displaced_at, touch) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                po.clearanceId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.priority(), po.eventNo(), po.status(), po.reviewId(), po.bucketsCanonical(),
                po.requestId(), po.createdAt(), po.departedAt(), po.displacedAt());
    }

    /** 按批件标识查询，不存在返回 null。 */
    public ClearancePo findClearance(String clearanceId) {
        return jdbc.query("SELECT " + CLEARANCE_COLUMNS + " FROM clearance WHERE clearance_id = ?",
                CLEARANCE_MAPPER, clearanceId).stream().findFirst().orElse(null);
    }

    /**
     * 对批件行做真实更新（touch 加一）取得行级写锁，锁持有至事务提交；
     * 不改变批件业务状态。批件不存在时更新 0 行。
     */
    public int touchClearance(String clearanceId) {
        return jdbc.update("UPDATE clearance SET touch = touch + 1 WHERE clearance_id = ?",
                clearanceId);
    }

    /**
     * 锁定航线当前生效批件（APPROVED/DEPARTED）行并返回。
     * 对这些行做真实更新取得行级写锁；无生效批件返回 null。
     */
    public ClearancePo findActiveClearanceForUpdate(String routeId) {
        jdbc.update("UPDATE clearance SET touch = touch + 1 "
                + "WHERE route_id = ? AND status IN ('APPROVED', 'DEPARTED')", routeId);
        return jdbc.query("SELECT " + CLEARANCE_COLUMNS
                        + " FROM clearance WHERE route_id = ? AND status IN ('APPROVED', 'DEPARTED')",
                CLEARANCE_MAPPER, routeId).stream().findFirst().orElse(null);
    }

    /** 查询航线全部 DISPLACED 批件（按置换时间升序）。 */
    public List<ClearancePo> findDisplacedClearances(String routeId) {
        return jdbc.query("SELECT " + CLEARANCE_COLUMNS
                        + " FROM clearance WHERE route_id = ? AND status = 'DISPLACED' "
                        + "ORDER BY displaced_at, clearance_id",
                CLEARANCE_MAPPER, routeId);
    }

    /**
     * 条件置换：仅当批件仍为 APPROVED 时转为 DISPLACED 并记录时间。
     *
     * @return 更新行数；0 表示状态已不符（可能已起飞或被其他事务置换）
     */
    public int displaceIfApproved(String clearanceId, long now) {
        return jdbc.update("UPDATE clearance SET status = 'DISPLACED', displaced_at = ? "
                + "WHERE clearance_id = ? AND status = 'APPROVED'", now, clearanceId);
    }

    /** 重新提交审查时把航线旧的未起飞批件作旧（DEPARTED 不受影响）。 */
    public int supersedeApprovedByRoute(String routeId) {
        return jdbc.update("UPDATE clearance SET status = 'SUPERSEDED' "
                + "WHERE route_id = ? AND status = 'APPROVED'", routeId);
    }

    /**
     * 起飞登记：仅 APPROVED 批件可登记为 DEPARTED。
     *
     * @return 更新行数；0 表示批件不存在或状态不符
     */
    public int markDeparted(String clearanceId, long now) {
        return jdbc.update("UPDATE clearance SET status = 'DEPARTED', departed_at = ? "
                + "WHERE clearance_id = ? AND status = 'APPROVED'", now, clearanceId);
    }

    // ============================ 桶占用 ============================

    /** 写入一条桶占用（调用方负责事务与去重）。 */
    public void insertBucket(BucketOccupancyPo po) {
        jdbc.update("INSERT INTO clearance_bucket "
                        + "(clearance_id, route_id, cell_x, cell_y, time_bucket, priority) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                po.clearanceId(), po.routeId(), po.cellX(), po.cellY(),
                po.timeBucket(), po.priority());
    }

    /** 删除批件的全部桶占用（置换/作旧时随事务调用）。 */
    public void deleteBuckets(String clearanceId) {
        jdbc.update("DELETE FROM clearance_bucket WHERE clearance_id = ?", clearanceId);
    }

    /** 查询指定时空桶上的全部占用（关联批件状态，仅返回生效占用）。 */
    public List<BucketOccupancyPo> findOccupants(int cellX, int cellY, long timeBucket) {
        return jdbc.query(
                "SELECT b.clearance_id, b.route_id, b.cell_x, b.cell_y, b.time_bucket, b.priority "
                        + "FROM clearance_bucket b "
                        + "JOIN clearance c ON c.clearance_id = b.clearance_id "
                        + "WHERE b.cell_x = ? AND b.cell_y = ? AND b.time_bucket = ? "
                        + "AND c.status IN ('APPROVED', 'DEPARTED') "
                        + "ORDER BY b.clearance_id",
                OCCUPANCY_MAPPER, cellX, cellY, timeBucket);
    }

    /** 查询容量桶视图：占用 + 批件状态，按桶与批件排序。 */
    public List<CapacityBucketView> findBucketViews(int cellX, int cellY, long timeBucket) {
        return jdbc.query(
                "SELECT b.clearance_id, b.route_id, b.cell_x, b.cell_y, b.time_bucket, "
                        + "b.priority, c.status AS clearance_status "
                        + "FROM clearance_bucket b "
                        + "JOIN clearance c ON c.clearance_id = b.clearance_id "
                        + "WHERE b.cell_x = ? AND b.cell_y = ? AND b.time_bucket = ? "
                        + "AND c.status IN ('APPROVED', 'DEPARTED') "
                        + "ORDER BY b.clearance_id",
                (rs, n) -> new CapacityBucketView(
                        rs.getString("clearance_id"),
                        rs.getString("route_id"),
                        rs.getInt("cell_x"),
                        rs.getInt("cell_y"),
                        rs.getLong("time_bucket"),
                        rs.getString("priority"),
                        rs.getString("clearance_status")),
                cellX, cellY, timeBucket);
    }

    /** 容量桶查询行（含批件状态）。 */
    public record CapacityBucketView(
            String clearanceId, String routeId, int cellX, int cellY, long timeBucket,
            String priority, String clearanceStatus) {
    }

    // ============================ 抢占快照 ============================

    /** 插入不可变抢占快照头（调用方负责事务）。 */
    public void insertPreemption(PreemptionPo po) {
        jdbc.update("INSERT INTO preemption "
                        + "(preemption_id, emergency_clearance_id, emergency_route_id, event_no, "
                        + "airspace_version, displaced_ids_canonical, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.preemptionId(), po.emergencyClearanceId(), po.emergencyRouteId(), po.eventNo(),
                po.airspaceVersion(), ReviewRepository.encodeZoneIds(po.displacedRouteIds()),
                po.requestId(), po.createdAt());
    }

    /** 插入抢占快照明细（调用方负责事务）。 */
    public void insertPreemptionItem(PreemptionItemPo po) {
        jdbc.update("INSERT INTO preemption_item "
                        + "(preemption_id, displaced_route_id, displaced_clearance_id, "
                        + "route_version_snapshot, segments_canonical, status, pending_key, "
                        + "created_at, resolved_at, resolved_clearance_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.preemptionId(), po.displacedRouteId(), po.displacedClearanceId(),
                po.routeVersionSnapshot(), po.bucketsCanonical(), po.status(), po.pendingKey(),
                po.createdAt(), po.resolvedAt(), po.resolvedClearanceId());
    }

    /** 按抢占标识查询快照头，不存在返回 null。 */
    public PreemptionPo findPreemption(String preemptionId) {
        return jdbc.query(
                "SELECT preemption_id, emergency_clearance_id, emergency_route_id, event_no, "
                        + "airspace_version, displaced_ids_canonical, request_id, created_at "
                        + "FROM preemption WHERE preemption_id = ?",
                PREEMPTION_MAPPER, preemptionId).stream().findFirst().orElse(null);
    }

    /** 查询抢占快照全部明细（按被置换航线标识排序）。 */
    public List<PreemptionItemPo> findPreemptionItems(String preemptionId) {
        return jdbc.query(
                "SELECT id, preemption_id, displaced_route_id, displaced_clearance_id, "
                        + "route_version_snapshot, segments_canonical, status, pending_key, "
                        + "created_at, resolved_at, resolved_clearance_id "
                        + "FROM preemption_item WHERE preemption_id = ? "
                        + "ORDER BY displaced_route_id",
                ITEM_MAPPER, preemptionId);
    }

    /** 查询某航线作为被置换方的全部抢占明细（快照时间升序）。 */
    public List<PreemptionItemPo> findItemsForRoute(String routeId) {
        return jdbc.query(
                "SELECT id, preemption_id, displaced_route_id, displaced_clearance_id, "
                        + "route_version_snapshot, segments_canonical, status, pending_key, "
                        + "created_at, resolved_at, resolved_clearance_id "
                        + "FROM preemption_item WHERE displaced_route_id = ? "
                        + "ORDER BY created_at, id",
                ITEM_MAPPER, routeId);
    }

    /**
     * 把航线的未处理抢占明细推进为 RESOLVED（条件更新 pending_key，持行锁）。
     *
     * @return 更新行数；0 表示该航线没有未处理抢占记录
     */
    public int resolvePendingItem(String routeId, String resolvedClearanceId, long now) {
        return jdbc.update("UPDATE preemption_item SET status = 'RESOLVED', pending_key = NULL, "
                        + "resolved_at = ?, resolved_clearance_id = ? WHERE pending_key = ?",
                now, resolvedClearanceId, routeId);
    }
}
