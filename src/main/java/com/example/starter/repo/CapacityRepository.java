package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 容量账本数据访问：容量配置、航线版本激活、时空桶占用与转配证据。
 * 所有写方法都由调用方负责事务。
 */
@Repository
public class CapacityRepository {

    private final JdbcTemplate jdbc;

    public CapacityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CapacityConfigPo> CONFIG_MAPPER = (rs, n) -> new CapacityConfigPo(
            rs.getString("cell_id"), rs.getLong("bucket_start"),
            rs.getInt("max_flights"), rs.getLong("updated_at"));

    private static final RowMapper<RouteActivationPo> ACTIVATION_MAPPER = (rs, n) -> new RouteActivationPo(
            rs.getString("route_id"), rs.getInt("version"), rs.getString("status"),
            rs.getString("review_id"), rs.getLong("departure_time"), rs.getDouble("speed_mps"),
            rs.getString("request_id"), rs.getLong("created_at"));

    private static final RowMapper<OccupancyPo> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("route_id"), rs.getInt("route_version"),
            rs.getString("cell_id"), rs.getLong("bucket_start"), rs.getInt("seq"));

    private static final RowMapper<TransferItemPo> TRANSFER_ITEM_MAPPER = (rs, n) -> new TransferItemPo(
            rs.getString("transfer_key"), rs.getString("route_id"),
            rs.getInt("expected_version"), rs.getInt("new_version"),
            rs.getString("source_cell_id"), rs.getLong("source_bucket"),
            rs.getString("target_cell_id"), rs.getLong("target_bucket"),
            rs.getString("review_id"), rs.getString("before_cells"), rs.getString("after_cells"));

    private static final RowMapper<TransferBucketPo> TRANSFER_BUCKET_MAPPER = (rs, n) -> new TransferBucketPo(
            rs.getString("transfer_key"), rs.getString("cell_id"), rs.getLong("bucket_start"),
            rs.getInt("used_before"), rs.getInt("used_after"),
            (Integer) rs.getObject("max_flights"));

    // ============================ 容量配置 ============================

    /** 查询某时空桶的容量配置，未配置返回 null。 */
    public CapacityConfigPo findConfig(String cellId, long bucketStart) {
        return jdbc.query("SELECT cell_id, bucket_start, max_flights, updated_at "
                        + "FROM capacity_config WHERE cell_id = ? AND bucket_start = ?",
                CONFIG_MAPPER, cellId, bucketStart).stream().findFirst().orElse(null);
    }

    /** 查询全部容量配置（按单元、桶稳定排序）。 */
    public List<CapacityConfigPo> findAllConfigs() {
        return jdbc.query("SELECT cell_id, bucket_start, max_flights, updated_at "
                + "FROM capacity_config ORDER BY cell_id, bucket_start", CONFIG_MAPPER);
    }

    /** 新建或调整（upsert）某时空桶的容量上限。 */
    public void upsertConfig(String cellId, long bucketStart, int maxFlights, long updatedAt) {
        jdbc.update("INSERT INTO capacity_config (cell_id, bucket_start, max_flights, updated_at) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE max_flights = VALUES(max_flights), "
                        + "updated_at = VALUES(updated_at)",
                cellId, bucketStart, maxFlights, updatedAt);
    }

    // ============================ 航线版本激活 ============================

    /** 查询航线当前 ACTIVE 激活记录，不存在返回 null。 */
    public RouteActivationPo findActiveActivation(String routeId) {
        return jdbc.query("SELECT route_id, version, status, review_id, departure_time, speed_mps, "
                        + "request_id, created_at FROM route_activation "
                        + "WHERE route_id = ? AND status = 'ACTIVE'",
                ACTIVATION_MAPPER, routeId).stream().findFirst().orElse(null);
    }

    /** 插入激活记录（调用方保证同航线同版本不重复）。 */
    public void insertActivation(RouteActivationPo po) {
        jdbc.update("INSERT INTO route_activation "
                        + "(route_id, version, status, review_id, departure_time, speed_mps, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.routeId(), po.version(), po.status(), po.reviewId(), po.departureTime(),
                po.speedMps(), po.requestId(), po.createdAt());
    }

    /** 将航线当前 ACTIVE 激活置为 SUSPENDED，返回停用记录（无则 null）。 */
    public RouteActivationPo suspendActiveActivation(String routeId) {
        RouteActivationPo active = findActiveActivation(routeId);
        if (active == null) {
            return null;
        }
        jdbc.update("UPDATE route_activation SET status = 'SUSPENDED' "
                + "WHERE route_id = ? AND status = 'ACTIVE'", routeId);
        return active;
    }

    // ============================ 容量占用 ============================

    /** 查询某航线某版本的全部占用（按单元、桶稳定排序）。 */
    public List<OccupancyPo> findOccupancy(String routeId, int routeVersion) {
        return jdbc.query("SELECT route_id, route_version, cell_id, bucket_start, seq "
                        + "FROM capacity_occupancy WHERE route_id = ? AND route_version = ? "
                        + "ORDER BY cell_id, bucket_start",
                OCCUPANCY_MAPPER, routeId, routeVersion);
    }

    /** 查询全量占用（按航线、单元、桶稳定排序），用于整体后态容量核算。 */
    public List<OccupancyPo> findAllOccupancy() {
        return jdbc.query("SELECT route_id, route_version, cell_id, bucket_start, seq "
                        + "FROM capacity_occupancy ORDER BY route_id, cell_id, bucket_start",
                OCCUPANCY_MAPPER);
    }

    /** 写入一条占用（调用方保证主键不重复）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO capacity_occupancy "
                        + "(route_id, route_version, cell_id, bucket_start, seq) VALUES (?, ?, ?, ?, ?)",
                po.routeId(), po.routeVersion(), po.cellId(), po.bucketStart(), po.seq());
    }

    /** 删除某航线某版本的全部占用。 */
    public void deleteOccupancy(String routeId, int routeVersion) {
        jdbc.update("DELETE FROM capacity_occupancy WHERE route_id = ? AND route_version = ?",
                routeId, routeVersion);
    }

    // ============================ 转配证据 ============================

    /** 按 transferKey 查询转配单创建时间，不存在返回 null。 */
    public Long findTransferCreatedAt(String transferKey) {
        return jdbc.query("SELECT created_at FROM capacity_transfer WHERE transfer_key = ?",
                (rs, n) -> rs.getLong("created_at"), transferKey).stream().findFirst().orElse(null);
    }

    /** 插入转配单主记录（transfer_key 唯一约束兜底并发）。 */
    public void insertTransfer(String transferKey, String requestId, long createdAt) {
        jdbc.update("INSERT INTO capacity_transfer (transfer_key, request_id, created_at) "
                + "VALUES (?, ?, ?)", transferKey, requestId, createdAt);
    }

    /** 查询转配单航线明细（按 routeId 稳定排序）。 */
    public List<TransferItemPo> findTransferItems(String transferKey) {
        return jdbc.query("SELECT transfer_key, route_id, expected_version, new_version, "
                        + "source_cell_id, source_bucket, target_cell_id, target_bucket, "
                        + "review_id, before_cells, after_cells "
                        + "FROM capacity_transfer_item WHERE transfer_key = ? ORDER BY route_id",
                TRANSFER_ITEM_MAPPER, transferKey);
    }

    /** 插入转配航线明细。 */
    public void insertTransferItem(TransferItemPo po) {
        jdbc.update("INSERT INTO capacity_transfer_item "
                        + "(transfer_key, route_id, expected_version, new_version, "
                        + "source_cell_id, source_bucket, target_cell_id, target_bucket, "
                        + "review_id, before_cells, after_cells) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.transferKey(), po.routeId(), po.expectedVersion(), po.newVersion(),
                po.sourceCellId(), po.sourceBucket(), po.targetCellId(), po.targetBucket(),
                po.reviewId(), po.beforeCells(), po.afterCells());
    }

    /** 查询转配涉及桶的冻结占用（按单元、桶稳定排序）。 */
    public List<TransferBucketPo> findTransferBuckets(String transferKey) {
        return jdbc.query("SELECT transfer_key, cell_id, bucket_start, used_before, used_after, max_flights "
                        + "FROM capacity_transfer_bucket WHERE transfer_key = ? "
                        + "ORDER BY cell_id, bucket_start",
                TRANSFER_BUCKET_MAPPER, transferKey);
    }

    /** 插入转配桶冻结记录。 */
    public void insertTransferBucket(TransferBucketPo po) {
        jdbc.update("INSERT INTO capacity_transfer_bucket "
                        + "(transfer_key, cell_id, bucket_start, used_before, used_after, max_flights) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                po.transferKey(), po.cellId(), po.bucketStart(),
                po.usedBefore(), po.usedAfter(), po.maxFlights());
    }
}
