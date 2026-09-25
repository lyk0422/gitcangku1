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
 * 借出人冻结状态表访问。每个借出人至多一行（borrower_id 唯一）；
 * 追缴计数与冻结标志的变更必须先通过 {@link #findByBorrowerIdForUpdate} 锁定行，
 * 行不存在时插入与并发首次插入冲突由唯一约束拒绝后重查处理。
 */
@Repository
public class BorrowerFreezeRepository {

    private static final FreezeRowMapper ROW_MAPPER = new FreezeRowMapper();

    private final JdbcTemplate jdbc;

    public BorrowerFreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按借出人查询冻结状态（不加锁），用于只读场景。
     */
    public Optional<BorrowerFreeze> findByBorrowerId(String borrowerId) {
        List<BorrowerFreeze> rows = jdbc.query(
                "SELECT * FROM borrower_freeze WHERE borrower_id = ?", ROW_MAPPER, borrowerId);
        return rows.stream().findFirst();
    }

    /**
     * 按借出人查询并锁定冻结状态行（SELECT ... FOR UPDATE），用于追缴计数与冻结/解冻变更。
     */
    public Optional<BorrowerFreeze> findByBorrowerIdForUpdate(String borrowerId) {
        List<BorrowerFreeze> rows = jdbc.query(
                "SELECT * FROM borrower_freeze WHERE borrower_id = ? FOR UPDATE",
                ROW_MAPPER, borrowerId);
        return rows.stream().findFirst();
    }

    /**
     * 插入借出人初始冻结状态行（未冻结、计数为零）；borrower_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String borrowerId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO borrower_freeze
                            (borrower_id, frozen, reclaim_count, frozen_by, frozen_at, updated_at)
                        VALUES (?, 0, 0, NULL, NULL, ?)
                        """,
                borrowerId, now);
    }

    /**
     * 更新冻结状态与追缴计数（调用前必须已锁定行）。
     */
    public void update(String borrowerId, boolean frozen, int reclaimCount, String frozenBy,
                       LocalDateTime frozenAt, LocalDateTime now) {
        jdbc.update("""
                        UPDATE borrower_freeze
                        SET frozen = ?, reclaim_count = ?, frozen_by = ?, frozen_at = ?, updated_at = ?
                        WHERE borrower_id = ?
                        """,
                frozen ? 1 : 0, reclaimCount, frozenBy, frozenAt, now, borrowerId);
    }

    private static final class FreezeRowMapper implements RowMapper<BorrowerFreeze> {
        @Override
        public BorrowerFreeze mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BorrowerFreeze(
                    rs.getString("borrower_id"),
                    rs.getInt("frozen") == 1,
                    rs.getInt("reclaim_count"),
                    rs.getString("frozen_by"),
                    rs.getObject("frozen_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
