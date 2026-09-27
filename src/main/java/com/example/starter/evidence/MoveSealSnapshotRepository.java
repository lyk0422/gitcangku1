package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移封签快照表访问。快照只追加、不可变，不提供任何更新语句。
 */
@Repository
public class MoveSealSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public MoveSealSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一件证物的迁移封签快照。
     */
    public void insert(String moveKey, String evidenceKey, String sealNo,
                       String fromLocation, String toLocation,
                       String firstConfirmer, String secondConfirmer, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO move_seal_snapshot
                            (move_key, evidence_key, seal_no, from_location, to_location,
                             first_confirmer, second_confirmer, snapshot_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                moveKey, evidenceKey, sealNo, fromLocation, toLocation,
                firstConfirmer, secondConfirmer, now);
    }

    /**
     * 按迁移业务键查询全部快照（按写入顺序）。
     */
    public List<MoveSealSnapshot> findByMoveKey(String moveKey) {
        return jdbc.query(
                "SELECT * FROM move_seal_snapshot WHERE move_key = ? ORDER BY id",
                ROW_MAPPER, moveKey);
    }

    /**
     * 按证物业务键查询全部迁移快照（按写入顺序）。
     */
    public List<MoveSealSnapshot> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM move_seal_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class SnapshotRowMapper implements RowMapper<MoveSealSnapshot> {
        @Override
        public MoveSealSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MoveSealSnapshot(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    rs.getString("evidence_key"),
                    rs.getString("seal_no"),
                    rs.getString("from_location"),
                    rs.getString("to_location"),
                    rs.getString("first_confirmer"),
                    rs.getString("second_confirmer"),
                    rs.getObject("snapshot_at", LocalDateTime.class));
        }
    }
}
