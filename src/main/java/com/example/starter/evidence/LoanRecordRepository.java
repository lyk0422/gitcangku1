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
 * 借出记录表访问。记录只追加；每件证物通过证物行锁保证至多一笔 ACTIVE。
 * 归还仅允许 ACTIVE → RETURNED 的一次性条件更新。
 */
@Repository
public class LoanRecordRepository {

    private static final LoanRowMapper ROW_MAPPER = new LoanRowMapper();

    private final JdbcTemplate jdbc;

    public LoanRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一笔未归还借出记录。loan_key 全局唯一，复用冲突由唯一约束拦截。
     */
    public void insert(String loanKey, String evidenceKey, String custodianId, String borrowerId,
                       String purpose, LocalDateTime loanedAt, LocalDateTime dueAtUtc,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO loan_record
                            (loan_key, evidence_key, custodian_id, borrower_id, purpose, status,
                             loaned_at, due_at_utc, returned_at, seal_intact, return_note, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
                        """,
                loanKey, evidenceKey, custodianId, borrowerId, purpose,
                LoanStatus.ACTIVE.name(), loanedAt, dueAtUtc, now);
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
     * 查询证物当前未归还借出（调用前必须已锁定证物行）。
     */
    public Optional<LoanRecord> findActiveByEvidenceKey(String evidenceKey) {
        List<LoanRecord> rows = jdbc.query(
                "SELECT * FROM loan_record WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, LoanStatus.ACTIVE.name());
        return rows.stream().findFirst();
    }

    /**
     * 将未归还借出标记为已归还并写入封条核验结果；仅当仍为 ACTIVE 时生效。
     *
     * @return 是否成功（false 表示已被并发事务先行归还）
     */
    public boolean complete(long id, boolean sealIntact, String note, LocalDateTime returnedAt) {
        int updated = jdbc.update("""
                        UPDATE loan_record
                        SET status = ?, returned_at = ?, seal_intact = ?, return_note = ?
                        WHERE id = ? AND status = ?
                        """,
                LoanStatus.RETURNED.name(), returnedAt, sealIntact ? 1 : 0, note, id,
                LoanStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 按发生顺序查询证物全部借出记录。
     */
    public List<LoanRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM loan_record WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 查询指定借用人全部未归还借出记录（含逾期），按借出时间排序。
     */
    public List<LoanRecord> findActiveByBorrower(String borrowerId) {
        return jdbc.query(
                "SELECT * FROM loan_record WHERE borrower_id = ? AND status = ? ORDER BY loaned_at, id",
                ROW_MAPPER, borrowerId, LoanStatus.ACTIVE.name());
    }

    private static final class LoanRowMapper implements RowMapper<LoanRecord> {
        @Override
        public LoanRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            int sealIntactValue = rs.getInt("seal_intact");
            Boolean sealIntact = rs.wasNull() ? null : sealIntactValue == 1;
            return new LoanRecord(
                    rs.getLong("id"),
                    rs.getString("loan_key"),
                    rs.getString("evidence_key"),
                    rs.getString("custodian_id"),
                    rs.getString("borrower_id"),
                    rs.getString("purpose"),
                    LoanStatus.valueOf(rs.getString("status")),
                    rs.getObject("loaned_at", LocalDateTime.class),
                    rs.getObject("due_at_utc", LocalDateTime.class),
                    rs.getObject("returned_at", LocalDateTime.class),
                    sealIntact,
                    rs.getString("return_note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
