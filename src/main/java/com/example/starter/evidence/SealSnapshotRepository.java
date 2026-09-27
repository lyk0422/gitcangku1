package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 封签核验快照表访问。只在位置迁移双人确认完成的事务内逐件追加，只追加、不可变，不提供更新语句。
 */
@Repository
public class SealSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public SealSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一件证物的迁移封签快照。
     */
    public void insert(String moveKey, Evidence evidence, String firstConfirmer,
                       String secondConfirmer, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO seal_snapshot
                            (move_key, evidence_key, seal_no, evidence_status, custodian_id,
                             location_code, version, first_confirmer, second_confirmer, snapshot_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                moveKey, evidence.evidenceKey(), evidence.sealNo(), evidence.status().name(),
                evidence.custodianId(), evidence.locationCode(), evidence.version(),
                firstConfirmer, secondConfirmer, now);
    }

    /**
     * 按迁移单查询全部封签快照（按逐件写入顺序）。
     */
    public List<SealSnapshot> findByMoveKey(String moveKey) {
        return jdbc.query(
                "SELECT * FROM seal_snapshot WHERE move_key = ? ORDER BY id",
                ROW_MAPPER, moveKey);
    }

    /**
     * 按证物查询全部封签快照（按迁移发生顺序）。
     */
    public List<SealSnapshot> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM seal_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class SnapshotRowMapper implements RowMapper<SealSnapshot> {
        @Override
        public SealSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SealSnapshot(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    rs.getString("evidence_key"),
                    rs.getString("seal_no"),
                    EvidenceStatus.valueOf(rs.getString("evidence_status")),
                    rs.getString("custodian_id"),
                    rs.getString("location_code"),
                    rs.getLong("version"),
                    rs.getString("first_confirmer"),
                    rs.getString("second_confirmer"),
                    rs.getObject("snapshot_at", LocalDateTime.class));
        }
    }
}
