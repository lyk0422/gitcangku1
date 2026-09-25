package com.example.starter.firmware.repo;

import com.example.starter.firmware.api.IncompatibleRecordView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备拉取不兼容记录数据访问。只增不改，作为失败率隔离与按型号投放统计的依据。
 */
@Repository
public class IncompatibleRecordRepository {

    private final JdbcTemplate jdbc;

    public IncompatibleRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String deviceId, String hardwareModel, long releaseId,
                       String toVersion, int matrixVersion) {
        jdbc.update("INSERT INTO device_incompatible_record"
                        + " (device_id, hardware_model, release_id, to_version, matrix_version)"
                        + " VALUES (?, ?, ?, ?, ?)",
                deviceId, hardwareModel, releaseId, toVersion, matrixVersion);
    }

    public List<IncompatibleRecordView> findByDevice(String deviceId) {
        return jdbc.query("SELECT id, device_id, hardware_model, release_id, to_version, matrix_version"
                        + " FROM device_incompatible_record WHERE device_id = ? ORDER BY id",
                (rs, n) -> new IncompatibleRecordView(rs.getLong("id"), rs.getString("device_id"),
                        rs.getString("hardware_model"), rs.getLong("release_id"),
                        rs.getString("to_version"), rs.getInt("matrix_version")),
                deviceId);
    }

    /**
     * 按发布单与硬件型号统计拦截次数。
     */
    public long countByReleaseAndHardwareModel(long releaseId, String hardwareModel) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_incompatible_record WHERE release_id = ? AND hardware_model = ?",
                Long.class, releaseId, hardwareModel);
        return count == null ? 0 : count;
    }

    /**
     * 按发布单分组统计每个硬件型号的拦截次数。
     */
    public List<IncompatibleCount> countByReleaseGroupByModel(long releaseId) {
        return jdbc.query("SELECT hardware_model, COUNT(*) AS cnt FROM device_incompatible_record"
                        + " WHERE release_id = ? GROUP BY hardware_model ORDER BY hardware_model",
                (rs, n) -> new IncompatibleCount(rs.getString("hardware_model"), rs.getLong("cnt")),
                releaseId);
    }

    public record IncompatibleCount(String hardwareModel, long count) {
    }
}
