package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨案移交保管链表访问。链只追加，不可更新、不可删除。
 */
@Repository
public class CaseTransferChainRepository {

    private static final ChainRowMapper ROW_MAPPER = new ChainRowMapper();

    private final JdbcTemplate jdbc;

    public CaseTransferChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条保管链条目。
     */
    public void insert(String transferId, String evidenceKey, ChainDirection direction,
                       String caseKey, String counterCaseKey, String custodianId,
                       String orderVersion, LocalDateTime nowUtc) {
        jdbc.update("""
                        INSERT INTO case_transfer_chain
                            (transfer_id, evidence_key, direction, case_key, counter_case_key,
                             custodian_id, order_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, direction.name(), caseKey, counterCaseKey,
                custodianId, orderVersion, nowUtc);
    }

    /**
     * 按批次标识查询全部链条目（按写入顺序）。
     */
    public List<CaseTransferChainEntry> findByTransferId(String transferId) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE transfer_id = ? ORDER BY id",
                ROW_MAPPER, transferId);
    }

    /**
     * 按证物查询全部链条目（按写入顺序）。
     */
    public List<CaseTransferChainEntry> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按案件查询全部链条目（按写入顺序）。
     */
    public List<CaseTransferChainEntry> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM case_transfer_chain WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    /**
     * 查询证物在指定时刻之后、不属于指定批次的链条目数，用于撤销前校验是否已发生后续跨案移交。
     */
    public int countLaterTransfers(String evidenceKey, String transferId, LocalDateTime sinceUtc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_transfer_chain "
                        + "WHERE evidence_key = ? AND transfer_id <> ? AND created_at > ?",
                Integer.class, evidenceKey, transferId, sinceUtc);
        return count == null ? 0 : count;
    }

    private static final class ChainRowMapper implements RowMapper<CaseTransferChainEntry> {
        @Override
        public CaseTransferChainEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseTransferChainEntry(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("evidence_key"),
                    ChainDirection.valueOf(rs.getString("direction")),
                    rs.getString("case_key"),
                    rs.getString("counter_case_key"),
                    rs.getString("custodian_id"),
                    rs.getString("order_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
