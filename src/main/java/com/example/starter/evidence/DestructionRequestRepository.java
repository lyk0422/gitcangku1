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
 * 销毁申请表访问。申请记录只追加：APPROVED 与 REJECTED 均为终态裁决，不可改写。
 * request_id 唯一约束承担销毁申请的幂等重放。
 */
@Repository
public class DestructionRequestRepository {

    private static final DestructionRowMapper ROW_MAPPER = new DestructionRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条销毁申请裁决；request_id 冲突由唯一约束拒绝。
     */
    public void insert(String requestId, String requestHash, String evidenceKey, String caseKey,
                       String reason, String requestedBy, DestructionStatus status,
                       Long blockingHoldId, LocalDateTime nowUtc, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO destruction_request
                            (request_id, request_hash, evidence_key, case_key, reason, requested_by,
                             status, blocking_hold_id, requested_utc, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                requestId, requestHash, evidenceKey, caseKey, reason, requestedBy,
                status.name(), blockingHoldId, nowUtc, createdAt);
    }

    /**
     * 按销毁申请请求键查询首次裁决，用于重放。
     */
    public Optional<DestructionRequestRecord> findByRequestId(String requestId) {
        List<DestructionRequestRecord> rows = jdbc.query(
                "SELECT * FROM destruction_request WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询单件证物的全部销毁申请（按发生顺序）。
     */
    public List<DestructionRequestRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM destruction_request WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 查询案件下全部证物的销毁申请（按发生顺序）。
     */
    public List<DestructionRequestRecord> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM destruction_request WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    private static final class DestructionRowMapper implements RowMapper<DestructionRequestRecord> {
        @Override
        public DestructionRequestRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long blockingHoldId = rs.getObject("blocking_hold_id", Long.class);
            return new DestructionRequestRecord(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("request_hash"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    rs.getString("reason"),
                    rs.getString("requested_by"),
                    DestructionStatus.valueOf(rs.getString("status")),
                    blockingHoldId,
                    rs.getObject("requested_utc", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
