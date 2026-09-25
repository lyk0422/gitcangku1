package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 高度层占用数据访问。占用记录创建后不可变，取消仅更新状态与取消时间，历史保留。
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
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("zone_id"),
            rs.getString("band_id"),
            rs.getInt("cruise_altitude_m"),
            rs.getLong("start_at"),
            rs.getLong("end_at"),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getLong("created_at"),
            (Long) rs.getObject("cancelled_at"));

    private static final String OCCUPANCY_COLUMNS =
            "occupancy_id, review_id, route_id, route_version, airspace_version, "
                    + "zone_id, band_id, cruise_altitude_m, start_at, end_at, "
                    + "status, request_id, created_at, cancelled_at";

    /** 按 occupancyId 查询占用记录，不存在返回 null。 */
    public OccupancyPo findOccupancy(String occupancyId) {
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + " FROM altitude_occupancy WHERE occupancy_id = ?",
                OCCUPANCY_MAPPER, occupancyId).stream().findFirst().orElse(null);
    }

    /**
     * 统计某高度带上与给定时段重叠、且巡航高度落入带内（消耗容量）的 ACTIVE 占用数。
     * 时段为左闭右开：重叠条件为 existing.start_at &lt; new.end_at 且 existing.end_at &gt; new.start_at。
     * 必须在持有高度带行锁的事务内调用，保证计数与后续插入之间无并发插入。
     */
    public int countActiveConsumingOverlap(String zoneId, String bandId,
                                           long startAt, long endAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupancy o "
                        + "JOIN zone_altitude_band b ON b.zone_id = o.zone_id AND b.band_id = o.band_id "
                        + "WHERE o.zone_id = ? AND o.band_id = ? AND o.status = 'ACTIVE' "
                        + "AND o.start_at < ? AND o.end_at > ? "
                        + "AND o.cruise_altitude_m >= b.lower_m AND o.cruise_altitude_m < b.upper_m",
                Integer.class, zoneId, bandId, endAt, startAt);
        return count == null ? 0 : count;
    }

    /** 查询某区域（可选限定高度带）与给定时段重叠的全部占用记录（含已取消历史），按创建时间排序。 */
    public List<OccupancyPo> findOverlapping(String zoneId, String bandId,
                                             long fromAt, long toAt) {
        if (bandId != null) {
            return jdbc.query(
                    "SELECT " + OCCUPANCY_COLUMNS + " FROM altitude_occupancy "
                            + "WHERE zone_id = ? AND band_id = ? AND start_at < ? AND end_at > ? "
                            + "ORDER BY created_at, occupancy_id",
                    OCCUPANCY_MAPPER, zoneId, bandId, toAt, fromAt);
        }
        return jdbc.query(
                "SELECT " + OCCUPANCY_COLUMNS + " FROM altitude_occupancy "
                        + "WHERE zone_id = ? AND start_at < ? AND end_at > ? "
                        + "ORDER BY created_at, occupancy_id",
                OCCUPANCY_MAPPER, zoneId, toAt, fromAt);
    }

    /** 插入占用记录（调用方负责事务与容量校验）。 */
    public void insertOccupancy(OccupancyPo po) {
        jdbc.update("INSERT INTO altitude_occupancy "
                        + "(occupancy_id, review_id, route_id, route_version, airspace_version, "
                        + "zone_id, band_id, cruise_altitude_m, start_at, end_at, "
                        + "status, request_id, created_at, cancelled_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupancyId(), po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.zoneId(), po.bandId(), po.cruiseAltitudeM(),
                po.startAt(), po.endAt(), po.status(), po.requestId(), po.createdAt(),
                po.cancelledAt());
    }

    /**
     * 条件取消占用：仅当当前状态为 ACTIVE 时置为 CANCELLED 并记录取消时间。
     * 该更新取得占用行写锁，与并发取消互斥；容量随提交立即释放。
     *
     * @return 更新行数；0 表示占用不存在或已取消
     */
    public int cancelIfActive(String occupancyId, long cancelledAt) {
        return jdbc.update(
                "UPDATE altitude_occupancy SET status = 'CANCELLED', cancelled_at = ? "
                        + "WHERE occupancy_id = ? AND status = 'ACTIVE'",
                cancelledAt, occupancyId);
    }
}
