package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 组合借出包表访问。package_key 全局唯一；
 * 归还批次与自动关闭必须先通过 {@link #findByKeyForUpdate} 锁定包行，
 * 保证并发归还批次按事务提交顺序生效、关闭只发生一次。
 */
@Repository
public class LoanPackageRepository {

    private static final PackageRowMapper ROW_MAPPER = new PackageRowMapper();

    private final JdbcTemplate jdbc;

    public LoanPackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新组合包，初始状态 PARTIAL；package_key 冲突由唯一约束拒绝。
     *
     * @return 新组合包主键
     */
    public long insert(String packageKey, String caseKey, String custodianId, String borrowerId,
                       String purpose, LocalDateTime dueAt, LocalDateTime loanAt,
                       LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO loan_package
                                (package_key, case_key, custodian_id, borrower_id, purpose,
                                 due_at, loan_at, status, closed_at, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, packageKey);
            ps.setString(2, caseKey);
            ps.setString(3, custodianId);
            ps.setString(4, borrowerId);
            ps.setString(5, purpose);
            ps.setObject(6, dueAt);
            ps.setObject(7, loanAt);
            ps.setString(8, PackageStatus.PARTIAL.name());
            ps.setObject(9, now);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("组合包主键生成失败");
        }
        return key.longValue();
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
     * 按主键查询（不加锁），用于同事务内构建视图。
     */
    public Optional<LoanPackage> findById(long packageId) {
        List<LoanPackage> rows = jdbc.query(
                "SELECT * FROM loan_package WHERE id = ?", ROW_MAPPER, packageId);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定组合包行（SELECT ... FOR UPDATE），用于归还批次与撤销。
     */
    public Optional<LoanPackage> findByKeyForUpdate(String packageKey) {
        List<LoanPackage> rows = jdbc.query(
                "SELECT * FROM loan_package WHERE package_key = ? FOR UPDATE", ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 条件自动关闭：仅当包仍为 PARTIAL 时置为 CLOSED，写入关闭时刻与关闭快照。
     *
     * @return 是否成功关闭（false 表示已被并发关闭）
     */
    public boolean closeIfPartial(long packageId, LocalDateTime closedAt, String closeSnapshot,
                                  LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE loan_package
                        SET status = ?, closed_at = ?, close_snapshot = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                PackageStatus.CLOSED.name(), closedAt, closeSnapshot, now,
                packageId, PackageStatus.PARTIAL.name());
        return updated == 1;
    }

    /**
     * 仅刷新更新时间（归还批次落账时）。
     */
    public void touch(long packageId, LocalDateTime now) {
        jdbc.update("UPDATE loan_package SET updated_at = ? WHERE id = ?", now, packageId);
    }

    /**
     * 删除组合包行（借出撤销时整体移除；调用方须已持有包行锁并确认无任何归还批次）。
     */
    public void deleteById(long packageId) {
        jdbc.update("DELETE FROM loan_package WHERE id = ?", packageId);
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
                    rs.getString("purpose"),
                    rs.getObject("due_at", LocalDateTime.class),
                    rs.getObject("loan_at", LocalDateTime.class),
                    PackageStatus.valueOf(rs.getString("status")),
                    rs.getObject("closed_at", LocalDateTime.class),
                    rs.getString("close_snapshot"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
