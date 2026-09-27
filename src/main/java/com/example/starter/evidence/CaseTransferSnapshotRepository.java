package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双案封存快照表访问。快照只追加，不可更新、不可删除。
 */
@Repository
public class CaseTransferSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条双案封存快照。
     */
    public void insert(String transferId, String evidenceKey, String sourceCaseKey, String targetCaseKey,
                       String sourceCustodianId, String targetCustodianId, String originLocation,
                       String sealNo, String orderVersion, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer_snapshot
                            (transfer_id, evidence_key, source_case_key, target_case_key,
                             source_custodian_id, target_custodian_id, origin_location,
                             seal_no, order_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, sourceCaseKey, targetCaseKey,
                sourceCustodianId, targetCustodianId, originLocation,
                sealNo, orderVersion, nowUtc);
    }

    /**
     * 按批次标识查询全部快照（按证物键排序，与请求规范化顺序一致）。
     */
    public List<CaseTransferSnapshot> findByTransferId(String transferId) {
        return jdbc.query(
                "SELECT * FROM case_transfer_snapshot WHERE transfer_id = ? ORDER BY evidence_key",
                ROW_MAPPER, transferId);
    }

    /**
     * 按证物查询全部快照（按写入顺序）。
     */
    public List<CaseTransferSnapshot> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 查询案件作为来源或目标参与过的全部批次标识（按批次标识排序去重）。
     */
    public List<String> findTransferIdsByCase(String caseKey) {
        return jdbc.query(
                "SELECT DISTINCT transfer_id FROM case_transfer_snapshot "
                        + "WHERE source_case_key = ? OR target_case_key = ? ORDER BY transfer_id",
                (rs, rowNum) -> rs.getString(1), caseKey, caseKey);
    }

    private static final class SnapshotRowMapper implements RowMapper<CaseTransferSnapshot> {
        @Override
        public CaseTransferSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferSnapshot(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getString("source_case_key"),
                    rs.getString("target_case_key"),
                    rs.getString("source_custodian_id"),
                    rs.getString("target_custodian_id"),
                    rs.getString("origin_location"),
                    rs.getString("seal_no"),
                    rs.getString("order_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
