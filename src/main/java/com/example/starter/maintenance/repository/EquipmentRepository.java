package com.example.starter.maintenance.repository;

import com.example.starter.maintenance.model.Equipment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备表数据访问。
 */
@Repository
public class EquipmentRepository {

    private final JdbcTemplate jdbc;

    public EquipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String equipmentId, int intervalMinutes, long nowEpochMs) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_interval_minutes, version, created_at_epoch_ms)"
                        + " VALUES (?, ?, 1, ?)",
                equipmentId, intervalMinutes, nowEpochMs);
    }

    public Optional<Equipment> findById(String equipmentId) {
        return jdbc.query("SELECT equipment_id, maintenance_interval_minutes, version FROM equipment WHERE equipment_id = ?",
                (rs, i) -> new Equipment(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                equipmentId).stream().findFirst();
    }

    /**
     * 加行锁读取设备，串行化同一设备的写操作，配合版本校验避免并发冲突。
     */
    public Optional<Equipment> findByIdForUpdate(String equipmentId) {
        return jdbc.query("SELECT equipment_id, maintenance_interval_minutes, version FROM equipment"
                        + " WHERE equipment_id = ? FOR UPDATE",
                (rs, i) -> new Equipment(rs.getString(1), rs.getInt(2), rs.getInt(3)),
                equipmentId).stream().findFirst();
    }

    public void incrementVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE equipment_id = ?", equipmentId);
    }
}
