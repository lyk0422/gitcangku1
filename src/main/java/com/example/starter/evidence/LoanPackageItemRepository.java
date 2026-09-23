package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合包逐件借出明细表访问。建包时一次性写入；
 * 归还仅允许一次条件更新（status 必须仍为 OUT），历史不可覆盖。
 */
@Repository
public class LoanPackageItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public LoanPackageItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条包内明细，初始状态 OUT；(package_id, evidence_key) 唯一约束拒绝重复证物。
     */
    public void insert(long packageId, String evidenceKey, long sealVersion, int itemSeq,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO package_loan_item
                            (package_id, evidence_key, seal_version, item_seq, status,
                             returned_batch_id, returned_at, created_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)
                        """,
                packageId, evidenceKey, sealVersion, itemSeq,
                PackageItemStatus.OUT.name(), now);
    }

    /**
     * 按组合包查询全部明细（按包内稳定排序序号）。
     */
    public List<LoanPackageItem> findByPackageId(long packageId) {
        return jdbc.query(
                "SELECT * FROM package_loan_item WHERE package_id = ? ORDER BY item_seq",
                ROW_MAPPER, packageId);
    }

    /**
     * 按组合包查询未归还明细（按包内稳定排序序号），用于剩余集合查询。
     */
    public List<LoanPackageItem> findOutstandingByPackageId(long packageId) {
        return jdbc.query(
                "SELECT * FROM package_loan_item WHERE package_id = ? AND status = ? ORDER BY item_seq",
                ROW_MAPPER, packageId, PackageItemStatus.OUT.name());
    }

    /**
     * 条件归还：仅当明细仍为 OUT 时写入归还批次与归还时刻，历史不可覆盖。
     *
     * @return 是否成功归还（false 表示已被并发归还或明细不存在）
     */
    public boolean markReturned(long itemId, long batchId, LocalDateTime returnedAt) {
        int updated = jdbc.update("""
                        UPDATE package_loan_item
                        SET status = ?, returned_batch_id = ?, returned_at = ?
                        WHERE id = ? AND status = ?
                        """,
                PackageItemStatus.RETURNED.name(), batchId, returnedAt,
                itemId, PackageItemStatus.OUT.name());
        return updated == 1;
    }

    /**
     * 删除组合包全部明细（借出撤销时整体移除；调用方须已确认无任何归还）。
     */
    public void deleteByPackageId(long packageId) {
        jdbc.update("DELETE FROM package_loan_item WHERE package_id = ?", packageId);
    }

    private static final class ItemRowMapper implements RowMapper<LoanPackageItem> {
        @Override
        public LoanPackageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long returnedBatchId = rs.getObject("returned_batch_id", Long.class);
            return new LoanPackageItem(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getString("evidence_key"),
                    rs.getLong("seal_version"),
                    rs.getInt("item_seq"),
                    PackageItemStatus.valueOf(rs.getString("status")),
                    returnedBatchId,
                    rs.getObject("returned_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
