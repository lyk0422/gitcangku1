package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 披露包逐件固化条目表访问。条目只追加、不可变，不提供任何更新语句。
 */
@Repository
public class DisclosurePackageItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public DisclosurePackageItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一件固化条目。
     */
    public void insert(long packageId, String caseKey, int packageVersion, int position,
                       String evidenceKey, int chainVersion, String custodianAtAsOf,
                       String sealSnapshotJson, String itemSummaryJson, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO disclosure_package_item
                            (package_id, case_key, package_version, position, evidence_key,
                             chain_version, custodian_at_as_of, seal_snapshot_json, item_summary_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                packageId, caseKey, packageVersion, position, evidenceKey,
                chainVersion, custodianAtAsOf, sealSnapshotJson, itemSummaryJson, now);
    }

    /**
     * 按包主键查询全部条目（件次升序）。
     */
    public List<DisclosurePackageItem> findByPackageId(long packageId) {
        return jdbc.query(
                "SELECT * FROM disclosure_package_item WHERE package_id = ? ORDER BY position",
                ROW_MAPPER, packageId);
    }

    private static final class ItemRowMapper implements RowMapper<DisclosurePackageItem> {
        @Override
        public DisclosurePackageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DisclosurePackageItem(
                    rs.getLong("id"),
                    rs.getLong("package_id"),
                    rs.getString("case_key"),
                    rs.getInt("package_version"),
                    rs.getInt("position"),
                    rs.getString("evidence_key"),
                    rs.getInt("chain_version"),
                    rs.getString("custodian_at_as_of"),
                    rs.getString("seal_snapshot_json"),
                    rs.getString("item_summary_json"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
