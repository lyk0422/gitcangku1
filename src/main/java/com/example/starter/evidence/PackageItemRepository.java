package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合包证物明细表访问。明细只追加；归还仅允许一次条件回写（return_batch_id 必须仍为 NULL），
 * 由证物行锁与包行锁共同保证并发分批归还不会重复归还同一件。
 */
@Repository
public class PackageItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public PackageItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条借出明细，冻结借出时封条版本。
     */
    public void insert(long packageId, String evidenceKey, long frozenSealVersion,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO package_item
                            (package_id, evidence_key, frozen_seal_version, return_batch_id, returned_at, created_at)
                        VALUES (?, ?, ?, NULL, NULL, ?)
                        """,
                packageId, evidenceKey, frozenSealVersion, now);
    }

    /**
     * 查询组合包全部明细，按证物键稳定排序。
     */
    public List<PackageItem> findByPackageIdOrdered(long packageId) {
        return jdbc.query(
                "SELECT * FROM package_item WHERE package_id = ? ORDER BY evidence_key",
                ROW_MAPPER, packageId);
    }

    /**
     * 按证物键集合查询包内明细（调用前应已锁定包行）。
     */
    public List<PackageItem> findByPackageAndEvidenceKeys(long packageId, List<String> evidenceKeys) {
        if (evidenceKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", evidenceKeys.stream().map(k -> "?").toList());
        Object[] args = new Object[evidenceKeys.size() + 1];
        args[0] = packageId;
        for (int i = 0; i < evidenceKeys.size(); i++) {
            args[i + 1] = evidenceKeys.get(i);
        }
        return jdbc.query(
                "SELECT * FROM package_item WHERE package_id = ? AND evidence_key IN (" + placeholders + ")",
                ROW_MAPPER, args);
    }

    /**
     * 条件回写归还批次：仅当该件尚未归还（return_batch_id 为 NULL）时写入。
     *
     * @return 是否回写成功（false 表示已被并发批次归还）
     */
    public boolean markReturned(long itemId, long batchId, LocalDateTime returnedAt) {
        int updated = jdbc.update("""
                        UPDATE package_item
                        SET return_batch_id = ?, returned_at = ?
                        WHERE id = ? AND return_batch_id IS NULL
                        """,
                batchId, returnedAt, itemId);
        return updated == 1;
    }

    private static final class ItemRowMapper implements RowMapper<PackageItem> {
        @Override
        public PackageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long batchId = rs.getObject("return_batch_id", Long.class);
            return new PackageItem(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getString("evidence_key"),
                    rs.getLong("frozen_seal_version"),
                    batchId,
                    rs.getObject("returned_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
