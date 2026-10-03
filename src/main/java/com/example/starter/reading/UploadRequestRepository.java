package com.example.starter.reading;

import com.example.starter.common.UtcTimes;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 测温上传幂等记录持久化。
 */
@Repository
public class UploadRequestRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UploadRequestRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 幂等记录视图。
     */
    public record UploadRequest(String requestId, long batchId, String payloadHash, String responseBody) {
    }

    /**
     * 按请求号查询幂等记录。
     */
    public Optional<UploadRequest> find(String requestId) {
        List<UploadRequest> list = jdbc.query(
                "SELECT request_id, batch_id, payload_hash, response_body FROM reading_upload_request"
                        + " WHERE request_id = ?",
                (rs, rowNum) -> new UploadRequest(rs.getString("request_id"), rs.getLong("batch_id"),
                        rs.getString("payload_hash"), rs.getString("response_body")),
                requestId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 保存幂等记录，request_id 为主键，并发重复时由主键约束拒绝。
     */
    public void insert(String requestId, long batchId, String payloadHash, String responseBody) {
        jdbc.update("INSERT INTO reading_upload_request (request_id, batch_id, payload_hash,"
                        + " response_body, created_at) VALUES (?,?,?,?,?)",
                requestId, batchId, payloadHash, responseBody, UtcTimes.toDb(clock.instant()));
    }
}