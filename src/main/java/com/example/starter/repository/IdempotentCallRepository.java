package com.example.starter.repository;

import com.example.starter.domain.OperationType;
import com.example.starter.domain.RequestStatus;
import com.example.starter.model.IdempotentCall;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 幂等调用记录仓库；仅首次成功提交写入，事务失败回滚即“不占幂等键”。
 */
@Repository
public class IdempotentCallRepository {

    private static final RowMapper<IdempotentCall> MAPPER = (rs, n) -> {
        IdempotentCall c = new IdempotentCall();
        c.setRequestId(rs.getString("request_id"));
        c.setOperation(OperationType.valueOf(rs.getString("operation")));
        c.setRequestFingerprint(rs.getString("request_fingerprint"));
        c.setResultRequestNo(rs.getString("result_request_no"));
        c.setResultStatus(RequestStatus.valueOf(rs.getString("result_status")));
        c.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return c;
    };

    private final JdbcTemplate jdbcTemplate;

    public IdempotentCallRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(IdempotentCall call) {
        jdbcTemplate.update(
                """
                INSERT INTO idempotent_call
                    (request_id, operation, request_fingerprint, result_request_no, result_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                call.getRequestId(), call.getOperation().name(), call.getRequestFingerprint(),
                call.getResultRequestNo(), call.getResultStatus().name(),
                Timestamp.from(call.getCreatedAt()));
    }

    public IdempotentCall find(String requestId, OperationType operation) {
        List<IdempotentCall> list = jdbcTemplate.query(
                "SELECT * FROM idempotent_call WHERE request_id = ? AND operation = ?",
                MAPPER, requestId, operation.name());
        return list.isEmpty() ? null : list.get(0);
    }
}
