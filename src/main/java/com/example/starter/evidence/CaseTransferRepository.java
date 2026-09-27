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
 * 跨案移交主单与明细表访问。主单只追加；撤销为一次条件更新（仅 TRANSFERRED 可撤销），
 * 原移交与明细不删除。
 */
@Repository
public class CaseTransferRepository {

    private static final TransferRowMapper TRANSFER_MAPPER = new TransferRowMapper();
    private static final ItemRowMapper ITEM_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一笔跨案移交主单（初始 TRANSFERRED）。
     */
    public void insert(String transferId, String orderNo, int orderVersion,
                       String fromCaseKey, String toCaseKey, String fromCustodian,
                       String toCustodian, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO case_transfer
                            (transfer_id, order_no, order_version, from_case_key, to_case_key,
                             from_custodian, to_custodian, status, created_at,
                             revoked_at, revoke_from_custodian, revoke_to_custodian)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                        """,
                transferId, orderNo, orderVersion, fromCaseKey, toCaseKey,
                fromCustodian, toCustodian, CaseTransferStatus.TRANSFERRED.name(), now);
    }

    /**
     * 追加一条移交明细（seq 为规范化排序序号，从 0 开始）。
     */
    public void insertItem(String transferId, String evidenceKey, int seq,
                           String originalLocation, int sealVersion, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO case_transfer_item
                            (transfer_id, evidence_key, seq, original_location, seal_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, seq, originalLocation, sealVersion, now);
    }

    /**
     * 按业务 ID 查询移交主单。
     */
    public Optional<CaseTransfer> findById(String transferId) {
        List<CaseTransfer> rows = jdbc.query(
                "SELECT * FROM case_transfer WHERE transfer_id = ?", TRANSFER_MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 条件撤销：仅当主单仍为 TRANSFERRED 时写入撤销信息。
     *
     * @return 是否撤销成功（false 表示不存在或已撤销）
     */
    public boolean revoke(String transferId, String fromCustodian, String toCustodian,
                          LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE case_transfer
                        SET status = ?, revoked_at = ?,
                            revoke_from_custodian = ?, revoke_to_custodian = ?
                        WHERE transfer_id = ? AND status = ?
                        """,
                CaseTransferStatus.REVOKED.name(), now, fromCustodian, toCustodian,
                transferId, CaseTransferStatus.TRANSFERRED.name());
        return updated == 1;
    }

    /**
     * 查询某证物已涉及的全部未撤销移交（用于识别“已处于其他移交中”）。
     */
    public List<CaseTransfer> findActiveByEvidenceKey(String evidenceKey) {
        return jdbc.query("""
                        SELECT t.* FROM case_transfer t
                        JOIN case_transfer_item i ON i.transfer_id = t.transfer_id
                        WHERE i.evidence_key = ? AND t.status = ?
                        ORDER BY t.id
                        """,
                TRANSFER_MAPPER, evidenceKey, CaseTransferStatus.TRANSFERRED.name());
    }

    /**
     * 查询某证物涉及的全部移交（历史查询，按发生顺序）。
     */
    public List<CaseTransfer> findAllByEvidenceKey(String evidenceKey) {
        return jdbc.query("""
                        SELECT t.* FROM case_transfer t
                        JOIN case_transfer_item i ON i.transfer_id = t.transfer_id
                        WHERE i.evidence_key = ?
                        ORDER BY t.id
                        """,
                TRANSFER_MAPPER, evidenceKey);
    }

    /**
     * 查询移交单全部明细（按规范化序号）。
     */
    public List<CaseTransferItem> findItems(String transferId) {
        return jdbc.query(
                "SELECT * FROM case_transfer_item WHERE transfer_id = ? ORDER BY seq",
                ITEM_MAPPER, transferId);
    }

    /**
     * 判断证物在某时刻之后是否已产生旧流程交接（保管人之间的交接记录）。
     */
    public boolean existsPersonTransferAfter(String evidenceKey, LocalDateTime after) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM transfer_record
                        WHERE evidence_key = ? AND created_at > ?
                        """,
                Integer.class, evidenceKey, after);
        return count != null && count > 0;
    }

    /**
     * 判断证物在某时刻之后是否已产生借出（物理保管移交借用人）。
     */
    public boolean existsLoanAfter(String evidenceKey, LocalDateTime after) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_record WHERE evidence_key = ? AND created_at > ?",
                Integer.class, evidenceKey, after);
        return count != null && count > 0;
    }

    /**
     * 判断证物在某笔移交之后是否又发生过其他跨案移交链事件。
     */
    public boolean existsLaterCaseTransferEvent(String evidenceKey, String transferId,
                                                LocalDateTime after) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM case_custody_chain_event
                        WHERE evidence_key = ? AND transfer_id <> ? AND created_at > ?
                        """,
                Integer.class, evidenceKey, transferId, after);
        return count != null && count > 0;
    }

    private static final class TransferRowMapper implements RowMapper<CaseTransfer> {
        @Override
        public CaseTransfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransfer(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("order_no"),
                    rs.getInt("order_version"),
                    rs.getString("from_case_key"),
                    rs.getString("to_case_key"),
                    rs.getString("from_custodian"),
                    rs.getString("to_custodian"),
                    CaseTransferStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getString("revoke_from_custodian"),
                    rs.getString("revoke_to_custodian"));
        }
    }

    private static final class ItemRowMapper implements RowMapper<CaseTransferItem> {
        @Override
        public CaseTransferItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferItem(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("seq"),
                    rs.getString("original_location"),
                    rs.getInt("seal_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
