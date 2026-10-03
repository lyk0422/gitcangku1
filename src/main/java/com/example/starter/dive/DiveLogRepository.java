package com.example.starter.dive;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 潜水作业记录的JDBC持久化，全部使用参数绑定。
 */
@Repository
public class DiveLogRepository {

    private static final RowMapper<LogRow> LOG_MAPPER = (rs, n) -> new LogRow(
            rs.getLong("id"), rs.getInt("plan_max_depth_m"), rs.getInt("plan_max_duration_min"),
            toInstant(rs, "operation_start_utc"), toInstant(rs, "operation_end_utc"),
            rs.getInt("point_version"), toInstant(rs, "created_at"));

    private static final RowMapper<PointRow> POINT_MAPPER = (rs, n) -> new PointRow(
            rs.getLong("id"), rs.getLong("dive_log_id"), rs.getInt("seq"),
            toInstant(rs, "observed_at_utc"), rs.getDouble("depth_m"),
            rs.getInt("log_version"), rs.getBoolean("errata"));

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, n) -> new ReviewRow(
            rs.getLong("id"), rs.getLong("dive_log_id"), rs.getInt("point_version"),
            rs.getInt("point_count"), rs.getString("decision"), rs.getString("anomalies"),
            rs.getString("status"), rs.getString("request_id"), toInstant(rs, "created_at"));

    private static final RowMapper<ReReviewRow> RE_REVIEW_MAPPER = (rs, n) -> new ReReviewRow(
            rs.getLong("id"), rs.getLong("dive_log_id"), rs.getLong("stale_review_id"),
            rs.getInt("from_point_version"), rs.getInt("to_point_version"),
            rs.getInt("appended_points"), toInstant(rs, "created_at"));

    private static final RowMapper<IdemRow> IDEM_MAPPER = (rs, n) -> new IdemRow(
            rs.getLong("id"), rs.getString("request_hash"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public DiveLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 作业主表行。 */
    public record LogRow(long id, int planMaxDepthM, int planMaxDurationMin,
            Instant operationStartUtc, Instant operationEndUtc, int pointVersion, Instant createdAt) {
    }

    /** 点位行。 */
    public record PointRow(long id, long diveLogId, int seq, Instant observedAtUtc,
            double depthM, int logVersion, boolean errata) {
    }

    /** 审核行。 */
    public record ReviewRow(long id, long diveLogId, int pointVersion, int pointCount,
            String decision, String anomalies, String status, String requestId, Instant createdAt) {
    }

    /** 复核链行。 */
    public record ReReviewRow(long id, long diveLogId, long staleReviewId,
            int fromPointVersion, int toPointVersion, int appendedPoints, Instant createdAt) {
    }

    /** 幂等登记行。 */
    public record IdemRow(long id, String requestHash, int responseStatus, String responseBody) {
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("未返回自增主键");
        }
        return key.longValue();
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public long insertLog(int planMaxDepthM, int planMaxDurationMin, Instant operationStartUtc,
            Instant operationEndUtc, Instant createdAt) {
        return insert("INSERT INTO dive_log (plan_max_depth_m, plan_max_duration_min,"
                        + " operation_start_utc, operation_end_utc, point_version, created_at)"
                        + " VALUES (?, ?, ?, ?, 0, ?)",
                planMaxDepthM, planMaxDurationMin, Timestamp.from(operationStartUtc),
                Timestamp.from(operationEndUtc), Timestamp.from(createdAt));
    }

    public Optional<LogRow> findLog(long id) {
        return queryOne("SELECT * FROM dive_log WHERE id = ?", LOG_MAPPER, id);
    }

    /** 按主键锁定作业行（SELECT ... FOR UPDATE），用于串行化追加与审核。 */
    public Optional<LogRow> lockLog(long id) {
        return queryOne("SELECT * FROM dive_log WHERE id = ? FOR UPDATE", LOG_MAPPER, id);
    }

    public void updatePointVersion(long logId, int newVersion) {
        jdbc.update("UPDATE dive_log SET point_version = ? WHERE id = ?", newVersion, logId);
    }

    public int maxPointSeq(long logId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(seq) FROM depth_point WHERE dive_log_id = ?", Integer.class, logId);
        return max == null ? 0 : max;
    }

    public void insertPoint(long logId, int seq, Instant observedAtUtc, double depthM,
            int logVersion, boolean errata, Instant createdAt) {
        jdbc.update("INSERT INTO depth_point (dive_log_id, seq, observed_at_utc, depth_m,"
                        + " log_version, errata, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                logId, seq, Timestamp.from(observedAtUtc), depthM, logVersion, errata,
                Timestamp.from(createdAt));
    }

    public List<PointRow> findPoints(long logId) {
        return jdbc.query("SELECT * FROM depth_point WHERE dive_log_id = ? ORDER BY seq",
                POINT_MAPPER, logId);
    }

    public long insertReview(long logId, int pointVersion, int pointCount, String decision,
            String anomalies, String status, String requestId, Instant createdAt) {
        return insert("INSERT INTO dive_review (dive_log_id, point_version, point_count, decision,"
                        + " anomalies, status, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                logId, pointVersion, pointCount, decision, anomalies, status, requestId,
                Timestamp.from(createdAt));
    }

    public Optional<ReviewRow> findReview(long logId, int pointVersion, String decision) {
        return queryOne("SELECT * FROM dive_review WHERE dive_log_id = ? AND point_version = ?"
                + " AND decision = ?", REVIEW_MAPPER, logId, pointVersion, decision);
    }

    public Optional<ReviewRow> findCurrentReview(long logId) {
        return queryOne("SELECT * FROM dive_review WHERE dive_log_id = ? AND status = 'CURRENT'"
                + " ORDER BY id DESC LIMIT 1", REVIEW_MAPPER, logId);
    }

    public List<ReviewRow> findReviews(long logId) {
        return jdbc.query("SELECT * FROM dive_review WHERE dive_log_id = ? ORDER BY id",
                REVIEW_MAPPER, logId);
    }

    public void markReviewsStale(long logId) {
        jdbc.update("UPDATE dive_review SET status = 'STALE' WHERE dive_log_id = ?"
                + " AND status = 'CURRENT'", logId);
    }

    public long insertReReview(long logId, long staleReviewId, int fromPointVersion,
            int toPointVersion, int appendedPoints, Instant createdAt) {
        return insert("INSERT INTO re_review (dive_log_id, stale_review_id, from_point_version,"
                        + " to_point_version, appended_points, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                logId, staleReviewId, fromPointVersion, toPointVersion, appendedPoints,
                Timestamp.from(createdAt));
    }

    public List<ReReviewRow> findReReviews(long logId) {
        return jdbc.query("SELECT * FROM re_review WHERE dive_log_id = ? ORDER BY id",
                RE_REVIEW_MAPPER, logId);
    }

    public Optional<IdemRow> findIdempotency(String operation, String resourceKey, String requestId) {
        return queryOne("SELECT * FROM idempotency_record WHERE operation = ?"
                + " AND resource_key = ? AND request_id = ?", IDEM_MAPPER,
                operation, resourceKey, requestId);
    }

    public void insertIdempotency(String operation, String resourceKey, String requestId,
            String requestHash, int responseStatus, String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (operation, resource_key, request_id,"
                        + " request_hash, response_status, response_body, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                operation, resourceKey, requestId, requestHash, responseStatus, responseBody,
                Timestamp.from(createdAt));
    }
}