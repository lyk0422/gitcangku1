package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双案保管链表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class CaseTransferChainRepository {

    private static final ChainRowMapper ROW_MAPPER = new ChainRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条链条目（来源移出/目标移入/撤销反向）。
     */
    public void insert(String transferId, String evidenceKey, String caseKey,
                       ChainEntryType entryType, String custodianId, String orderVersion,
                       LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer_chain
                            (transfer_id, evidence_key, case_key, entry_type, custodian_id, order_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, caseKey, entryType.name(), custodianId, orderVersion, nowUtc);
    }

    /**
     * 按移交业务键查询全部链条目（按写入顺序）。
     */
    public List<CaseTransferChainEntry> findByTransferId(String transferId) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE transfer_id = ? ORDER BY id",
                ROW_MAPPER, transferId);
    }

    /**
     * 按案件查询全部链条目（按写入顺序），即该案件的只读链证据。
     */
    public List<CaseTransferChainEntry> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    /**
     * 按证物查询全部链条目（按写入顺序）。
     */
    public List<CaseTransferChainEntry> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class ChainRowMapper implements RowMapper<CaseTransferChainEntry> {
        @Override
        public CaseTransferChainEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferChainEntry(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    ChainEntryType.valueOf(rs.getString("entry_type")),
                    rs.getString("custodian_id"),
                    rs.getString("order_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
