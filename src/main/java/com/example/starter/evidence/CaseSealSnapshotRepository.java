package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双案封存快照表访问。快照只追加，撤销不删除；同一 transferId 每件证物两行（FROM/TO）。
 */
@Repository
public class CaseSealSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public CaseSealSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条封存快照。
     */
    public void insert(String transferId, String caseKey, SnapshotSide side, String evidenceKey,
                       String originalLocation, int sealVersion, int orderVersion,
                       long chainEventId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO case_seal_snapshot
                            (transfer_id, case_key, side, evidence_key, original_location,
                             seal_version, order_version, chain_event_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, caseKey, side.name(), evidenceKey, originalLocation,
                sealVersion, orderVersion, chainEventId, now);
    }

    /**
     * 按移交 ID 查询全部快照（按侧别与规范化序号）。
     */
    public List<CaseSealSnapshot> findByTransferId(String transferId) {
        return jdbc.query("""
                        SELECT s.* FROM case_seal_snapshot s
                        JOIN case_transfer_item i
                            ON i.transfer_id = s.transfer_id AND i.evidence_key = s.evidence_key
                        WHERE s.transfer_id = ?
                        ORDER BY s.side, i.seq
                        """,
                ROW_MAPPER, transferId);
    }

    /**
     * 按证物查询全部快照（按固化顺序）。
     */
    public List<CaseSealSnapshot> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM case_seal_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class SnapshotRowMapper implements RowMapper<CaseSealSnapshot> {
        @Override
        public CaseSealSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CaseSealSnapshot(
                    rs.getLong("id"),
                    rs.getString("transfer_id"),
                    rs.getString("case_key"),
                    SnapshotSide.valueOf(rs.getString("side")),
                    rs.getString("evidence_key"),
                    rs.getString("original_location"),
                    rs.getInt("seal_version"),
                    rs.getInt("order_version"),
                    rs.getLong("chain_event_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
