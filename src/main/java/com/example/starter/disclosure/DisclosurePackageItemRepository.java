package com.example.starter.disclosure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 披露包逐件快照表访问。快照只在建包事务内追加，之后不提供任何更新语句。
 */
@Repository
public class DisclosurePackageItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public DisclosurePackageItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一件冻结快照。
     */
    public void insert(DisclosurePackageItem item) {
        jdbc.update("""
                        INSERT INTO disclosure_package_item
                            (package_key, item_order, evidence_key, chain_version, custodian_snapshot,
                             seal_snapshot, last_inspection_passed, transfer_count, loan_count,
                             inspection_count, item_summary, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                item.packageKey(), item.itemOrder(), item.evidenceKey(), item.chainVersion(),
                item.custodianSnapshot(), item.sealSnapshot().name(),
                item.lastInspectionPassed(),
                item.transferCount(), item.loanCount(), item.inspectionCount(),
                item.itemSummary(), item.createdAt());
    }

    /**
     * 按规范化排序查询包内全部快照。
     */
    public List<DisclosurePackageItem> findByPackageKey(String packageKey) {
        return jdbc.query(
                "SELECT * FROM disclosure_package_item WHERE package_key = ? ORDER BY item_order",
                ROW_MAPPER, packageKey);
    }

    private static final class ItemRowMapper implements RowMapper<DisclosurePackageItem> {
        @Override
        public DisclosurePackageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            Integer lastPassed = rs.getObject("last_inspection_passed", Integer.class);
            return new DisclosurePackageItem(
                    rs.getLong("id"),
                    rs.getString("package_key"),
                    rs.getInt("item_order"),
                    rs.getString("evidence_key"),
                    rs.getString("chain_version"),
                    rs.getString("custodian_snapshot"),
                    SealSnapshot.valueOf(rs.getString("seal_snapshot")),
                    lastPassed == null ? null : lastPassed == 1,
                    rs.getInt("transfer_count"),
                    rs.getInt("loan_count"),
                    rs.getInt("inspection_count"),
                    rs.getString("item_summary"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
