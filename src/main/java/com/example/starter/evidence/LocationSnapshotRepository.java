package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 证物历史位置轨迹表访问。只追加，不提供任何更新语句；
 * 隔离轨迹保留巡检时原位置与原保管人，复核放行追加新行而不改写历史。
 */
@Repository
public class LocationSnapshotRepository {

    private static final LocationRowMapper ROW_MAPPER = new LocationRowMapper();

    private final JdbcTemplate jdbc;

    public LocationSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条位置轨迹。
     */
    public void insert(String evidenceKey, String containerKey, String custodianId,
                       LocationReason reason, Long inspectionId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_location_snapshot
                            (evidence_key, container_key, custodian_id, reason, inspection_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, containerKey, custodianId, reason.name(), inspectionId, now);
    }

    /**
     * 按发生顺序查询证物全部位置轨迹。
     */
    public List<LocationSnapshot> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM evidence_location_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class LocationRowMapper implements RowMapper<LocationSnapshot> {
        @Override
        public LocationSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long inspectionId = rs.getObject("inspection_id", Long.class);
            return new LocationSnapshot(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("container_key"),
                    rs.getString("custodian_id"),
                    LocationReason.valueOf(rs.getString("reason")),
                    inspectionId,
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
