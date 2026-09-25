package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 航班、跑道风险快照与批量审查数据访问。
 */
@Repository
public class FlightRepository {

    private final JdbcTemplate jdbc;

    public FlightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlightPo> FLIGHT_MAPPER = (rs, n) -> new FlightPo(
            rs.getString("flight_id"),
            rs.getString("route_id"),
            rs.getString("route_type"),
            rs.getString("event_no"),
            rs.getString("dep_runway_id"),
            rs.getLong("dep_time_utc"),
            rs.getString("arr_runway_id"),
            rs.getLong("arr_time_utc"),
            rs.getString("status"));

    private static final RowMapper<FlightRiskPo> RISK_MAPPER = (rs, n) -> new FlightRiskPo(
            rs.getString("flight_id"),
            rs.getString("closure_id"),
            rs.getString("runway_id"),
            rs.getLong("start_utc"),
            rs.getLong("end_utc"),
            rs.getBoolean("allow_emergency"),
            rs.getString("operator"),
            rs.getInt("runway_version"),
            rs.getLong("snapshot_at"));

    private static final RowMapper<FlightReviewItemPo> ITEM_MAPPER = (rs, n) -> new FlightReviewItemPo(
            rs.getString("review_id"),
            rs.getString("flight_id"),
            rs.getString("result"),
            rs.getString("reason"),
            rs.getString("detail"));

    // ============================ 航班 ============================

    /** 按 flightId 查询航班，不存在返回 null。 */
    public FlightPo findFlight(String flightId) {
        try {
            return jdbc.queryForObject(
                    "SELECT flight_id, route_id, route_type, event_no, dep_runway_id, dep_time_utc, "
                            + "arr_runway_id, arr_time_utc, status FROM flight WHERE flight_id = ?",
                    FLIGHT_MAPPER, flightId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对航班行做真实更新（touch 加一）取得行级排他锁并读取航班；
     * 航班不存在返回 null。
     */
    public FlightPo findFlightForUpdate(String flightId) {
        int locked = jdbc.update("UPDATE flight SET touch = touch + 1 WHERE flight_id = ?", flightId);
        if (locked == 0) {
            return null;
        }
        return findFlight(flightId);
    }

    /** 登记航班（初始状态 PENDING，调用方负责事务）。 */
    public void insertFlight(FlightPo po) {
        jdbc.update("INSERT INTO flight "
                        + "(flight_id, route_id, route_type, event_no, dep_runway_id, dep_time_utc, "
                        + "arr_runway_id, arr_time_utc, status, approved_at, touch) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)",
                po.flightId(), po.routeId(), po.routeType(), po.eventNo(),
                po.depRunwayId(), po.depTimeUtc(), po.arrRunwayId(), po.arrTimeUtc(), po.status());
    }

    /** 查询指定状态下、起降段涉及某跑道的全部航班（用于关闭窗口风险标记）。 */
    public List<FlightPo> findByStatusAndRunway(String status, String runwayId) {
        return jdbc.query("SELECT flight_id, route_id, route_type, event_no, dep_runway_id, "
                        + "dep_time_utc, arr_runway_id, arr_time_utc, status FROM flight "
                        + "WHERE status = ? AND (dep_runway_id = ? OR arr_runway_id = ?) "
                        + "ORDER BY flight_id",
                FLIGHT_MAPPER, status, runwayId, runwayId);
    }

    /** 查询占用容量槽位的航班（APPROVED 或 DEPARTED）的起降段。 */
    public List<FlightPo> findCapacityConsumers() {
        return jdbc.query("SELECT flight_id, route_id, route_type, event_no, dep_runway_id, "
                        + "dep_time_utc, arr_runway_id, arr_time_utc, status FROM flight "
                        + "WHERE status IN ('APPROVED', 'DEPARTED') ORDER BY flight_id",
                FLIGHT_MAPPER);
    }

    /** 条件更新航班状态：仅当当前状态等于 expected 时置为 next（同时推进 touch 持行锁）。 */
    public int compareAndSetStatus(String flightId, String expected, String next) {
        return jdbc.update("UPDATE flight SET status = ?, touch = touch + 1 "
                + "WHERE flight_id = ? AND status = ?", next, flightId, expected);
    }

    /** 批量审查整批通过时置为 APPROVED 并记录批准时间（调用方负责事务与前置校验）。 */
    public void markApproved(String flightId, long approvedAt) {
        jdbc.update("UPDATE flight SET status = 'APPROVED', approved_at = ?, touch = touch + 1 "
                + "WHERE flight_id = ?", approvedAt, flightId);
    }

    /** 新关闭窗口命中时置为 RUNWAY_RISK（仅 APPROVED 航班，调用方负责事务）。 */
    public void markRunwayRisk(String flightId) {
        jdbc.update("UPDATE flight SET status = 'RUNWAY_RISK', touch = touch + 1 "
                + "WHERE flight_id = ? AND status = 'APPROVED'", flightId);
    }

    /** 改航：更新起降段并回到 PENDING（调用方负责事务与状态校验）。 */
    public void reroute(String flightId, String depRunwayId, long depTimeUtc,
                        String arrRunwayId, long arrTimeUtc) {
        jdbc.update("UPDATE flight SET dep_runway_id = ?, dep_time_utc = ?, "
                        + "arr_runway_id = ?, arr_time_utc = ?, status = 'PENDING', "
                        + "approved_at = NULL, touch = touch + 1 WHERE flight_id = ?",
                depRunwayId, depTimeUtc, arrRunwayId, arrTimeUtc, flightId);
    }

    /** 转为紧急例外：置为 EMERGENCY、附事件号并回到 PENDING 待重新审查（调用方负责事务）。 */
    public void convertToEmergency(String flightId, String eventNo) {
        jdbc.update("UPDATE flight SET route_type = 'EMERGENCY', event_no = ?, "
                + "status = 'PENDING', approved_at = NULL, touch = touch + 1 WHERE flight_id = ?",
                eventNo, flightId);
    }

    // ============================ 风险快照 ============================

    /** 固化风险快照（调用方负责事务）。 */
    public void insertRisk(FlightRiskPo po) {
        jdbc.update("INSERT INTO flight_risk "
                        + "(flight_id, closure_id, runway_id, start_utc, end_utc, allow_emergency, "
                        + "operator, runway_version, snapshot_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.flightId(), po.closureId(), po.runwayId(), po.startUtc(), po.endUtc(),
                po.allowEmergency(), po.operator(), po.runwayVersion(), po.snapshotAt());
    }

    /** 查询航班全部风险快照（按快照时间、closureId 升序）。 */
    public List<FlightRiskPo> findRisks(String flightId) {
        return jdbc.query("SELECT flight_id, closure_id, runway_id, start_utc, end_utc, "
                        + "allow_emergency, operator, runway_version, snapshot_at "
                        + "FROM flight_risk WHERE flight_id = ? ORDER BY snapshot_at, closure_id",
                RISK_MAPPER, flightId);
    }

    /** 删除航班风险快照（改航或转为紧急例外后重新审查前清除，调用方负责事务）。 */
    public void deleteRisks(String flightId) {
        jdbc.update("DELETE FROM flight_risk WHERE flight_id = ?", flightId);
    }

    // ============================ 批量审查 ============================

    /** 插入批量审查结论（不可变，调用方负责事务）。 */
    public void insertReview(String reviewId, String requestId, boolean approved, long createdAt) {
        jdbc.update("INSERT INTO flight_review (review_id, request_id, approved, created_at) "
                + "VALUES (?, ?, ?, ?)", reviewId, requestId, approved, createdAt);
    }

    /** 插入批量审查明细（不可变，调用方负责事务）。 */
    public void insertReviewItem(FlightReviewItemPo po) {
        jdbc.update("INSERT INTO flight_review_item (review_id, flight_id, result, reason, detail) "
                        + "VALUES (?, ?, ?, ?, ?)",
                po.reviewId(), po.flightId(), po.result(), po.reason(), po.detail());
    }

    /** 查询批量审查结论，不存在返回 null。approved 标志封装在 Boolean 中。 */
    public Boolean findReviewApproved(String reviewId) {
        try {
            return jdbc.queryForObject(
                    "SELECT approved FROM flight_review WHERE review_id = ?",
                    Boolean.class, reviewId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查询批量审查明细（按 flightId 升序）。 */
    public List<FlightReviewItemPo> findReviewItems(String reviewId) {
        return jdbc.query("SELECT review_id, flight_id, result, reason, detail "
                        + "FROM flight_review_item WHERE review_id = ? ORDER BY flight_id",
                ITEM_MAPPER, reviewId);
    }

    /** 查询航班最近一次审查明细（按审查创建时间、reviewId 倒序），没有返回 null。 */
    public FlightReviewItemPo findLatestReviewItem(String flightId) {
        return jdbc.query("SELECT i.review_id, i.flight_id, i.result, i.reason, i.detail "
                        + "FROM flight_review_item i JOIN flight_review r ON i.review_id = r.review_id "
                        + "WHERE i.flight_id = ? ORDER BY r.created_at DESC, i.review_id DESC LIMIT 1",
                ITEM_MAPPER, flightId).stream().findFirst().orElse(null);
    }
}
