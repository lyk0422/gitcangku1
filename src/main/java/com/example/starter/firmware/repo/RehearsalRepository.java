package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.RehearsalDevice;
import com.example.starter.firmware.domain.RehearsalStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RollbackRehearsal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 回滚演练数据访问。RUNNING 演练由 active_key 唯一约束保证同型号同目标版本至多一个；
 * 结果整批提交，历史行只增不改（完成仅更新演练头一次，设备行写入后不再更新）。
 */
@Repository
public class RehearsalRepository {

    private static final RowMapper<RollbackRehearsal> REHEARSAL_MAPPER = (rs, rowNum) -> {
        Long durationSeconds = rs.getObject("duration_seconds", Long.class);
        return new RollbackRehearsal(
            rs.getLong("id"), rs.getString("model"), rs.getString("target_version"),
            rs.getString("rollback_version"), RehearsalStatus.valueOf(rs.getString("status")),
            rs.getString("operator"), rs.getInt("device_count"), rs.getInt("success_count"),
            rs.getInt("failed_count"), rs.getString("started_at_utc"),
            rs.getString("ended_at_utc"), durationSeconds);
    };

    private static final RowMapper<RehearsalDevice> DEVICE_MAPPER = (rs, rowNum) -> {
        String result = rs.getString("result");
        String recordedAt = rs.getString("recorded_at_utc");
        return new RehearsalDevice(rs.getLong("id"), rs.getLong("rehearsal_id"), rs.getString("device_id"),
                rs.getInt("ordinal"), result == null ? null : ReceiptResult.valueOf(result), recordedAt);
    };

    private static final String REHEARSAL_COLUMNS = "id, model, target_version, rollback_version, status,"
            + " operator, device_count, success_count, failed_count, started_at_utc, ended_at_utc, duration_seconds";

    private static final String DEVICE_COLUMNS = "id, rehearsal_id, device_id, ordinal, result, recorded_at_utc";

    private final JdbcTemplate jdbc;

    public RehearsalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String targetVersion, String rollbackVersion, String operator,
                       int deviceCount, String startedAtUtc, String activeKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollback_rehearsal (model, target_version, rollback_version, status, operator,"
                            + " device_count, success_count, failed_count, started_at_utc, ended_at_utc,"
                            + " duration_seconds, active_key)"
                            + " VALUES (?, ?, ?, 'RUNNING', ?, ?, 0, 0, ?, NULL, NULL, ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, targetVersion);
            ps.setString(3, rollbackVersion);
            ps.setString(4, operator);
            ps.setInt(5, deviceCount);
            ps.setString(6, startedAtUtc);
            ps.setString(7, activeKey);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertDevice(long rehearsalId, String deviceId, int ordinal) {
        jdbc.update("INSERT INTO rollback_rehearsal_device (rehearsal_id, device_id, ordinal, result, recorded_at_utc)"
                + " VALUES (?, ?, ?, NULL, NULL)", rehearsalId, deviceId, ordinal);
    }

    public Optional<RollbackRehearsal> findById(long id) {
        return jdbc.query("SELECT " + REHEARSAL_COLUMNS + " FROM rollback_rehearsal WHERE id = ?",
                REHEARSAL_MAPPER, id).stream().findFirst();
    }

    public Optional<RollbackRehearsal> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + REHEARSAL_COLUMNS + " FROM rollback_rehearsal WHERE id = ? FOR UPDATE",
                REHEARSAL_MAPPER, id).stream().findFirst();
    }

    public List<RollbackRehearsal> findByModelVersion(String model, String targetVersion) {
        return jdbc.query("SELECT " + REHEARSAL_COLUMNS
                        + " FROM rollback_rehearsal WHERE model = ? AND target_version = ? ORDER BY id",
                REHEARSAL_MAPPER, model, targetVersion);
    }

    public List<RollbackRehearsal> findAll() {
        return jdbc.query("SELECT " + REHEARSAL_COLUMNS + " FROM rollback_rehearsal ORDER BY id",
                REHEARSAL_MAPPER);
    }

    public List<RehearsalDevice> findDevices(long rehearsalId) {
        return jdbc.query("SELECT " + DEVICE_COLUMNS
                + " FROM rollback_rehearsal_device WHERE rehearsal_id = ? ORDER BY ordinal, id",
                DEVICE_MAPPER, rehearsalId);
    }

    /**
     * 整批结果提交：逐台写入 SUCCESS/FAILED 与记录时刻。调用方已完成完整集合校验且持有演练行锁。
     */
    public void recordDeviceResult(long rehearsalId, String deviceId, ReceiptResult result, String recordedAtUtc) {
        jdbc.update("UPDATE rollback_rehearsal_device SET result = ?, recorded_at_utc = ?"
                + " WHERE rehearsal_id = ? AND device_id = ?", result.name(), recordedAtUtc, rehearsalId, deviceId);
    }

    /**
     * 全部成功：演练置 SUCCEEDED 并释放 active_key，同一事务内随后写入门禁。
     */
    public void markSucceeded(long id, int successCount, int failedCount, String endedAtUtc, long durationSeconds) {
        jdbc.update("UPDATE rollback_rehearsal SET status = 'SUCCEEDED', success_count = ?, failed_count = ?,"
                + " ended_at_utc = ?, duration_seconds = ?, active_key = NULL WHERE id = ?",
                successCount, failedCount, endedAtUtc, durationSeconds, id);
    }
}
