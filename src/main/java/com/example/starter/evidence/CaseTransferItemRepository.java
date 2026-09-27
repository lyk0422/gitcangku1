package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双案封存快照表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class CaseTransferItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条双案封存快照。
     */
    public void insert(String transferKey, String evidenceKey,
                       String sourceCaseKey, String targetCaseKey,
                       String sourceCustodianId, String targetCustodianId,
                       String sealNo, int sealVersion, int handoverCount, int loanCount,
                       String orderVersion, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer_item
                            (transfer_key, evidence_key, source_case_key, target_case_key,
                             source_custodian_id, target_custodian_id, seal_no, seal_version,
                             handover_count, loan_count, order_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transferKey, evidenceKey, sourceCaseKey, targetCaseKey,
                sourceCustodianId, targetCustodianId, sealNo, sealVersion,
                handoverCount, loanCount, orderVersion, nowUtc);
    }

    /**
     * 按移交业务键查询全部快照（按写入顺序，与规范化证物排序一致）。
     */
    public List<CaseTransferItem> findByTransferKey(String transferKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_item WHERE transfer_key = ? ORDER BY id",
                ROW_MAPPER, transferKey);
    }

    /**
     * 按证物查询全部快照（按写入顺序），用于证物维度历史。
     */
    public List<CaseTransferItem> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_item WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 查询证物在指定时刻之后是否被其他移交包含（撤销前校验是否已发生后续跨案移交）。
     */
    public boolean existsForEvidenceAfter(String evidenceKey, String excludeTransferKey,
                                          LocalDateTime afterUtc) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM case_transfer_item
                        WHERE evidence_key = ? AND transfer_key <> ? AND created_at >= ?
                        """,
                Integer.class, evidenceKey, excludeTransferKey, afterUtc);
        return count != null && count > 0;
    }

    private static final class ItemRowMapper implements RowMapper<CaseTransferItem> {
        @Override
        public CaseTransferItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferItem(
                    rs.getLong("id"),
                    rs.getString("transfer_key"),
                    rs.getString("evidence_key"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getString("source_custodian_id"),
                    rs.getString("target_custodian_id"),
                    rs.getString("seal_no"),
                    rs.getInt("seal_version"),
                    rs.getInt("handover_count"),
                    rs.getInt("loan_count"),
                    rs.getString("order_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
