package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.StagingReservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务暂存空间预留数据访问。uk_staging_device 唯一约束保证同设备至多一项活跃暂存；
 * 预留行随任务首次终结或发布取消在同一事务内删除。
 */
@Repository
public class StagingReservationRepository {

    private static final RowMapper<StagingReservation> MAPPER = (rs, rowNum) -> new StagingReservation(
            rs.getLong("id"), rs.getLong("task_id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getLong("old_image_bytes"), rs.getLong("package_bytes"), rs.getLong("new_image_bytes"),
            rs.getLong("peak_bytes"));

    private static final String COLUMNS = "id, task_id, release_id, device_id, old_image_bytes,"
            + " package_bytes, new_image_bytes, peak_bytes";

    private final JdbcTemplate jdbc;

    public StagingReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, long releaseId, String deviceId, long oldImageBytes,
                       long packageBytes, long newImageBytes) {
        jdbc.update("INSERT INTO staging_reservation (task_id, release_id, device_id, old_image_bytes,"
                        + " package_bytes, new_image_bytes, peak_bytes) VALUES (?, ?, ?, ?, ?, ?, ?)",
                taskId, releaseId, deviceId, oldImageBytes, packageBytes, newImageBytes,
                oldImageBytes + packageBytes + newImageBytes);
    }

    public Optional<StagingReservation> findByTaskId(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM staging_reservation WHERE task_id = ?",
                MAPPER, taskId).stream().findFirst();
    }

    public Optional<StagingReservation> findByDeviceId(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM staging_reservation WHERE device_id = ?",
                MAPPER, deviceId).stream().findFirst();
    }

    /**
     * 按设备ID升序列出发布单的全部活跃预留，供取消时按一致顺序逐台锁定设备并释放。
     */
    public List<StagingReservation> findByReleaseOrderByDevice(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM staging_reservation WHERE release_id = ?"
                + " ORDER BY device_id", MAPPER, releaseId);
    }

    public int deleteByTaskId(long taskId) {
        return jdbc.update("DELETE FROM staging_reservation WHERE task_id = ?", taskId);
    }

    public long countByDeviceId(String deviceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation WHERE device_id = ?",
                Long.class, deviceId);
        return count == null ? 0 : count;
    }
}
