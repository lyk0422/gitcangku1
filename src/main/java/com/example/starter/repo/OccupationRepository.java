package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 高度层占用数据访问。
 * 容量计数只统计 ACTIVE 且时间窗（左闭右开）与目标重叠的记录；
 * 取消仅翻转状态，行作为历史永久保留。
 */
@Repository
public class OccupationRepository {

    private final JdbcTemplate jdbc;

    public OccupationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "occupation_id, review_id, route_id, zone_id, band_id, start_utc, end_utc, "
                    + "cruise_altitude, status, request_id, created_at, cancelled_at";

    private static final RowMapper<OccupationPo> MAPPER = (rs, n) -> new OccupationPo(
            rs.getString("occupation_id"),
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getString("zone_id"),
            rs.getString("band_id"),
            rs.getLong("start_utc"),
            rs.getLong("end_utc"),
            rs.getInt("cruise_altitude"),
            rs.getString("status"),
            rs.getString("request_id"),
            rs.getLong("created_at"),
            (Long) rs.getObject("cancelled_at"));

    /** 按 occupationId 查询占用记录，不存在返回 null。 */
    public OccupationPo findOccupation(String occupationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM altitude_occupation WHERE occupation_id = ?",
                MAPPER, occupationId).stream().findFirst().orElse(null);
    }

    /**
     * 统计某高度带在半开时间窗 [startUtc, endUtc) 内重叠的 ACTIVE 占用数。
     * 半开区间重叠条件：existing.start_utc &lt; endUtc 且 existing.end_utc &gt; startUtc；
     * 端点相接（existing.end_utc == startUtc 等）不算重叠。
     * 调用方须在持带级行锁的事务内调用，保证计数与后续插入串行。
     */
    public int countActiveOverlapping(String bandId, long startUtc, long endUtc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM altitude_occupation "
                        + "WHERE band_id = ? AND status = 'ACTIVE' "
                        + "AND start_utc < ? AND end_utc > ?",
                Integer.class, bandId, endUtc, startUtc);
        return count == null ? 0 : count;
    }

    /** 插入占用记录（调用方负责事务与容量校验）。 */
    public void insertOccupation(OccupationPo po) {
        jdbc.update("INSERT INTO altitude_occupation "
                        + "(occupation_id, review_id, route_id, zone_id, band_id, start_utc, end_utc, "
                        + "cruise_altitude, status, request_id, created_at, cancelled_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.occupationId(), po.reviewId(), po.routeId(), po.zoneId(), po.bandId(),
                po.startUtc(), po.endUtc(), po.cruiseAltitude(), po.status(),
                po.requestId(), po.createdAt(), po.cancelledAt());
    }

    /**
     * 条件取消占用：仅当当前状态为 ACTIVE 时置为 CANCELLED 并记录取消时间。
     *
     * @return 更新行数；0 表示记录不存在或已取消
     */
    public int cancelIfActive(String occupationId, long cancelledAt) {
        return jdbc.update(
                "UPDATE altitude_occupation SET status = 'CANCELLED', cancelled_at = ? "
                        + "WHERE occupation_id = ? AND status = 'ACTIVE'",
                cancelledAt, occupationId);
    }

    /**
     * 按时段查询占用（含已取消历史）：时间窗与 [fromUtc, toUtc) 半开重叠，
     * zoneId 为 null 时不限区域；按创建时间、占用标识升序返回。
     */
    public List<OccupationPo> findOverlapping(String zoneId, long fromUtc, long toUtc) {
        if (zoneId == null) {
            return jdbc.query(
                    "SELECT " + COLUMNS + " FROM altitude_occupation "
                            + "WHERE start_utc < ? AND end_utc > ? "
                            + "ORDER BY created_at, occupation_id",
                    MAPPER, toUtc, fromUtc);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM altitude_occupation "
                        + "WHERE zone_id = ? AND start_utc < ? AND end_utc > ? "
                        + "ORDER BY created_at, occupation_id",
                MAPPER, zoneId, toUtc, fromUtc);
    }
}
