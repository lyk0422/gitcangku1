package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 时空桶容量配置与航线版本占用数据访问。
 */
@Repository
public class CapacityRepository {

    private final JdbcTemplate jdbc;

    public CapacityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询容量配置，未配置返回 null（未配置的桶视为不限容量）。 */
    public CapacityConfigPo findConfig(String cellId, long bucketStart) {
        return jdbc.query(
                        "SELECT cell_id, bucket_start, max_flights FROM capacity_config "
                                + "WHERE cell_id = ? AND bucket_start = ?",
                        (rs, n) -> new CapacityConfigPo(rs.getString("cell_id"),
                                rs.getLong("bucket_start"), rs.getInt("max_flights")),
                        cellId, bucketStart)
                .stream().findFirst().orElse(null);
    }

    /**
     * 配置或调整容量上限（upsert）。调用方必须已持有协调锁（coord_lock），
     * 因此先更新后插入不会产生并发冲突。
     */
    public void upsertConfig(String cellId, long bucketStart, int maxFlights, long updatedAt) {
        int updated = jdbc.update(
                "UPDATE capacity_config SET max_flights = ?, updated_at = ? "
                        + "WHERE cell_id = ? AND bucket_start = ?",
                maxFlights, updatedAt, cellId, bucketStart);
        if (updated == 0) {
            jdbc.update("INSERT INTO capacity_config (cell_id, bucket_start, max_flights, updated_at) "
                    + "VALUES (?, ?, ?, ?)", cellId, bucketStart, maxFlights, updatedAt);
        }
    }

    /** 按航线查询当前占用序列（按 seq 升序），无占用返回空列表。 */
    public List<OccupancyPo> findOccupancy(String routeId) {
        return jdbc.query(
                "SELECT route_id, route_version, seq, cell_id, bucket_start, review_id "
                        + "FROM route_occupancy WHERE route_id = ? ORDER BY seq",
                (rs, n) -> new OccupancyPo(rs.getString("route_id"), rs.getInt("route_version"),
                        rs.getInt("seq"), rs.getString("cell_id"), rs.getLong("bucket_start"),
                        rs.getString("review_id")),
                routeId);
    }

    /** 写入一条占用记录（调用方负责事务）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO route_occupancy "
                        + "(route_id, route_version, seq, cell_id, bucket_start, review_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                po.routeId(), po.routeVersion(), po.seq(), po.cellId(), po.bucketStart(),
                po.reviewId());
    }

    /** 删除某航线的全部占用记录，返回删除行数（调用方负责事务）。 */
    public int deleteOccupancyByRoute(String routeId) {
        return jdbc.update("DELETE FROM route_occupancy WHERE route_id = ?", routeId);
    }

    /** 统计某时空桶的全量占用数（含所有航线版本）。 */
    public int countByBucket(String cellId, long bucketStart) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM route_occupancy WHERE cell_id = ? AND bucket_start = ?",
                Integer.class, cellId, bucketStart);
        return count == null ? 0 : count;
    }
}
