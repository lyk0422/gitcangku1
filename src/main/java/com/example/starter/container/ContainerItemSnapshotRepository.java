package com.example.starter.container;

import com.example.starter.evidence.EvidenceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 逐件巡检快照表访问。仅 FAIL 巡检写入，只追加、不可变，不提供任何更新语句。
 */
@Repository
public class ContainerItemSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerItemSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 为一件装载证物写入不可变快照。
     */
    public void insert(long inspectionId, String containerId, String evidenceKey,
                       EvidenceStatus evidenceStatus, String custodianId, String sealNo,
                       int snapshotNo, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_item_snapshot
                            (inspection_id, container_id, evidence_key, evidence_status,
                             custodian_id, seal_no, snapshot_no, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                inspectionId, containerId, evidenceKey, evidenceStatus.name(), custodianId,
                sealNo, snapshotNo, now);
    }

    /**
     * 按容器查询全部逐件快照（按巡检与件次顺序）。
     */
    public List<ContainerItemSnapshot> findByContainerId(String containerId) {
        return jdbc.query("""
                        SELECT * FROM container_item_snapshot
                        WHERE container_id = ?
                        ORDER BY inspection_id, snapshot_no
                        """,
                ROW_MAPPER, containerId);
    }

    /**
     * 按巡检记录查询全部逐件快照（按件次顺序）。
     */
    public List<ContainerItemSnapshot> findByInspectionId(long inspectionId) {
        return jdbc.query("""
                        SELECT * FROM container_item_snapshot
                        WHERE inspection_id = ?
                        ORDER BY snapshot_no
                        """,
                ROW_MAPPER, inspectionId);
    }

    private static final class SnapshotRowMapper implements RowMapper<ContainerItemSnapshot> {
        @Override
        public ContainerItemSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerItemSnapshot(
                    rs.getLong("id"),
                    rs.getLong("inspection_id"),
                    rs.getString("container_id"),
                    rs.getString("evidence_key"),
                    EvidenceStatus.valueOf(rs.getString("evidence_status")),
                    rs.getString("custodian_id"),
                    rs.getString("seal_no"),
                    rs.getInt("snapshot_no"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
