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
 * 追缴记录表访问。记录只追加、不可变；reclaim_key 与 loan_key 均全局唯一，
 * 由唯一约束保证同一借出记录只能被追缴一次、同一追缴键只落库一次。
 */
@Repository
public class ReclaimRecordRepository {

    private static final ReclaimRowMapper ROW_MAPPER = new ReclaimRowMapper();

    private final JdbcTemplate jdbc;

    public ReclaimRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条追缴记录；reclaim_key 或 loan_key 冲突时由唯一约束拒绝。
     */
    public void insert(String reclaimKey, String loanKey, String evidenceKey, String custodianId,
                       String borrowerId, LocalDateTime dueAt, long overdueMinutes, String note,
                       LocalDateTime reclaimedAt, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO reclaim_record
                            (reclaim_key, loan_key, evidence_key, custodian_id, borrower_id,
                             due_at, overdue_minutes, note, reclaimed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reclaimKey, loanKey, evidenceKey, custodianId, borrowerId,
                dueAt, overdueMinutes, note, reclaimedAt, createdAt);
    }

    /**
     * 按追缴业务键查询（用于 reclaimKey 幂等重放）。
     */
    public Optional<ReclaimRecord> findByReclaimKey(String reclaimKey) {
        List<ReclaimRecord> rows = jdbc.query(
                "SELECT * FROM reclaim_record WHERE reclaim_key = ?", ROW_MAPPER, reclaimKey);
        return rows.stream().findFirst();
    }

    /**
     * 按借出业务键查询（同一借出至多一条追缴记录）。
     */
    public Optional<ReclaimRecord> findByLoanKey(String loanKey) {
        List<ReclaimRecord> rows = jdbc.query(
                "SELECT * FROM reclaim_record WHERE loan_key = ?", ROW_MAPPER, loanKey);
        return rows.stream().findFirst();
    }

    /**
     * 按借出人查询全部追缴记录（按发生顺序），历史记录解冻后仍保留。
     */
    public List<ReclaimRecord> findByBorrower(String borrowerId) {
        return jdbc.query(
                "SELECT * FROM reclaim_record WHERE borrower_id = ? ORDER BY id",
                ROW_MAPPER, borrowerId);
    }

    private static final class ReclaimRowMapper implements RowMapper<ReclaimRecord> {
        @Override
        public ReclaimRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReclaimRecord(
                    rs.getLong("id"),
                    rs.getString("reclaim_key"),
                    rs.getString("loan_key"),
                    rs.getString("evidence_key"),
                    rs.getString("custodian_id"),
                    rs.getString("borrower_id"),
                    rs.getObject("due_at", LocalDateTime.class),
                    rs.getLong("overdue_minutes"),
                    rs.getString("note"),
                    rs.getObject("reclaimed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
