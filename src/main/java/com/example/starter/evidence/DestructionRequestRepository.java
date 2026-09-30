package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 销毁申请表访问。记录只追加、不可变；
 * 同一 request_id 的唯一约束兜底并发重复申请，DENIED 记录不随冻结事后到期或解除改写。
 */
@Repository
public class DestructionRequestRepository {

    private static final DestructionRowMapper ROW_MAPPER = new DestructionRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加销毁申请结论；request_id 冲突抛唯一约束异常（由幂等编排转为重放或 409）。
     */
    public void insert(String evidenceKey, String requestId, String requestedBy, String reason,
                       DestructionStatus status, Long blockingHoldId, LocalDateTime requestedAt) {
        jdbc.update("""
                        INSERT INTO destruction_request
                            (evidence_key, request_id, requested_by, reason, status,
                             blocking_hold_id, requested_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, requestId, requestedBy, reason, status.name(),
                blockingHoldId, requestedAt);
    }

    /**
     * 按销毁申请业务键查询。
     */
    public Optional<DestructionRequest> findByRequestId(String requestId) {
        List<DestructionRequest> rows = jdbc.query(
                "SELECT * FROM destruction_request WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询单件证物的全部销毁申请，按发生顺序。
     */
    public List<DestructionRequest> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM destruction_request WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class DestructionRowMapper implements RowMapper<DestructionRequest> {
        @Override
        public DestructionRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long blockingHoldId = rs.getObject("blocking_hold_id", Long.class);
            return new DestructionRequest(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("request_id"),
                    rs.getString("requested_by"),
                    rs.getString("reason"),
                    DestructionStatus.valueOf(rs.getString("status")),
                    blockingHoldId,
                    rs.getObject("requested_at", LocalDateTime.class));
        }
    }
}
