package com.example.snowpack.repo;

import com.example.snowpack.repo.Rows.DecisionRow;
import com.example.snowpack.repo.Rows.ReviewRow;
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

/** 解除审核单与解除决定版本存取。 */
@Repository
public class ReviewRepository {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_INVALIDATED = "INVALIDATED";
    public static final String REASON_OVER_LIMIT_READING = "OVER_LIMIT_READING";

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, rowNum) -> new ReviewRow(
            rs.getLong("id"), rs.getString("zone_id"), rs.getLong("closure_id"), rs.getString("status"),
            rs.getString("first_reviewer"), rs.getString("second_reviewer"),
            rs.getString("first_request_id"), rs.getString("second_request_id"),
            rs.getLong("reading_version"), rs.getLong("window_head_reading_id"),
            rs.getString("invalidate_reason"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());

    private static final RowMapper<DecisionRow> DECISION_MAPPER = (rs, rowNum) -> new DecisionRow(
            rs.getLong("id"), rs.getLong("review_id"), rs.getString("zone_id"),
            rs.getInt("version_no"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertReview(String zoneId, long closureId, String firstReviewer, String firstRequestId,
                             long readingVersion, long windowHeadReadingId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO release_review (zone_id, closure_id, status, first_reviewer, second_reviewer,"
                            + " first_request_id, second_request_id, reading_version, window_head_reading_id,"
                            + " invalidate_reason, created_at, completed_at)"
                            + " VALUES (?,?,?,?,NULL,?,NULL,?,?,NULL,?,NULL)",
                    new String[] {"id"});
            ps.setString(1, zoneId);
            ps.setLong(2, closureId);
            ps.setString(3, STATUS_IN_PROGRESS);
            ps.setString(4, firstReviewer);
            ps.setString(5, firstRequestId);
            ps.setLong(6, readingVersion);
            ps.setLong(7, windowHeadReadingId);
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReviewRow> findInProgress(String zoneId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM release_review WHERE zone_id = ? AND status = ? ORDER BY id DESC LIMIT 1",
                    REVIEW_MAPPER, zoneId, STATUS_IN_PROGRESS));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<ReviewRow> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM release_review WHERE id = ?", REVIEW_MAPPER, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<ReviewRow> findByFirstRequestId(String zoneId, String requestId) {
        return findByRequestColumn(zoneId, "first_request_id", requestId);
    }

    public Optional<ReviewRow> findBySecondRequestId(String zoneId, String requestId) {
        return findByRequestColumn(zoneId, "second_request_id", requestId);
    }

    private Optional<ReviewRow> findByRequestColumn(String zoneId, String column, String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM release_review WHERE zone_id = ? AND " + column + " = ?",
                    REVIEW_MAPPER, zoneId, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 将区域内所有未完成审核标记为失效，返回失效条数。 */
    public int invalidateInProgress(String zoneId, String reason) {
        return jdbc.update(
                "UPDATE release_review SET status = ?, invalidate_reason = ? WHERE zone_id = ? AND status = ?",
                STATUS_INVALIDATED, reason, zoneId, STATUS_IN_PROGRESS);
    }

    public void complete(long reviewId, String secondReviewer, String secondRequestId, Instant completedAt) {
        jdbc.update("UPDATE release_review SET status = ?, second_reviewer = ?, second_request_id = ?,"
                        + " completed_at = ? WHERE id = ?",
                STATUS_COMPLETED, secondReviewer, secondRequestId, Timestamp.from(completedAt), reviewId);
    }

    public Optional<ReviewRow> findLatest(String zoneId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM release_review WHERE zone_id = ? ORDER BY id DESC LIMIT 1",
                    REVIEW_MAPPER, zoneId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public long insertDecision(long reviewId, String zoneId, int versionNo, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO release_decision (review_id, zone_id, version_no, created_at) VALUES (?,?,?,?)",
                    new String[] {"id"});
            ps.setLong(1, reviewId);
            ps.setString(2, zoneId);
            ps.setInt(3, versionNo);
            ps.setTimestamp(4, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public int nextVersionNo(String zoneId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM release_decision WHERE zone_id = ?",
                Integer.class, zoneId);
        return value == null ? 1 : value;
    }

    public Optional<DecisionRow> findDecisionByReviewId(long reviewId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM release_decision WHERE review_id = ?", DECISION_MAPPER, reviewId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<DecisionRow> listDecisions(String zoneId) {
        return jdbc.query("SELECT * FROM release_decision WHERE zone_id = ? ORDER BY id",
                DECISION_MAPPER, zoneId);
    }
}