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
 * 披露包证物明细表访问。快照行只在建包与批量增删时写入或删除，内容从不更新；
 * 证物侧的迁移、复核或新增链记录不回写本表。
 */
@Repository
public class DisclosurePackageItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public DisclosurePackageItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条冻结快照明细。
     */
    public void insert(String packageKey, String evidenceKey, int seqNo, String custodianId,
                       boolean sealIntact, int chainVersion, String chainDigest, String snapshot,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO disclosure_package_item
                            (package_key, evidence_key, seq_no, custodian_id, seal_intact,
                             chain_version, chain_digest, snapshot, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                packageKey, evidenceKey, seqNo, custodianId, sealIntact ? 1 : 0,
                chainVersion, chainDigest, snapshot, now);
    }

    /**
     * 按包内规范排序位次查询全部明细。
     */
    public List<DisclosurePackageItem> findByPackageKey(String packageKey) {
        return jdbc.query(
                "SELECT * FROM disclosure_package_item WHERE package_key = ? ORDER BY seq_no",
                ROW_MAPPER, packageKey);
    }

    /**
     * 查询包内指定证物的明细。
     */
    public Optional<DisclosurePackageItem> findByPackageKeyAndEvidenceKey(String packageKey,
                                                                          String evidenceKey) {
        List<DisclosurePackageItem> rows = jdbc.query(
                "SELECT * FROM disclosure_package_item WHERE package_key = ? AND evidence_key = ?",
                ROW_MAPPER, packageKey, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 删除包内指定证物的明细（批量增删时移除用）。
     */
    public void deleteByPackageKeyAndEvidenceKey(String packageKey, String evidenceKey) {
        jdbc.update(
                "DELETE FROM disclosure_package_item WHERE package_key = ? AND evidence_key = ?",
                packageKey, evidenceKey);
    }

    /**
     * 批量增删后重排位次。
     */
    public void updateSeqNo(String packageKey, String evidenceKey, int seqNo) {
        jdbc.update(
                "UPDATE disclosure_package_item SET seq_no = ? WHERE package_key = ? AND evidence_key = ?",
                seqNo, packageKey, evidenceKey);
    }

    private static final class ItemRowMapper implements RowMapper<DisclosurePackageItem> {
        @Override
        public DisclosurePackageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DisclosurePackageItem(
                    rs.getLong("id"),
                    rs.getString("package_key"),
                    rs.getString("evidence_key"),
                    rs.getInt("seq_no"),
                    rs.getString("custodian_id"),
                    rs.getInt("seal_intact") == 1,
                    rs.getInt("chain_version"),
                    rs.getString("chain_digest"),
                    rs.getString("snapshot"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
