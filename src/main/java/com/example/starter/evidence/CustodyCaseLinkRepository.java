package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双案保管链事件表访问。只追加、不可变，不提供任何更新或删除语句。
 */
@Repository
public class CustodyCaseLinkRepository {

    private static final LinkRowMapper ROW_MAPPER = new LinkRowMapper();

    private final JdbcTemplate jdbc;

    public CustodyCaseLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条链事件。
     */
    public void insert(String caseKey, String evidenceKey, String transferId,
                       ChainDirection direction, String orderVersion, String actorId,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO custody_case_link
                            (case_key, evidence_key, transfer_id, direction, order_version, actor_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                caseKey, evidenceKey, transferId, direction.name(), orderVersion, actorId, now);
    }

    /**
     * 按案件查询全部链事件（按发生顺序）。
     */
    public List<CustodyCaseLink> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM custody_case_link WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    /**
     * 按证物查询全部链事件（按发生顺序），跨案证据随证物可见。
     */
    public List<CustodyCaseLink> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM custody_case_link WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按批次查询全部链事件（按发生顺序）。
     */
    public List<CustodyCaseLink> findByTransferId(String transferId) {
        return jdbc.query(
                "SELECT * FROM custody_case_link WHERE transfer_id = ? ORDER BY id",
                ROW_MAPPER, transferId);
    }

    /**
     * 统计指定案件在指定批次中的链事件实际条数（诊断用）。
     */
    public int countByTransferAndCase(String transferId, String caseKey) {
        List<Integer> rows = jdbc.query(
                "SELECT COUNT(*) FROM custody_case_link WHERE transfer_id = ? AND case_key = ?",
                (rs, rowNum) -> rs.getInt(1), transferId, caseKey);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static final class LinkRowMapper implements RowMapper<CustodyCaseLink> {
        @Override
        public CustodyCaseLink mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CustodyCaseLink(
                    rs.getLong("id"),
                    rs.getString("case_key"),
                    rs.getString("evidence_key"),
                    rs.getString("transfer_id"),
                    ChainDirection.valueOf(rs.getString("direction")),
                    rs.getString("order_version"),
                    rs.getString("actor_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
