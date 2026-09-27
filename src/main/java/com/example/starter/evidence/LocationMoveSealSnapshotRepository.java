package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移封签核验快照表访问。快照只追加、不可变，不提供任何更新语句。
 */
@Repository
public class LocationMoveSealSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public LocationMoveSealSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条封签核验快照（第二人确认时逐件写入）。
     */
    public void insert(String moveKey, String evidenceKey, String sealNo, boolean sealIntact,
                       String locationCode, long locationVersion, String checkedBy,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO location_move_seal_snapshot
                            (move_key, evidence_key, seal_no, seal_intact,
                             location_code, location_version, checked_by, checked_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                moveKey, evidenceKey, sealNo, sealIntact ? 1 : 0,
                locationCode, locationVersion, checkedBy, now);
    }

    /**
     * 按迁移键查询全部快照（按写入顺序）。
     */
    public List<LocationMoveSealSnapshot> findByMoveKey(String moveKey) {
        return jdbc.query(
                "SELECT * FROM location_move_seal_snapshot WHERE move_key = ? ORDER BY id",
                ROW_MAPPER, moveKey);
    }

    /**
     * 按证物键查询全部快照（按写入顺序）。
     */
    public List<LocationMoveSealSnapshot> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM location_move_seal_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class SnapshotRowMapper implements RowMapper<LocationMoveSealSnapshot> {
        @Override
        public LocationMoveSealSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LocationMoveSealSnapshot(
                    rs.getLong("id"),
                    rs.getString("move_key"),
                    rs.getString("evidence_key"),
                    rs.getString("seal_no"),
                    rs.getInt("seal_intact") == 1,
                    rs.getString("location_code"),
                    rs.getLong("location_version"),
                    rs.getString("checked_by"),
                    rs.getObject("checked_at", LocalDateTime.class));
        }
    }
}
