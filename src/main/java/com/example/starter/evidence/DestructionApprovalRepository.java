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
 * 销毁令同意记录表访问。行只追加；(destruction_key, approver_id) 唯一，
 * 同一审批人重复同意由唯一约束与服务层幂等共同保证只生效一次。
 */
@Repository
public class DestructionApprovalRepository {

    private static final ApprovalRowMapper ROW_MAPPER = new ApprovalRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条同意记录。
     */
    public void insert(String destructionKey, String approverId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO destruction_approval (destruction_key, approver_id, created_at)
                        VALUES (?, ?, ?)
                        """,
                destructionKey, approverId, now);
    }

    /**
     * 查询某审批人在该销毁令上的同意记录。
     */
    public Optional<DestructionApproval> find(String destructionKey, String approverId) {
        List<DestructionApproval> rows = jdbc.query(
                "SELECT * FROM destruction_approval WHERE destruction_key = ? AND approver_id = ?",
                ROW_MAPPER, destructionKey, approverId);
        return rows.stream().findFirst();
    }

    /**
     * 按销毁键查询全部同意记录（按同意顺序）。
     */
    public List<DestructionApproval> findByDestructionKey(String destructionKey) {
        return jdbc.query(
                "SELECT * FROM destruction_approval WHERE destruction_key = ? ORDER BY id",
                ROW_MAPPER, destructionKey);
    }

    /**
     * 统计同意人数（调用前须锁定销毁令行）。
     */
    public int countByDestructionKey(String destructionKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM destruction_approval WHERE destruction_key = ?",
                Integer.class, destructionKey);
        return count == null ? 0 : count;
    }

    private static final class ApprovalRowMapper implements RowMapper<DestructionApproval> {
        @Override
        public DestructionApproval mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DestructionApproval(
                    rs.getLong("id"),
                    rs.getString("destruction_key"),
                    rs.getString("approver_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
