package com.example.starter.maintenance.repository;

import com.example.starter.maintenance.model.MaintenanceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 保养记录数据访问；只增不删。
 */
@Repository
public class MaintenanceRepository {

    private final JdbcTemplate jdbc;

    public MaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MaintenanceRecord map(ResultSet rs, int i) throws SQLException {
        return new MaintenanceRecord(rs.getLong("maintenance_id"),
                rs.getString("anchor_reading_id"),
                rs.getInt("anchor_revision_no"),
                Instant.ofEpochMilli(rs.getLong("anchor_sampled_at_epoch_ms")),
                rs.getLong("anchor_accumulated_minutes"),
                Instant.ofEpochMilli(rs.getLong("completed_at_epoch_ms")));
    }

    public void insert(String equipmentId, String anchorReadingId, int anchorRevisionNo,
                       long anchorSampledAtEpochMs, long anchorAccumulatedMinutes, long completedAtEpochMs) {
        jdbc.update("INSERT INTO maintenance_record"
                        + " (equipment_id, anchor_reading_id, anchor_revision_no,"
                        + " anchor_sampled_at_epoch_ms, anchor_accumulated_minutes, completed_at_epoch_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                equipmentId, anchorReadingId, anchorRevisionNo,
                anchorSampledAtEpochMs, anchorAccumulatedMinutes, completedAtEpochMs);
    }

    /**
     * 最近一次保养（按锚点采样时刻，其次按主键）。
     */
    public Optional<MaintenanceRecord> findLatest(String equipmentId) {
        return jdbc.query("SELECT maintenance_id, anchor_reading_id, anchor_revision_no,"
                        + " anchor_sampled_at_epoch_ms, anchor_accumulated_minutes, completed_at_epoch_ms"
                        + " FROM maintenance_record WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at_epoch_ms DESC, maintenance_id DESC LIMIT 1",
                MaintenanceRepository::map, equipmentId).stream().findFirst();
    }

    public List<MaintenanceRecord> findAllByEquipment(String equipmentId) {
        return jdbc.query("SELECT maintenance_id, anchor_reading_id, anchor_revision_no,"
                        + " anchor_sampled_at_epoch_ms, anchor_accumulated_minutes, completed_at_epoch_ms"
                        + " FROM maintenance_record WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at_epoch_ms ASC, maintenance_id ASC",
                MaintenanceRepository::map, equipmentId);
    }

    /**
     * 指定读数是否作为任意历史保养锚点。
     */
    public boolean isAnchorOfAnyMaintenance(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_record"
                        + " WHERE equipment_id = ? AND anchor_reading_id = ?",
                Integer.class, equipmentId, readingId);
        return count != null && count > 0;
    }
}
