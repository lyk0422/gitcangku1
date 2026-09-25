package com.example.starter.calibration.repo;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 复核幂等请求记录持久化。requestId 记录首次请求的参数指纹与结果；
 * 业务失败（400/404/409/422 等）随事务回滚而不占键，仅成功提交（201）与版本失效（410）占用。
 */
@Repository
public class ReviewRequestRepository {

    /**
     * 已占用 requestId 的首次请求快照。
     *
     * @param requestHash 首次请求参数指纹
     * @param httpStatus  首次请求 HTTP 状态码；占用未决（仅并发事务可见）时为 null
     * @param reviewId    首次产生的复核记录 ID（可空）
     */
    public record StoredRequest(String requestHash, Integer httpStatus, Long reviewId) {
    }

    private static final RowMapper<StoredRequest> MAPPER = (rs, rowNum) -> new StoredRequest(
            rs.getString("request_hash"),
            (Integer) rs.getObject("http_status"),
            (Long) rs.getObject("review_id"));

    private final JdbcTemplate jdbc;

    public ReviewRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 占用 requestId（http_status/review_id 暂为 NULL，须在复核事务内调用）；
     * 主键冲突抛出 DuplicateKeyException，由上层串行裁决或重试。
     */
    public void claim(String requestId, String requestHash, Instant claimedAt) {
        jdbc.update("INSERT INTO review_request (request_id, request_hash, http_status, review_id, created_at) "
                        + "VALUES (?, ?, NULL, NULL, ?)",
                requestId, requestHash, JdbcTimes.toDb(claimedAt));
    }

    /**
     * 补全占用请求的首次结果（HTTP 状态码与复核记录 ID）。
     */
    public void complete(String requestId, int httpStatus, Long reviewId) {
        jdbc.update("UPDATE review_request SET http_status = ?, review_id = ? WHERE request_id = ?",
                httpStatus, reviewId, requestId);
    }

    /**
     * 查询 requestId 的首次请求快照。
     */
    public Optional<StoredRequest> find(String requestId) {
        return jdbc.query("SELECT request_hash, http_status, review_id FROM review_request WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }
}
