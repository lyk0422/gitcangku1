package com.example.starter.consent.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 账本域幂等请求持久化访问：复用 idempotency_request 表，操作类型为
 * QUOTA_ADJUST / RESTRICTED_QUERY / QUOTA_CLEAR。
 *
 * <p>采用“事务内先占键（PENDING）、同事务补快照（SUCCEEDED）”的方式：
 * <ul>
 *   <li>业务事务开始即插入 PENDING 占位行并提交/回滚随业务事务；失败回滚自动释放，不占键；</li>
 *   <li>并发同 requestId 的第二个事务因唯一约束阻塞或冲突，只能重放先提交者的快照，
 *       从而保证同一 requestId 的核销最多发生一次。</li>
 * </ul>
 */
@Repository
public class LedgerIdempotencyRepository {

    static final String PENDING = "PENDING";
    static final String SUCCEEDED = "SUCCEEDED";

    private static final RowMapper<Row> MAPPER = (rs, rowNum) -> new Row(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("params_fingerprint"),
            rs.getString("response_body"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public LedgerIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等请求行。
     *
     * @param requestId         幂等请求标识
     * @param operation         操作类型
     * @param paramsFingerprint 规范化参数指纹
     * @param responseBody      首次完整成功响应快照（JSON）；PENDING 状态为空字符串
     * @param status            PENDING 处理中 / SUCCEEDED 已成功
     */
    public record Row(String requestId, String operation, String paramsFingerprint,
                      String responseBody, String status) {

        public boolean succeeded() {
            return SUCCEEDED.equals(status);
        }
    }

    /**
     * 插入 PENDING 占位行（须在业务事务内调用）；唯一约束冲突说明已有并发事务占键。
     */
    void insertPending(String requestId, String operation, String paramsFingerprint) {
        jdbc.update(
                "INSERT INTO idempotency_request (request_id, operation, params_fingerprint, response_body, status)"
                        + " VALUES (?, ?, ?, '', ?)",
                requestId, operation, paramsFingerprint, PENDING);
    }

    /**
     * 同事务把占位行补齐为 SUCCEEDED 并写入首次完整成功响应快照。
     */
    void complete(String requestId, String responseBody) {
        int updated = jdbc.update(
                "UPDATE idempotency_request SET response_body = ?, status = ? WHERE request_id = ? AND status = ?",
                responseBody, SUCCEEDED, requestId, PENDING);
        if (updated != 1) {
            throw new IllegalStateException("幂等占位行补齐失败: " + requestId);
        }
    }

    /**
     * 读取幂等行（可能为并发事务的 PENDING 行）。
     */
    Optional<Row> find(String requestId) {
        List<Row> rows = jdbc.query(
                "SELECT request_id, operation, params_fingerprint, response_body, status"
                        + " FROM idempotency_request WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }
}
