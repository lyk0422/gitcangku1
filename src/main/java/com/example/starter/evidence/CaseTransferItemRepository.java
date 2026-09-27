package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨案移交明细（双案封存快照）表访问。快照字段写入后不可修改；撤销仅更新状态与撤销时间。
 */
@Repository
public class CaseTransferItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条移交明细快照；(transfer_id, evidence_key) 唯一。
     */
    public void insert(String transferId, String evidenceKey, int seqNo,
                       String sourceCaseKey, String targetCaseKey,
                       String sourceCustodian, String targetCustodian,
                       String sealNo, int sealVersion, String orderVersion,
                       LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer_item
                            (transfer_id, evidence_key, seq_no, source_case_key, target_case_key,
                             source_custodian, target_custodian, seal_no, seal_version, order_version,
                             status, created_at, revoked_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                transferId, evidenceKey, seqNo, sourceCaseKey, targetCaseKey,
                sourceCustodian, targetCustodian, sealNo, sealVersion, orderVersion,
                CaseTransferItemStatus.TRANSFERRED.name(), nowUtc);
    }

    /**
     * 按移交业务键查询全部明细（按批内序号排序）。
     */
    public List<CaseTransferItem> findByTransferId(String transferId) {
        return jdbc.query(
                "SELECT * FROM case_transfer_item WHERE transfer_id = ? ORDER BY seq_no",
                ROW_MAPPER, transferId);
    }

    /**
     * 按证物查询全部移交明细（按发生顺序），用于证物跨案历史查询。
     */
    public List<CaseTransferItem> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_item WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 将某移交的全部明细标记为 REVOKED（调用前必须已锁定移交头行）。
     */
    public void markRevokedByTransferId(String transferId, LocalDateTime revokedAtUtc) {
        jdbc.update(
                "UPDATE case_transfer_item SET status = ?, revoked_at = ? WHERE transfer_id = ?",
                CaseTransferItemStatus.REVOKED.name(), revokedAtUtc, transferId);
    }

    private static final class ItemRowMapper implements RowMapper<CaseTransferItem> {
        @Override
        public CaseTransferItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferItem(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("seq_no"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getString("source_custodian"),
                    rs.getString("target_custodian"),
                    rs.getString("seal_no"),
                    rs.getInt("seal_version"),
                    rs.getString("order_version"),
                    CaseTransferItemStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class));
        }
    }
}
