package com.example.starter.snow;

import com.example.starter.snow.model.ConfirmationRec;
import com.example.starter.snow.model.DecisionRec;
import com.example.starter.snow.model.ReviewRec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 解除审核、确认记录与解除决定版本的数据访问。
 */
@Repository
public class ReviewDao {

    private final JdbcTemplate jdbc;

    public ReviewDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertReview(String regionId, Instant windowStart, Instant windowEnd,
                             String readingIds, String requestId, Instant now) {
        jdbc.update("INSERT INTO unseal_review (region_id, status, window_start, window_end, reading_ids,"
                        + " first_reviewer, request_id, invalidated_reason, created_at, completed_at)"
                        + " VALUES (?, 'PENDING', ?, ?, ?, NULL, ?, NULL, ?, NULL)",
                regionId, Timestamp.from(windowStart), Timestamp.from(windowEnd), readingIds,
                requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject("SELECT id FROM unseal_review WHERE request_id = ?",
                Long.class, requestId);
        return id == null ? -1L : id;
    }

    public Optional<ReviewRec> findById(long id) {
        List<ReviewRec> rows = jdbc.query("SELECT * FROM unseal_review WHERE id = ?", this::mapReview, id);
        return rows.stream().findFirst();
    }

    public Optional<ReviewRec> findByRequestId(String requestId) {
        List<ReviewRec> rows = jdbc.query("SELECT * FROM unseal_review WHERE request_id = ?",
                this::mapReview, requestId);
        return rows.stream().findFirst();
    }

    public Optional<ReviewRec> findLatest(String regionId) {
        List<ReviewRec> rows = jdbc.query(
                "SELECT * FROM unseal_review WHERE region_id = ? ORDER BY id DESC LIMIT 1",
                this::mapReview, regionId);
        return rows.stream().findFirst();
    }

    /**
     * 将控制区内所有未完成审核标记为失效，返回失效数量。
     */
    public int invalidatePending(String regionId, String reason, Instant now) {
        return jdbc.update("UPDATE unseal_review SET status = 'INVALIDATED', invalidated_reason = ?"
                + " WHERE region_id = ? AND status = 'PENDING'", reason, regionId);
    }

    public void setFirstReviewer(long reviewId, String reviewer) {
        jdbc.update("UPDATE unseal_review SET first_reviewer = ? WHERE id = ?", reviewer, reviewId);
    }

    public void complete(long reviewId, Instant now) {
        jdbc.update("UPDATE unseal_review SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.from(now), reviewId);
    }

    public long insertConfirmation(long reviewId, String reviewer, int seq, String requestId, Instant now) {
        jdbc.update("INSERT INTO unseal_confirmation (review_id, reviewer, seq, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                reviewId, reviewer, seq, requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject("SELECT id FROM unseal_confirmation WHERE request_id = ?",
                Long.class, requestId);
        return id == null ? -1L : id;
    }

    public Optional<ConfirmationRec> findConfirmationByRequestId(String requestId) {
        List<ConfirmationRec> rows = jdbc.query(
                "SELECT * FROM unseal_confirmation WHERE request_id = ?", this::mapConfirmation, requestId);
        return rows.stream().findFirst();
    }

    public List<ConfirmationRec> findConfirmations(long reviewId) {
        return jdbc.query("SELECT * FROM unseal_confirmation WHERE review_id = ? ORDER BY seq",
                this::mapConfirmation, reviewId);
    }

    public long nextVersion(String regionId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM unseal_decision WHERE region_id = ?",
                Long.class, regionId);
        return (max == null ? 0L : max) + 1L;
    }

    public long insertDecision(long reviewId, String regionId, long version, Instant now) {
        jdbc.update("INSERT INTO unseal_decision (review_id, region_id, version, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                reviewId, regionId, version, Timestamp.from(now));
        Long id = jdbc.queryForObject(
                "SELECT id FROM unseal_decision WHERE review_id = ?", Long.class, reviewId);
        return id == null ? -1L : id;
    }

    public List<DecisionRec> findDecisions(String regionId) {
        return jdbc.query("SELECT * FROM unseal_decision WHERE region_id = ? ORDER BY version",
                this::mapDecision, regionId);
    }

    private ReviewRec mapReview(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new ReviewRec(rs.getLong("id"), rs.getString("region_id"), rs.getString("status"),
                rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
                rs.getString("reading_ids"), rs.getString("first_reviewer"), rs.getString("request_id"),
                rs.getString("invalidated_reason"), rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private ConfirmationRec mapConfirmation(ResultSet rs, int rowNum) throws SQLException {
        return new ConfirmationRec(rs.getLong("id"), rs.getLong("review_id"), rs.getString("reviewer"),
                rs.getInt("seq"), rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());
    }

    private DecisionRec mapDecision(ResultSet rs, int rowNum) throws SQLException {
        return new DecisionRec(rs.getLong("id"), rs.getLong("review_id"), rs.getString("region_id"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant());
    }
}