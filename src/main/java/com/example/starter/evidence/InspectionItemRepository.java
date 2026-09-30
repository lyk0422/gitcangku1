package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 巡检箱内清单表访问。双人确认时冻结快照并只追加；复核放行仅做条件更新
 * （处置必须仍为 QUARANTINED），快照字段永不更新。
 */
@Repository
public class InspectionItemRepository {

    private static final ItemRowMapper ROW_MAPPER = new ItemRowMapper();

    private final JdbcTemplate jdbc;

    public InspectionItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 冻结一条箱内清单（保留巡检时原位置/原保管人/原状态/待接收交接快照）。
     */
    public void insert(long inspectionId, String evidenceKey, String snapshotContainerKey,
                       String snapshotCustodianId, EvidenceStatus snapshotStatus,
                       Long snapshotTransferId, ItemDisposition disposition, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO inspection_item
                            (inspection_id, evidence_key, snapshot_container_key, snapshot_custodian_id,
                             snapshot_status, snapshot_transfer_id, disposition, released_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)
                        """,
                inspectionId, evidenceKey, snapshotContainerKey, snapshotCustodianId,
                snapshotStatus.name(), snapshotTransferId, disposition.name(), now);
    }

    /**
     * 条件放行：仅当处置仍为 QUARANTINED 时置为 RELEASED 并记录放行时刻。
     *
     * @return 是否更新成功（false 表示已放行或本就不在隔离清单）
     */
    public boolean markReleased(long id, LocalDateTime releasedAt) {
        int updated = jdbc.update(
                "UPDATE inspection_item SET disposition = ?, released_at = ? WHERE id = ? AND disposition = ?",
                ItemDisposition.RELEASED.name(), releasedAt, id, ItemDisposition.QUARANTINED.name());
        return updated == 1;
    }

    /**
     * 按清单冻结顺序查询一次巡检的全部箱内条目。
     */
    public List<InspectionItem> findByInspectionId(long inspectionId) {
        return jdbc.query(
                "SELECT * FROM inspection_item WHERE inspection_id = ? ORDER BY id",
                ROW_MAPPER, inspectionId);
    }

    private static final class ItemRowMapper implements RowMapper<InspectionItem> {
        @Override
        public InspectionItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long transferId = rs.getObject("snapshot_transfer_id", Long.class);
            return new InspectionItem(
                    rs.getLong("id"),
                    rs.getLong("inspection_id"),
                    rs.getString("evidence_key"),
                    rs.getString("snapshot_container_key"),
                    rs.getString("snapshot_custodian_id"),
                    EvidenceStatus.valueOf(rs.getString("snapshot_status")),
                    transferId,
                    ItemDisposition.valueOf(rs.getString("disposition")),
                    rs.getObject("released_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
