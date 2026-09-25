package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 封签核验快照表访问。快照只追加、不可变，不提供任何更新路径。
 */
@Repository
public class SealSnapshotRepository {

    private static final SealSnapshotRowMapper ROW_MAPPER = new SealSnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public SealSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 逐件写入封签核验快照（迁移执行时调用）。
     */
    public void insert(String moveKey, String evidenceKey, String sealNo,
                       String fromLocation, String toLocation,
                       String firstConfirmer, String secondConfirmer, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO seal_snapshot
                            (move_key, evidence_key, seal_no, seal_status, from_location, to_location,
                             first_confirmer, second_confirmer, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                moveKey, evidenceKey, sealNo, SealSnapshot.STATUS_INTACT, fromLocation, toLocation,
                firstConfirmer, secondConfirmer, now);
    }

    /**
     * 按迁移键查询逐件封签快照（按写入顺序）。
     */
    public List<SealSnapshot> findByMoveKey(String moveKey) {
        return jdbc.query(
                "SELECT * FROM seal_snapshot WHERE move_key = ? ORDER BY id", ROW_MAPPER, moveKey);
    }

    /**
     * 按证物键查询其全部封签快照（按写入顺序）。
     */
    public List<SealSnapshot> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM seal_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class SealSnapshotRowMapper implements RowMapper<SealSnapshot> {
        @Override
        public SealSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SealSnapshot(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    rs.getString("evidence_key"),
                    rs.getString("seal_no"),
                    rs.getString("seal_status"),
                    rs.getString("from_location"),
                    rs.getString("to_location"),
                    rs.getString("first_confirmer"),
                    rs.getString("second_confirmer"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
