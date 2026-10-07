package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.FitRequestRecord;

/**
 * 幂等请求登记持久化。request_id 全局唯一；业务与去重同事务，
 * 失败回滚不占键，竞争冲突由唯一键兜底并在应用层转为重放或 409。
 */
@Repository
public class FitRequestRepository {

    private static final RowMapper<FitRequestRecord> MAPPER = (rs, rowNum) -> new FitRequestRecord(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("resource"),
            rs.getString("canonical_params"),
            rs.getString("response_body"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public FitRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记请求（响应体先置空，业务成功后由 updateResponse 回填）；
     * request_id 冲突时抛出 DuplicateKeyException，由调用方转为重放或 409。
     */
    public void insertPending(String requestId, String operation, String resource,
                              String canonicalParams, Instant createdAt) {
        jdbc.update("INSERT INTO fit_request (request_id, operation, resource, canonical_params, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, '', ?)",
                requestId, operation, resource, canonicalParams, JdbcTimes.toDb(createdAt));
    }

    /**
     * 按请求 ID 查询登记记录。
     */
    public Optional<FitRequestRecord> findById(String requestId) {
        return jdbc.query("SELECT * FROM fit_request WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 回填首次成功的完整响应 JSON（须在同一事务内调用）。
     */
    public void updateResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE fit_request SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
