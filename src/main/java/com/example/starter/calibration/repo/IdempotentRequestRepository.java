package com.example.starter.calibration.repo;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.IdempotentRequest;

/**
 * 写接口幂等记录持久化。request_id 主键：先插占位行占键，业务成功后在同事务内补写响应快照；
 * 业务失败随事务回滚删除占位，因此失败不占键。
 */
@Repository
public class IdempotentRequestRepository {

    private static final RowMapper<IdempotentRequest> MAPPER = (rs, rowNum) -> new IdempotentRequest(
            rs.getString("request_id"),
            rs.getString("operator"),
            rs.getString("operation_type"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_snapshot"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试插入占位行（http_status=0、快照为空串）占键。同 requestId 并发/重放时唯一插入者返回 true，
     * 其余抛唯一键冲突后返回 false；占位行在外层事务回滚时一并消失。
     */
    public boolean tryInsertPlaceholder(String requestId, String operator,
                                        String operationType, String fingerprint) {
        try {
            jdbc.update("INSERT INTO idempotent_request "
                            + "(request_id, operator, operation_type, fingerprint, http_status, "
                            + "response_snapshot, created_at) VALUES (?, ?, ?, ?, 0, '', ?)",
                    requestId, operator, operationType, fingerprint,
                    JdbcTimes.toDb(java.time.Instant.now()));
            return true;
        } catch (DuplicateKeyException | CannotAcquireLockException ex) {
            // 前者：前序事务已提交（确为重放/异参）；后者：前序事务仍持有键锁，交由上层等待裁决。
            return false;
        }
    }

    /**
     * 业务成功后在同事务内补写首次完整响应快照与 HTTP 状态码。
     */
    public void finalizeSuccess(String requestId, int httpStatus, String responseSnapshot) {
        jdbc.update("UPDATE idempotent_request SET http_status = ?, response_snapshot = ? "
                        + "WHERE request_id = ?",
                httpStatus, responseSnapshot, requestId);
    }

    /**
     * 按 requestId 查询已成功的幂等记录；占位阶段（未提交）对其他事务不可见。
     */
    public Optional<IdempotentRequest> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }
}
