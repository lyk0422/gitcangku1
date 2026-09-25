package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 逐件巡检快照表访问。快照只追加、不可变，与 FAIL 巡检同事务写入。
 */
@Repository
public class ContainerInspectionSnapshotRepository {

    private static final SnapshotRowMapper ROW_MAPPER = new SnapshotRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerInspectionSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一件证物的不可变快照。
     */
    public void insert(long inspectionId, String containerKey, Evidence evidence,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_inspection_snapshot
                            (inspection_id, container_key, evidence_key, evidence_status,
                             custodian_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                inspectionId, containerKey, evidence.evidenceKey(), evidence.status().name(),
                evidence.custodianId(), now);
    }

    /**
     * 按巡检记录查询全部逐件快照（按证物键排序）。
     */
    public List<ContainerInspectionSnapshot> findByInspection(long inspectionId) {
        return jdbc.query(
                "SELECT * FROM container_inspection_snapshot WHERE inspection_id = ? ORDER BY id",
                ROW_MAPPER, inspectionId);
    }

    /**
     * 按证物查询其全部历史快照（按发生顺序）。
     */
    public List<ContainerInspectionSnapshot> findByEvidence(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM container_inspection_snapshot WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class SnapshotRowMapper implements RowMapper<ContainerInspectionSnapshot> {
        @Override
        public ContainerInspectionSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerInspectionSnapshot(
                    rs.getLong("id"),
                    rs.getLong("inspection_id"),
                    rs.getString("container_key"),
                    rs.getString("evidence_key"),
                    EvidenceStatus.valueOf(rs.getString("evidence_status")),
                    rs.getString("custodian_id"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
