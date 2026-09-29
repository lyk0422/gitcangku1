package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotentRequest;

/**
 * 幂等请求持久化。仅成功结论占键；同 requestId 同参重放返回首次响应快照，异参由服务层判 409。
 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 requestId 查询并加行锁（须在事务内调用），串行化同键并发请求。
     */
    public Optional<IdempotentRequest> findByIdForUpdate(String requestId) {
        return jdbc.query(
                        "SELECT * FROM idempotent_request WHERE request_id = ? FOR UPDATE",
                        (rs, n) -> new IdempotentRequest(
                                rs.getString("request_id"),
                                rs.getString("operation"),
                                rs.getString("fingerprint"),
                                rs.getString("response_json"),
                                JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class))),
                        requestId)
                .stream().findFirst();
    }

    /**
     * 事务开始即插入占坑行（response_json 为 NULL）；requestId 冲突抛 DuplicateKeyException。
     * 动作失败随事务回滚删除本行，因此“失败不占键”。
     */
    public void insertPlaceholder(String requestId, String operation,
                                  String fingerprint, java.time.Instant createdAt) {
        jdbc.update("INSERT INTO idempotent_request "
                        + "(request_id, operation, fingerprint, response_json, created_at) "
                        + "VALUES (?, ?, ?, NULL, ?)",
                requestId, operation, fingerprint, JdbcTimes.toDb(createdAt));
    }

    /**
     * 动作成功后回填首次完整响应 JSON（须在同一事务内）。
     */
    public void complete(String requestId, String responseJson) {
        jdbc.update("UPDATE idempotent_request SET response_json = ? WHERE request_id = ?",
                responseJson, requestId);
    }
}
