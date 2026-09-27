package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双案保管链表访问。记录只追加、不可变，不提供任何更新或删除语句。
 */
@Repository
public class CaseTransferChainRepository {

    /** 方向：移出案件。 */
    public static final String DIRECTION_OUT = "OUT";
    /** 方向：移入案件。 */
    public static final String DIRECTION_IN = "IN";
    /** 条目类型：移交。 */
    public static final String KIND_TRANSFER = "TRANSFER";
    /** 条目类型：撤销反向。 */
    public static final String KIND_REVOKE = "REVOKE";

    private static final ChainRowMapper ROW_MAPPER = new ChainRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条保管链条目。
     */
    public void insert(String transferKey, String evidenceKey, String caseKey,
                       String direction, String entryKind, String custodianId,
                       LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer_chain
                            (transfer_key, evidence_key, case_key, direction, entry_kind,
                             custodian_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                transferKey, evidenceKey, caseKey, direction, entryKind, custodianId, nowUtc);
    }

    /**
     * 按移交业务键查询全部链条目（按写入顺序）。
     */
    public List<CaseTransferChainEntry> findByTransferKey(String transferKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE transfer_key = ? ORDER BY id",
                ROW_MAPPER, transferKey);
    }

    /**
     * 按证物查询全部链条目（按写入顺序），证物维度只读历史。
     */
    public List<CaseTransferChainEntry> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按案件查询全部链条目（按写入顺序），案件维度只读历史。
     */
    public List<CaseTransferChainEntry> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    private static final class ChainRowMapper implements RowMapper<CaseTransferChainEntry> {
        @Override
        public CaseTransferChainEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferChainEntry(
                    rs.getLong("id"),
                    rs.getString("transfer_key"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    rs.getString("direction"),
                    rs.getString("entry_kind"),
                    rs.getString("custodian_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
