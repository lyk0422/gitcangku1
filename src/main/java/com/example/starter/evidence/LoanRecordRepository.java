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
 * 借出记录表访问。记录只追加；归还只允许一次条件更新（status 必须仍为 ACTIVE）。
 * 每件证物至多一笔 ACTIVE 借出，由证物行锁与服务层状态校验共同保证。
 */
@Repository
public class LoanRecordRepository {

    private static final LoanRowMapper ROW_MAPPER = new LoanRowMapper();

    private final JdbcTemplate jdbc;

    public LoanRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一笔 ACTIVE 借出记录；loan_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String loanKey, String evidenceKey, String custodianId, String borrowerId,
                       String purpose, LocalDateTime loanAt, LocalDateTime dueAt,
                       LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO loan_record
                            (loan_key, evidence_key, custodian_id, borrower_id, purpose,
                             loan_at, due_at, status, seal_passed, return_note, returned_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
                        """,
                loanKey, evidenceKey, custodianId, borrowerId, purpose,
                loanAt, dueAt, LoanStatus.ACTIVE.name(), createdAt);
    }

    /**
     * 按全局借出业务键查询。
     */
    public Optional<LoanRecord> findByLoanKey(String loanKey) {
        List<LoanRecord> rows = jdbc.query(
                "SELECT * FROM loan_record WHERE loan_key = ?", ROW_MAPPER, loanKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前未归还（ACTIVE）借出（调用前必须已锁定证物行）。
     */
    public Optional<LoanRecord> findActiveByEvidenceKey(String evidenceKey) {
        List<LoanRecord> rows = jdbc.query(
                "SELECT * FROM loan_record WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, LoanStatus.ACTIVE.name());
        return rows.stream().findFirst();
    }

    /**
     * 条件归还：仅当记录仍为 ACTIVE 时写入归还结果与归还时刻，历史不可覆盖。
     *
     * @return 是否成功归还（false 表示已被并发归还或记录不存在）
     */
    public boolean completeReturn(long id, boolean sealPassed, String note, LocalDateTime returnedAt) {
        int updated = jdbc.update("""
                        UPDATE loan_record
                        SET status = ?, seal_passed = ?, return_note = ?, returned_at = ?
                        WHERE id = ? AND status = ?
                        """,
                LoanStatus.RETURNED.name(), sealPassed ? 1 : 0, note, returnedAt,
                id, LoanStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 条件追缴：仅当记录仍为 ACTIVE 时转为 RECLAIMED 终态，历史不可覆盖。
     *
     * @return 是否成功追缴（false 表示已被并发归还或追缴）
     */
    public boolean completeReclaim(long id) {
        int updated = jdbc.update("""
                        UPDATE loan_record
                        SET status = ?
                        WHERE id = ? AND status = ?
                        """,
                LoanStatus.RECLAIMED.name(), id, LoanStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 查询当前已逾期（ACTIVE 且应还时刻不晚于给定 UTC 时刻）的借出记录，按应还时刻升序。
     */
    public List<LoanRecord> findOverdue(LocalDateTime nowUtc) {
        return jdbc.query(
                "SELECT * FROM loan_record WHERE status = ? AND due_at <= ? ORDER BY due_at, id",
                ROW_MAPPER, LoanStatus.ACTIVE.name(), nowUtc);
    }

    /**
     * 按借用人查询全部借出记录（按发生顺序），逾期标识由服务层按时钟计算。
     */
    public List<LoanRecord> findByBorrower(String borrowerId) {
        return jdbc.query(
                "SELECT * FROM loan_record WHERE borrower_id = ? ORDER BY id",
                ROW_MAPPER, borrowerId);
    }

    /**
     * 按证物查询全部借出记录（按发生顺序）。
     */
    public List<LoanRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM loan_record WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class LoanRowMapper implements RowMapper<LoanRecord> {
        @Override
        public LoanRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            Integer sealPassed = rs.getObject("seal_passed", Integer.class);
            return new LoanRecord(
                    rs.getLong("id"),
                    rs.getString("loan_key"),
                    rs.getString("evidence_key"),
                    rs.getString("custodian_id"),
                    rs.getString("borrower_id"),
                    rs.getString("purpose"),
                    rs.getObject("loan_at", LocalDateTime.class),
                    rs.getObject("due_at", LocalDateTime.class),
                    LoanStatus.valueOf(rs.getString("status")),
                    sealPassed == null ? null : sealPassed == 1,
                    rs.getString("return_note"),
                    rs.getObject("returned_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
