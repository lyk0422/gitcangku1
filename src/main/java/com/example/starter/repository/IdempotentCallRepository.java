package com.example.starter.repository;

import com.example.starter.domain.ActionType;
import com.example.starter.model.IdempotentCall;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 幂等调用记录仓储。
 */
@Repository
public class IdempotentCallRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotentCallRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按幂等键查询首次调用记录。
     */
    public Optional<IdempotentCall> findByRequestId(String requestId) {
        List<IdempotentCall> list = jdbcTemplate.query(
                "SELECT request_id, action, fingerprint, target_request_no, outcome_status,"
                        + " conflict, error_code, message, promoted_request_no, response_snapshot,"
                        + " created_at FROM idempotent_call WHERE request_id = ?",
                new CallRowMapper(), requestId);
        return list.stream().findFirst();
    }

    /**
     * 落库首次成功调用；并发下幂等键冲突由调用方按 false 处理。
     */
    public boolean insert(IdempotentCall call) {
        return jdbcTemplate.update("""
                        INSERT INTO idempotent_call (request_id, action, fingerprint,
                            target_request_no, outcome_status, conflict, error_code, message,
                            promoted_request_no, response_snapshot, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                call.requestId(),
                call.action().name(),
                call.fingerprint(),
                call.targetRequestNo(),
                call.outcomeStatus(),
                false,
                call.errorCode(),
                call.message(),
                call.promotedRequestNo(),
                call.responseSnapshot(),
                Timestamp.valueOf(LocalDateTime.ofInstant(call.createdAt(), ZoneOffset.UTC))) > 0;
    }

    /**
     * 对幂等记录行加行锁，等待并发的首个事务提交或回滚。行不存在时直接返回。
     */
    public void lockForUpdate(String requestId) {
        jdbcTemplate.query(
                "SELECT request_id FROM idempotent_call WHERE request_id = ? FOR UPDATE",
                rs -> { }, requestId);
    }

    /**
     * 业务执行成功后补全首次记录的结果与响应快照。
     */
    public void complete(String requestId, String outcomeStatus, String message,
                         String promotedRequestNo, String responseSnapshot, Instant completedAt) {
        jdbcTemplate.update("""
                        UPDATE idempotent_call
                        SET outcome_status = ?, message = ?, promoted_request_no = ?,
                            response_snapshot = ?
                        WHERE request_id = ? AND outcome_status = 'RUNNING'""",
                outcomeStatus, message, promotedRequestNo, responseSnapshot, requestId);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    /**
     * 幂等记录行映射器。
     */
    private static final class CallRowMapper implements RowMapper<IdempotentCall> {

        @Override
        public IdempotentCall mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotentCall(
                    rs.getString("request_id"),
                    ActionType.valueOf(rs.getString("action")),
                    rs.getString("fingerprint"),
                    rs.getString("target_request_no"),
                    rs.getString("outcome_status"),
                    rs.getString("error_code"),
                    rs.getString("message"),
                    rs.getString("promoted_request_no"),
                    rs.getString("response_snapshot"),
                    toInstant(rs.getTimestamp("created_at")));
        }
    }
}
