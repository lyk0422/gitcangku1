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
 * 跨案移交头表访问。记录只追加；撤销仅允许一次条件更新（status 必须仍为 COMPLETED）。
 */
@Repository
public class CaseTransferRepository {

    private static final TransferRowMapper ROW_MAPPER = new TransferRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 COMPLETED 移交头；transfer_id 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String transferId, String sourceCaseKey, String targetCaseKey,
                       String orderVersion, String sourceCustodian, String targetCustodian,
                       String actorId, int evidenceCount, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer
                            (transfer_id, source_case_key, target_case_key, order_version,
                             source_custodian, target_custodian, status, actor_id, evidence_count,
                             created_at, revoked_at, revoke_source_confirmer, revoke_target_confirmer)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                        """,
                transferId, sourceCaseKey, targetCaseKey, orderVersion,
                sourceCustodian, targetCustodian, CaseTransferStatus.COMPLETED.name(),
                actorId, evidenceCount, nowUtc);
    }

    /**
     * 按移交业务键查询（不加锁），用于只读场景。
     */
    public Optional<CaseTransfer> findByTransferId(String transferId) {
        List<CaseTransfer> rows = jdbc.query(
                "SELECT * FROM case_transfer WHERE transfer_id = ?", ROW_MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 按移交业务键查询并锁定移交行（SELECT ... FOR UPDATE），用于撤销。
     */
    public Optional<CaseTransfer> findByTransferIdForUpdate(String transferId) {
        List<CaseTransfer> rows = jdbc.query(
                "SELECT * FROM case_transfer WHERE transfer_id = ? FOR UPDATE", ROW_MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 条件撤销：仅当状态仍为 COMPLETED 时写入撤销结果，原移交记录保留。
     *
     * @return 是否成功撤销（false 表示已被并发撤销）
     */
    public boolean markRevoked(long id, String sourceConfirmer, String targetConfirmer,
                               LocalDateTime revokedAtUtc) {
        int updated = jdbc.update("""
                        UPDATE case_transfer
                        SET status = ?, revoked_at = ?, revoke_source_confirmer = ?, revoke_target_confirmer = ?
                        WHERE id = ? AND status = ?
                        """,
                CaseTransferStatus.REVOKED.name(), revokedAtUtc, sourceConfirmer, targetConfirmer,
                id, CaseTransferStatus.COMPLETED.name());
        return updated == 1;
    }

    private static final class TransferRowMapper implements RowMapper<CaseTransfer> {
        @Override
        public CaseTransfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransfer(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getString("order_version"),
                    rs.getString("source_custodian"),
                    rs.getString("target_custodian"),
                    CaseTransferStatus.valueOf(rs.getString("status")),
                    rs.getString("actor_id"),
                    rs.getInt("evidence_count"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getString("revoke_source_confirmer"),
                    rs.getString("revoke_target_confirmer"));
        }
    }
}
