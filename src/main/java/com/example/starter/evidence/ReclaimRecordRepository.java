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
 * 追缴记录表访问。记录只追加、不可变，不提供任何更新语句。
 * reclaim_key 全局唯一，兼作幂等键。
 */
@Repository
public class ReclaimRecordRepository {

    private static final ReclaimRowMapper ROW_MAPPER = new ReclaimRowMapper();

    private final JdbcTemplate jdbc;

    public ReclaimRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条追缴记录；reclaim_key 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String reclaimKey, String loanKey, String evidenceKey, String borrowerId,
                       String custodianId, LocalDateTime dueAt, long overdueMinutes,
                       String note, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO reclaim_record
                            (reclaim_key, loan_key, evidence_key, borrower_id, custodian_id,
                             due_at, overdue_minutes, note, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reclaimKey, loanKey, evidenceKey, borrowerId, custodianId,
                dueAt, overdueMinutes, note, createdAt);
    }

    /**
     * 按追缴业务键查询。
     */
    public Optional<ReclaimRecord> findByReclaimKey(String reclaimKey) {
        List<ReclaimRecord> rows = jdbc.query(
                "SELECT * FROM reclaim_record WHERE reclaim_key = ?", ROW_MAPPER, reclaimKey);
        return rows.stream().findFirst();
    }

    /**
     * 按借出业务键查询追缴记录（同一借出至多一条）。
     */
    public Optional<ReclaimRecord> findByLoanKey(String loanKey) {
        List<ReclaimRecord> rows = jdbc.query(
                "SELECT * FROM reclaim_record WHERE loan_key = ?", ROW_MAPPER, loanKey);
        return rows.stream().findFirst();
    }

    /**
     * 按借出人查询全部追缴记录（按发生顺序）。
     */
    public List<ReclaimRecord> findByBorrower(String borrowerId) {
        return jdbc.query(
                "SELECT * FROM reclaim_record WHERE borrower_id = ? ORDER BY id",
                ROW_MAPPER, borrowerId);
    }

    /**
     * 查询全部追缴记录（按发生顺序）。
     */
    public List<ReclaimRecord> findAll() {
        return jdbc.query("SELECT * FROM reclaim_record ORDER BY id", ROW_MAPPER);
    }

    /**
     * 统计借出人累计追缴次数（含历史全部，冻结判定由服务层结合解冻基线计算）。
     */
    public int countByBorrower(String borrowerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reclaim_record WHERE borrower_id = ?",
                Integer.class, borrowerId);
        return count == null ? 0 : count;
    }

    private static final class ReclaimRowMapper implements RowMapper<ReclaimRecord> {
        @Override
        public ReclaimRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ReclaimRecord(
                    rs.getLong("id"),
                    rs.getString("reclaim_key"),
                    rs.getString("loan_key"),
                    rs.getString("evidence_key"),
                    rs.getString("borrower_id"),
                    rs.getString("custodian_id"),
                    rs.getObject("due_at", LocalDateTime.class),
                    rs.getLong("overdue_minutes"),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
