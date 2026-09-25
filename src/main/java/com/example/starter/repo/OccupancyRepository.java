package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 高度层占用数据访问。占用时段为左闭右开（epoch 毫秒，UTC）。
 */
@Repository
public class OccupancyRepository {

    private final JdbcTemplate jdbc;

    public OccupancyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<OccupancyPo> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyPo(
            rs.getString("occupancy_id"),
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getString("zone_id"),
            rs.getInt("band_lower"),
            rs.getInt("band_upper"),
            rs.getLong("start_time"),
            rs.getLong("end_time"),
            rs.getString("status"),
            rs.getLong("created_at"),
            (Long) rs.getObject("cancelled_at"));

    private static final String OCCUPANCY_COLUMNS =
            "occupancy_id, review_id, route_id, zone_id, band_lower, band_upper, "
                    + "start_time, end_time, status, created_at, cancelled_at";

    /** 按占用标识查询，不存在返回 null。 */
    public OccupancyPo findOccupancy(String occupancyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + OCCUPANCY_COLUMNS + " FROM altitude_occupancy WHERE occupancy_id = ?",
                    OCCUPANCY_MAPPER, occupancyId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询同一审核在指定区域高度带是否已有占用记录（任何状态，含已取消），不存在返回 null。 */
    public OccupancyPo findForReviewBand(String reviewId, String zoneId, int bandLower) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + OCCUPANCY_COLUMNS
                            + " FROM altitude_occupancy WHERE review_id = ? AND zone_id = ? AND band_lower = ?",
                    OCCUPANCY_MAPPER, reviewId, zoneId, bandLower);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 插入占用记录（调用方负责事务）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO altitude_occupancy "
                        + "(occupancy_id, review_id, route_id, zone_id, band_lower, band_upper, "
                        + "start_time, end_time, status, created_at, cancelled_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.reviewId(), po.routeId(), po.zoneId(),
                po.bandLower(), po.bandUpper(), po.startTime(), po.endTime(),
                po.status(), po.createdAt(), po.cancelledAt());
    }

    /**
     * 统计某区域某高度带与给定时段（左闭右开）重叠的 ACTIVE 占用数。
     * 调用方必须已持有该高度带行级排他锁，使“计数 + 插入”对并发占用原子。
     * 端点相接（一端 start 等于另一端 end）不算重叠，不计容量。
     */
    public int countActiveOverlaps(String zoneId, int bandLower, long startTime, long endTime) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupancy "
                        + "WHERE zone_id = ? AND band_lower = ? AND status = 'ACTIVE' "
                        + "AND start_time < ? AND end_time > ?",
                Integer.class, zoneId, bandLower, endTime, startTime);
        return count == null ? 0 : count;
    }

    /**
     * 取消占用：仅当当前状态为 ACTIVE 时置为 CANCELLED 并记录取消时间。
     *
     * @return 更新行数；0 表示记录不存在或已取消
     */
    public int cancelIfActive(String occupancyId, long cancelledAt) {
        return jdbc.update(
                "UPDATE altitude_occupancy SET status = 'CANCELLED', cancelled_at = ? "
                        + "WHERE occupancy_id = ? AND status = 'ACTIVE'",
                cancelledAt, occupancyId);
    }

    /**
     * 查询指定时段内与给定时间窗（左闭右开）重叠的占用（含 ACTIVE 与 CANCELLED，
     * 按时段起始、占用标识升序）。供按时段占用查询使用。
     */
    public List<OccupancyPo> findOccupanciesInWindow(String zoneId, Integer bandLower,
                                                     long windowStart, long windowEnd) {
        StringBuilder sql = new StringBuilder("SELECT ").append(OCCUPANCY_COLUMNS)
                .append(" FROM altitude_occupancy WHERE start_time < ? AND end_time > ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(windowEnd);
        args.add(windowStart);
        if (zoneId != null) {
            sql.append(" AND zone_id = ?");
            args.add(zoneId);
        }
        if (bandLower != null) {
            sql.append(" AND band_lower = ?");
            args.add(bandLower);
        }
        sql.append(" ORDER BY start_time, occupancy_id");
        return jdbc.query(sql.toString(), OCCUPANCY_MAPPER, args.toArray());
    }
}
