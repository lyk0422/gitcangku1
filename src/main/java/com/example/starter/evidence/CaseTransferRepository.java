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
 * 跨案移交主表访问。记录只追加；撤销仅允许一次条件更新（status 必须仍为 COMPLETED）。
 */
@Repository
public class CaseTransferRepository {

    private static final CaseTransferRowMapper ROW_MAPPER = new CaseTransferRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一笔已完成移交；transfer_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String transferKey, String sourceCaseKey, String targetCaseKey,
                       String orderVersion, String sourceCustodianId, String targetCustodianId,
                       int evidenceCount, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer
                            (transfer_key, source_case_key, target_case_key, order_version,
                             source_custodian_id, target_custodian_id, evidence_count, status,
                             created_at, revoked_at, revoke_source_confirmer, revoke_target_confirmer)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                        """,
                transferKey, sourceCaseKey, targetCaseKey, orderVersion,
                sourceCustodianId, targetCustodianId, evidenceCount,
                CaseTransferStatus.COMPLETED.name(), nowUtc);
    }

    /**
     * 按移交业务键查询。
     */
    public Optional<CaseTransfer> findByKey(String transferKey) {
        List<CaseTransfer> rows = jdbc.query(
                "SELECT * FROM case_transfer WHERE transfer_key = ?", ROW_MAPPER, transferKey);
        return rows.stream().findFirst();
    }

    /**
     * 条件撤销：仅当移交仍为 COMPLETED 时写入撤销结果与双方确认人，历史不可覆盖。
     *
     * @return 是否成功撤销（false 表示已被并发撤销）
     */
    public boolean revoke(String transferKey, LocalDateTime revokedAt,
                          String sourceConfirmer, String targetConfirmer) {
        int updated = jdbc.update("""
                        UPDATE case_transfer
                        SET status = ?, revoked_at = ?,
                            revoke_source_confirmer = ?, revoke_target_confirmer = ?
                        WHERE transfer_key = ? AND status = ?
                        """,
                CaseTransferStatus.REVOKED.name(), revokedAt, sourceConfirmer, targetConfirmer,
                transferKey, CaseTransferStatus.COMPLETED.name());
        return updated == 1;
    }

    /**
     * 查询涉及指定案件（来源或目标）的全部移交，按发生顺序。
     */
    public List<CaseTransfer> findByCase(String caseKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer WHERE source_case_key = ? OR target_case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey, caseKey);
    }

    /**
     * 统计涉及指定案件的移交数量（按状态分组，诊断用）。
     */
    public List<StatusCount> countByCaseGroupByStatus(String caseKey) {
        return jdbc.query("""
                        SELECT status, COUNT(*) AS cnt FROM case_transfer
                        WHERE source_case_key = ? OR target_case_key = ?
                        GROUP BY status ORDER BY status
                        """,
                (rs, rowNum) -> new StatusCount(
                        CaseTransferStatus.valueOf(rs.getString("status")), rs.getLong("cnt")),
                caseKey, caseKey);
    }

    /**
     * 诊断计数：状态 + 数量。
     *
     * @param status 移交状态
     * @param count  该状态移交数量（实际值）
     */
    public record StatusCount(CaseTransferStatus status, long count) {
    }

    private static final class CaseTransferRowMapper implements RowMapper<CaseTransfer> {
        @Override
        public CaseTransfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransfer(
                    rs.getLong("id"),
                    rs.getString("transfer_key"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getString("order_version"),
                    rs.getString("source_custodian_id"),
                    rs.getString("target_custodian_id"),
                    rs.getInt("evidence_count"),
                    CaseTransferStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getString("revoke_source_confirmer"),
                    rs.getString("revoke_target_confirmer"));
        }
    }
}
