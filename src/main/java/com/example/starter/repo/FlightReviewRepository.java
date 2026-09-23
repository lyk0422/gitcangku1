package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

/**
 * 航班豁免审核记录数据访问。
 */
@Repository
public class FlightReviewRepository {

    private final JdbcTemplate jdbc;

    public FlightReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "review_id, flight_key, route_id, route_version, airspace_version, review_at, "
                    + "conclusion, hit_region_keys, permit_key, snapshot_json, request_id, "
                    + "request_hash, created_at";

    private static final RowMapper<FlightReviewPo> MAPPER = (rs, n) -> new FlightReviewPo(
            rs.getString("review_id"),
            rs.getString("flight_key"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getLong("review_at"),
            rs.getString("conclusion"),
            decodeRegionKeys(rs.getString("hit_region_keys")),
            rs.getString("permit_key"),
            rs.getString("snapshot_json"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getLong("created_at"));

    /** 按 reviewId 查询审核记录，不存在返回 null。 */
    public FlightReviewPo findReview(String reviewId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM flight_review WHERE review_id = ?",
                    MAPPER, reviewId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 按 flightKey 查询唯一成功审核记录，不存在返回 null。 */
    public FlightReviewPo findByFlightKey(String flightKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM flight_review WHERE flight_key = ?",
                    MAPPER, flightKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 插入成功审核记录（调用方负责事务）。 */
    public void insertReview(FlightReviewPo po) {
        jdbc.update("INSERT INTO flight_review "
                        + "(review_id, flight_key, route_id, route_version, airspace_version, "
                        + "review_at, conclusion, hit_region_keys, permit_key, snapshot_json, "
                        + "request_id, request_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.flightKey(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.reviewAt(), po.conclusion(),
                encodeRegionKeys(po.hitRegionKeys()), po.permitKey(), po.snapshotJson(),
                po.requestId(), po.requestHash(), po.createdAt());
    }

    /** 查询某航线全部审核（按创建时间、reviewId 倒序）。 */
    public List<FlightReviewPo> findByRoute(String routeId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM flight_review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC",
                MAPPER, routeId);
    }

    /** regionKey 列表编码（字典序去重由调用方保证），逗号拼接；空列表为空串。 */
    public static String encodeRegionKeys(List<String> regionKeys) {
        return String.join(",", regionKeys);
    }

    /** regionKey 列表解码，空串返回空列表。 */
    public static List<String> decodeRegionKeys(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(text.split(","));
    }
}
