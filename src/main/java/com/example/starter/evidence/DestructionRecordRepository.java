package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销毁申请记录表访问。记录只追加：申请通过写 APPROVED，被有效冻结阻断写 REJECTED；
 * 冻结到期或解除均不回改历史记录。
 */
@Repository
public class DestructionRecordRepository {

    private static final DestructionRecordRowMapper ROW_MAPPER = new DestructionRecordRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条销毁申请记录（APPROVED 或 REJECTED）。
     */
    public void insert(String evidenceKey, String requestedBy, String reason,
                       DestructionStatus status, String rejectReason, String blockingHoldKey,
                       LocalDateTime decidedAt, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO destruction_record
                            (evidence_key, requested_by, reason, status, reject_reason,
                             blocking_hold_key, decided_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, requestedBy, reason, status.name(), rejectReason,
                blockingHoldKey, decidedAt, createdAt);
    }

    /**
     * 按证物查询全部销毁申请记录（按发生顺序，含被拒记录）。
     */
    public List<DestructionRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM destruction_record WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class DestructionRecordRowMapper implements RowMapper<DestructionRecord> {
        @Override
        public DestructionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionRecord(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("requested_by"),
                    rs.getString("reason"),
                    DestructionStatus.valueOf(rs.getString("status")),
                    rs.getString("reject_reason"),
                    rs.getString("blocking_hold_key"),
                    rs.getObject("decided_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
