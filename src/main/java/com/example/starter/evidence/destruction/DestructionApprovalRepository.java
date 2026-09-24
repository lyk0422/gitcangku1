package com.example.starter.evidence.destruction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销毁令审批记录表访问。只追加、不可变；同一销毁令每名审批人至多一条 AGREED 由服务层校验。
 */
@Repository
public class DestructionApprovalRepository {

    private static final ApprovalRowMapper ROW_MAPPER = new ApprovalRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条审批记录（AGREED 或 REJECTED）。
     */
    public void insert(String destructionKey, String approverId, ApprovalDecision decision,
                       String reason, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO destruction_approval
                            (destruction_key, approver_id, decision, reason, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                destructionKey, approverId, decision.name(), reason, now);
    }

    /**
     * 按提交顺序查询销毁令全部审批记录。
     */
    public List<DestructionApproval> findByDestructionKey(String destructionKey) {
        return jdbc.query(
                "SELECT * FROM destruction_approval WHERE destruction_key = ? ORDER BY id",
                ROW_MAPPER, destructionKey);
    }

    private static final class ApprovalRowMapper implements RowMapper<DestructionApproval> {
        @Override
        public DestructionApproval mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionApproval(
                    rs.getLong("id"),
                    rs.getString("destruction_key"),
                    rs.getString("approver_id"),
                    ApprovalDecision.valueOf(rs.getString("decision")),
                    rs.getString("reason"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
