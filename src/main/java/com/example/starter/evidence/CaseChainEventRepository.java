package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 案件保管链事件表访问。事件只追加、不可变，不提供任何更新或删除语句。
 */
@Repository
public class CaseChainEventRepository {

    private static final EventRowMapper ROW_MAPPER = new EventRowMapper();

    private final JdbcTemplate jdbc;

    public CaseChainEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条链事件并返回自增主键（双案封存快照引用该 ID）。
     */
    public long insert(String transferId, String evidenceKey, String caseKey,
                       ChainEventType eventType, int seqInTransfer, String custodianId,
                       String location, int sealVersion, int orderVersion, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO case_custody_chain_event
                                (transfer_id, evidence_key, case_key, event_type, seq_in_transfer,
                                 custodian_id, location, seal_version, order_version, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, transferId);
            ps.setString(2, evidenceKey);
            ps.setString(3, caseKey);
            ps.setString(4, eventType.name());
            ps.setInt(5, seqInTransfer);
            ps.setString(6, custodianId);
            ps.setString(7, location);
            ps.setInt(8, sealVersion);
            ps.setInt(9, orderVersion);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("链事件自增主键缺失");
        }
        return key.longValue();
    }

    /**
     * 按证物查询全部案件链事件（按发生顺序）。
     */
    public List<CaseChainEvent> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_custody_chain_event WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按案件查询全部案件链事件（按发生顺序）。
     */
    public List<CaseChainEvent> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM case_custody_chain_event WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    private static final class EventRowMapper implements RowMapper<CaseChainEvent> {
        @Override
        public CaseChainEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseChainEvent(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    ChainEventType.valueOf(rs.getString("event_type")),
                    rs.getInt("seq_in_transfer"),
                    rs.getString("custodian_id"),
                    rs.getString("location"),
                    rs.getInt("seal_version"),
                    rs.getInt("order_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
