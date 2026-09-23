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
 * 组合借出包表访问。package_key 全局唯一，冲突由唯一约束拒绝；
 * 包状态变更必须先通过 {@link #findByKeyForUpdate} 锁定包行串行化分批归还。
 */
@Repository
public class LoanPackageRepository {

    private static final PackageRowMapper ROW_MAPPER = new PackageRowMapper();

    private final JdbcTemplate jdbc;

    public LoanPackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个 PARTIAL 组合借出包。
     */
    public void insert(String packageKey, String caseKey, String custodianId, String borrowerId,
                       String handlerId, String purpose, LocalDateTime loanAt, LocalDateTime dueAt,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO loan_package
                            (package_key, case_key, custodian_id, borrower_id, handler_id, purpose,
                             loan_at, due_at, status, closed_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                        """,
                packageKey, caseKey, custodianId, borrowerId, handlerId, purpose,
                loanAt, dueAt, PackageStatus.PARTIAL.name(), now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<LoanPackage> findByKey(String packageKey) {
        List<LoanPackage> rows = jdbc.query(
                "SELECT * FROM loan_package WHERE package_key = ?", ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定包行（SELECT ... FOR UPDATE），用于分批归还、撤销与自动关闭。
     */
    public Optional<LoanPackage> findByKeyForUpdate(String packageKey) {
        List<LoanPackage> rows = jdbc.query(
                "SELECT * FROM loan_package WHERE package_key = ? FOR UPDATE", ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 自动关闭：仅当包仍为 PARTIAL 时置为 CLOSED 并记录关闭时刻。
     *
     * @return 是否关闭成功（false 表示已关闭）
     */
    public boolean close(long id, LocalDateTime closedAt, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE loan_package
                        SET status = ?, closed_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                PackageStatus.CLOSED.name(), closedAt, now, id, PackageStatus.PARTIAL.name());
        return updated == 1;
    }

    /**
     * 借出撤销：仅当包仍为 PARTIAL 时置为 CANCELLED（终态），关闭时刻不写。
     *
     * @return 是否撤销成功（false 表示已有批次归还或已被并发撤销/关闭）
     */
    public boolean cancel(long id, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE loan_package
                        SET status = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                PackageStatus.CANCELLED.name(), now, id, PackageStatus.PARTIAL.name());
        return updated == 1;
    }

    private static final class PackageRowMapper implements RowMapper<LoanPackage> {
        @Override
        public LoanPackage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LoanPackage(
                    rs.getLong("id"),
                    rs.getString("package_key"),
                    rs.getString("case_key"),
                    rs.getString("custodian_id"),
                    rs.getString("borrower_id"),
                    rs.getString("handler_id"),
                    rs.getString("purpose"),
                    rs.getObject("loan_at", LocalDateTime.class),
                    rs.getObject("due_at", LocalDateTime.class),
                    PackageStatus.valueOf(rs.getString("status")),
                    rs.getObject("closed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
