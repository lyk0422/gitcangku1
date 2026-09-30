package com.example.starter.germination.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等请求表访问。
 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IdempotentRecord> MAPPER = (rs, n) ->
            new IdempotentRecord(rs.getString("request_id"),
                    rs.getString("operation"),
                    rs.getString("params_hash"),
                    "DONE".equals(rs.getString("status")),
                    rs.getString("response_json"),
                    rs.getTimestamp("created_at").toInstant());

    /**
     * 尝试登记处理中请求；主键冲突返回 false（请求号已存在）。
     */
    public boolean tryInsertProcessing(String requestId, String operation, String paramsHash, Instant now) {
        int rows = jdbc.update("INSERT INTO idempotent_request "
                        + "(request_id, operation, params_hash, status, response_json, created_at) "
                        + "VALUES (?, ?, ?, 'PROCESSING', NULL, ?)",
                requestId, operation, paramsHash, Timestamp.from(now));
        return rows == 1;
    }

    /** 事务内锁定并读取幂等记录；并发重放将在此等待前序事务结束。 */
    public Optional<IdempotentRecord> findForUpdate(String requestId) {
        return jdbc.query("SELECT request_id, operation, params_hash, status, response_json, created_at "
                        + "FROM idempotent_request WHERE request_id = ? FOR UPDATE",
                MAPPER, requestId).stream().findFirst();
    }

    /** 标记完成并固化首次响应 JSON。 */
    public void markDone(String requestId, String responseJson) {
        jdbc.update("UPDATE idempotent_request SET status = 'DONE', response_json = ? WHERE request_id = ?",
                responseJson, requestId);
    }
}
