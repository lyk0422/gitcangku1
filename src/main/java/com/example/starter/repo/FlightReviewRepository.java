package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 飞行审核不可变快照数据访问。
 * 最终成功（CLEAR）审核以 final_key = flight_key 唯一约束，保证同 flightKey 只形成一次。
 */
@Repository
public class FlightReviewRepository {

    private final JdbcTemplate jdbc;

    public FlightReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "review_id, flight_key, final_key, finalized, request_id, route_id, route_version, "
                    + "airspace_version, permit_id, permit_version, review_at, conclusion, "
                    + "response_json, created_at";

    private static final RowMapper<FlightReviewPo> MAPPER = (rs, n) -> new FlightReviewPo(
            rs.getString("review_id"),
            rs.getString("flight_key"),
            rs.getBoolean("finalized"),
            rs.getString("request_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("permit_id"),
            (Integer) rs.getObject("permit_version"),
            rs.getLong("review_at"),
            rs.getString("conclusion"),
            rs.getString("response_json"),
            rs.getLong("created_at"));

    /** 查询某 flightKey 的最终（CLEAR）审核；不存在或仅有 BLOCKED 尝试时返回 null。 */
    public FlightReviewPo findFinalByFlightKey(String flightKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM flight_review WHERE final_key = ?",
                MAPPER, flightKey).stream().findFirst().orElse(null);
    }

    /** 按 requestId 查询审核快照，不存在返回 null。 */
    public FlightReviewPo findByRequestId(String requestId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM flight_review WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst().orElse(null);
    }

    /** 按 reviewId 查询审核快照，不存在返回 null。 */
    public FlightReviewPo findByReviewId(String reviewId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM flight_review WHERE review_id = ?",
                MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某 flightKey 的全部审核快照（含 BLOCKED 尝试，按时间、reviewId 正序）。 */
    public List<FlightReviewPo> findHistoryByFlightKey(String flightKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM flight_review WHERE flight_key = ? "
                        + "ORDER BY created_at, review_id",
                MAPPER, flightKey);
    }

    /** 追加一条不可变审核快照（调用方负责事务）。 */
    public void insertFlightReview(FlightReviewPo po) {
        jdbc.update("INSERT INTO flight_review "
                        + "(review_id, flight_key, final_key, finalized, request_id, route_id, "
                        + "route_version, airspace_version, permit_id, permit_version, review_at, "
                        + "conclusion, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.flightKey(), po.finalized() ? po.flightKey() : null,
                po.finalized(), po.requestId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), po.permitId(), po.permitVersion(), po.reviewAt(),
                po.conclusion(), po.responseJson(), po.createdAt());
    }
}
