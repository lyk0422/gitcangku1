package com.example.starter.calibration.repo;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 复核请求幂等表持久化。仅成功的复核提交占用 request_id；
 * 同键同参重放首次结果，同键异参 409，失败不占键。
 */
@Repository
public class ReviewRequestRepository {

    /** 已占用的请求记录。 */
    public record ReviewRequest(String requestId, String fingerprint, String reviewKey) {
    }

    private final JdbcTemplate jdbc;

    public ReviewRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按请求 ID 查询占用记录（不加锁）。
     */
    public Optional<ReviewRequest> findById(String requestId) {
        return jdbc.query("SELECT request_id, fingerprint, review_key FROM review_request "
                                + "WHERE request_id = ?",
                        (rs, rowNum) -> new ReviewRequest(
                                rs.getString("request_id"),
                                rs.getString("fingerprint"),
                                rs.getString("review_key")),
                        requestId)
                .stream().findFirst();
    }

    /**
     * 占用请求 ID；冲突时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String fingerprint, String reviewKey) {
        jdbc.update("INSERT INTO review_request (request_id, fingerprint, review_key, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, fingerprint, reviewKey, JdbcTimes.toDb(Instant.now()));
    }
}
