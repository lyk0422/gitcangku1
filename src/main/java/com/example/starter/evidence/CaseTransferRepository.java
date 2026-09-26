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
 * 跨案移交批次与双案封存快照表访问。批次只追加一次；
 * 撤销仅允许一次条件更新（status 必须仍为 COMPLETED），快照不可变。
 */
@Repository
public class CaseTransferRepository {

    private static final CaseTransferRowMapper ROW_MAPPER = new CaseTransferRowMapper();
    private static final CaseTransferItemRowMapper ITEM_ROW_MAPPER = new CaseTransferItemRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个 COMPLETED 跨案移交批次；transfer_id 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String transferId, String sourceCaseKey, String targetCaseKey,
                       String orderVersion, String sourceCustodianId, String targetCustodianId,
                       int evidenceCount, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO case_transfer
                            (transfer_id, source_case_key, target_case_key, order_version,
                             source_custodian_id, target_custodian_id, status, evidence_count,
                             created_at, revoked_at, revoke_source_custodian_id, revoke_target_custodian_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                        """,
                transferId, sourceCaseKey, targetCaseKey, orderVersion,
                sourceCustodianId, targetCustodianId, CaseTransferStatus.COMPLETED.name(),
                evidenceCount, now);
    }

    /**
     * 追加一条双案封存快照（批次明细）。
     */
    public void insertItem(String transferId, String evidenceKey, String sourceCaseKey,
                           String targetCaseKey, String fromLocation, int sealVersion,
                           String orderVersion, long transferSeq, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO case_transfer_item
                            (transfer_id, evidence_key, source_case_key, target_case_key,
                             from_location, seal_version, order_version, transfer_seq, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, sourceCaseKey, targetCaseKey,
                fromLocation, sealVersion, orderVersion, transferSeq, now);
    }

    /**
     * 按业务键查询批次（不加锁），用于只读场景。
     */
    public Optional<CaseTransfer> findByTransferId(String transferId) {
        List<CaseTransfer> rows = jdbc.query(
                "SELECT * FROM case_transfer WHERE transfer_id = ?", ROW_MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定批次行（SELECT ... FOR UPDATE），用于撤销。
     */
    public Optional<CaseTransfer> findByTransferIdForUpdate(String transferId) {
        List<CaseTransfer> rows = jdbc.query(
                "SELECT * FROM case_transfer WHERE transfer_id = ? FOR UPDATE", ROW_MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 条件撤销：仅当批次仍为 COMPLETED 时写入撤销结果，原记录不删除。
     *
     * @return 是否成功撤销（false 表示已被并发撤销）
     */
    public boolean markRevoked(String transferId, String sourceConfirmer, String targetConfirmer,
                               LocalDateTime revokedAt) {
        int updated = jdbc.update("""
                        UPDATE case_transfer
                        SET status = ?, revoked_at = ?,
                            revoke_source_custodian_id = ?, revoke_target_custodian_id = ?
                        WHERE transfer_id = ? AND status = ?
                        """,
                CaseTransferStatus.REVOKED.name(), revokedAt, sourceConfirmer, targetConfirmer,
                transferId, CaseTransferStatus.COMPLETED.name());
        return updated == 1;
    }

    /**
     * 查询批次全部快照明细（按证物键字典序，与规范化请求顺序一致）。
     */
    public List<CaseTransferItem> findItemsByTransferId(String transferId) {
        return jdbc.query(
                "SELECT * FROM case_transfer_item WHERE transfer_id = ? ORDER BY evidence_key",
                ITEM_ROW_MAPPER, transferId);
    }

    /**
     * 查询案件相关的全部批次（作为来源或目标），按发生顺序。
     */
    public List<CaseTransfer> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer WHERE source_case_key = ? OR target_case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey, caseKey);
    }

    private static final class CaseTransferRowMapper implements RowMapper<CaseTransfer> {
        @Override
        public CaseTransfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransfer(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getString("order_version"),
                    rs.getString("source_custodian_id"),
                    rs.getString("target_custodian_id"),
                    CaseTransferStatus.valueOf(rs.getString("status")),
                    rs.getInt("evidence_count"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getString("revoke_source_custodian_id"),
                    rs.getString("revoke_target_custodian_id"));
        }
    }

    private static final class CaseTransferItemRowMapper implements RowMapper<CaseTransferItem> {
        @Override
        public CaseTransferItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferItem(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getString("from_location"),
                    rs.getInt("seal_version"),
                    rs.getString("order_version"),
                    rs.getLong("transfer_seq"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
