package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.IncompatibleRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备不兼容拦截记录数据访问。唯一约束 uk_incompat_release_device 保证同设备同发布单只记首次。
 */
@Repository
public class IncompatibleRecordRepository {

    private static final RowMapper<IncompatibleRecord> MAPPER = (rs, rowNum) -> new IncompatibleRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getString("hardware_model"), rs.getString("firmware_version"),
            rs.getInt("matrix_version"), rs.getString("blocked_at_utc"));

    private static final String COLUMNS = "id, release_id, device_id, hardware_model, firmware_version,"
            + " matrix_version, blocked_at_utc";

    private final JdbcTemplate jdbc;

    public IncompatibleRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录首次拦截；同设备同发布单已存在时不重复写入，返回是否新插入。
     * 调用方持有发布单行锁，检查与写入之间无并发竞态。
     */
    public boolean insertIfAbsent(long releaseId, String deviceId, String hardwareModel,
                                  String firmwareVersion, int matrixVersion, String blockedAtUtc) {
        Long existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incompatible_record WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        if (existing != null && existing > 0) {
            return false;
        }
        jdbc.update("INSERT INTO incompatible_record"
                        + " (release_id, device_id, hardware_model, firmware_version, matrix_version, blocked_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, deviceId, hardwareModel, firmwareVersion, matrixVersion, blockedAtUtc);
        return true;
    }

    public List<IncompatibleRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM incompatible_record WHERE release_id = ? ORDER BY id",
                MAPPER, releaseId);
    }

    /**
     * 按硬件型号分组的拦截设备数。
     */
    public List<ModelCount> countByModel(long releaseId) {
        return jdbc.query("SELECT hardware_model, COUNT(*) AS cnt FROM incompatible_record"
                        + " WHERE release_id = ? GROUP BY hardware_model",
                (rs, rowNum) -> new ModelCount(rs.getString("hardware_model"), rs.getLong("cnt")),
                releaseId);
    }

    /**
     * 硬件型号 + 数量的统计行。
     */
    public record ModelCount(String hardwareModel, long count) {
    }
}
