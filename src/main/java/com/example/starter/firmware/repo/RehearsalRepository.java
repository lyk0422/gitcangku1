package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RehearsalDevice;
import com.example.starter.firmware.domain.RehearsalStatus;
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
 * 回滚演练数据访问。
 * 开始时写入 RUNNING 演练及规范设备行（结果为 NULL）；结果提交在调用方事务内
 * 先锁定演练行（SELECT ... FOR UPDATE）再批量落每台结果，历史只增不改。
 * active_key 唯一索引保证同一 型号|目标版本|回退版本 至多一场进行中演练，终结后置 NULL。
 */
@Repository
public class RehearsalRepository {

    private static final RowMapper<RollbackRehearsal> REHEARSAL_MAPPER = (rs, rowNum) ->
            new RollbackRehearsal(rs.getLong("id"), rs.getString("operator"), rs.getString("model"),
                    rs.getString("target_version"), rs.getString("rollback_version"),
                    RehearsalStatus.valueOf(rs.getString("status")),
                    rs.getInt("device_count"), rs.getInt("success_count"), rs.getInt("failed_count"),
                    rs.getString("started_at_utc"), rs.getString("finished_at_utc"),
                    rs.getString("gate_denied_reason"));

    private static final RowMapper<RehearsalDevice> DEVICE_MAPPER = (rs, rowNum) -> {
        String result = rs.getString("result");
        return new RehearsalDevice(rs.getLong("id"), rs.getLong("rehearsal_id"),
                rs.getString("device_id"), rs.getInt("ordinal"),
                result == null ? null : ReceiptResult.valueOf(result),
                rs.getString("failure_reason"));
    };

    private static final String REHEARSAL_COLUMNS = "id, operator, model, target_version, rollback_version,"
            + " status, device_count, success_count, failed_count, started_at_utc, finished_at_utc,"
            + " gate_denied_reason";

    private static final String DEVICE_COLUMNS = "id, rehearsal_id, device_id, ordinal, result, failure_reason";

    private final JdbcTemplate jdbc;

    public RehearsalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String operator, String model, String targetVersion, String rollbackVersion,
                       int deviceCount, String startedAtUtc, String activeKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollback_rehearsal (operator, model, target_version, rollback_version,"
                            + " status, device_count, started_at_utc, active_key)"
                            + " VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, operator);
            ps.setString(2, model);
            ps.setString(3, targetVersion);
            ps.setString(4, rollbackVersion);
            ps.setInt(5, deviceCount);
            ps.setString(6, startedAtUtc);
            ps.setString(7, activeKey);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertDevice(long rehearsalId, String deviceId, int ordinal) {
        jdbc.update("INSERT INTO rollback_rehearsal_device (rehearsal_id, device_id, ordinal)"
                + " VALUES (?, ?, ?)", rehearsalId, deviceId, ordinal);
    }

    public Optional<RollbackRehearsal> findById(long id) {
        return jdbc.query("SELECT " + REHEARSAL_COLUMNS + " FROM rollback_rehearsal WHERE id = ?",
                REHEARSAL_MAPPER, id).stream().findFirst();
    }

    public Optional<RollbackRehearsal> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + REHEARSAL_COLUMNS + " FROM rollback_rehearsal WHERE id = ? FOR UPDATE",
                REHEARSAL_MAPPER, id).stream().findFirst();
    }

    /**
     * 结果提交：终结演练（PASSED/FAILED）、写实际成功/失败数、结束时刻与未授权原因，并释放 active_key。
     */
    public void complete(long id, RehearsalStatus status, int successCount, int failedCount,
                         String finishedAtUtc, String gateDeniedReason) {
        jdbc.update("UPDATE rollback_rehearsal SET status = ?, success_count = ?, failed_count = ?,"
                        + " finished_at_utc = ?, gate_denied_reason = ?, active_key = NULL WHERE id = ?",
                status.name(), successCount, failedCount, finishedAtUtc, gateDeniedReason, id);
    }

    public void completeDevice(long deviceRowId, ReceiptResult result, String failureReason) {
        jdbc.update("UPDATE rollback_rehearsal_device SET result = ?, failure_reason = ? WHERE id = ?",
                result.name(), failureReason, deviceRowId);
    }

    public List<RehearsalDevice> findDevices(long rehearsalId) {
        return jdbc.query("SELECT " + DEVICE_COLUMNS + " FROM rollback_rehearsal_device"
                + " WHERE rehearsal_id = ? ORDER BY ordinal", DEVICE_MAPPER, rehearsalId);
    }

    public List<RehearsalDevice> findDevicesForUpdate(long rehearsalId) {
        return jdbc.query("SELECT " + DEVICE_COLUMNS + " FROM rollback_rehearsal_device"
                + " WHERE rehearsal_id = ? ORDER BY ordinal FOR UPDATE", DEVICE_MAPPER, rehearsalId);
    }

    /**
     * 历史查询：型号必填，目标/回退版本可选过滤；按演练ID稳定排序。
     */
    public List<RollbackRehearsal> findByFilters(String model, String targetVersion, String rollbackVersion) {
        StringBuilder sql = new StringBuilder("SELECT ").append(REHEARSAL_COLUMNS)
                .append(" FROM rollback_rehearsal WHERE model = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(model));
        if (targetVersion != null) {
            sql.append(" AND target_version = ?");
            args.add(targetVersion);
        }
        if (rollbackVersion != null) {
            sql.append(" AND rollback_version = ?");
            args.add(rollbackVersion);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), REHEARSAL_MAPPER, args.toArray());
    }

    /**
     * 规范设备集合中已有针对目标版本正式投放任务的设备数（关联正式发布单与任务）。
     */
    public long countFormalTasks(String model, String targetVersion, List<String> deviceIds) {
        String placeholders = String.join(",", deviceIds.stream().map(d -> "?").toList());
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task t JOIN release_order r ON t.release_id = r.id"
                        + " WHERE r.model = ? AND r.to_version = ? AND t.device_id IN (" + placeholders + ")",
                Long.class,
                java.util.stream.Stream.concat(java.util.stream.Stream.of(model, targetVersion),
                        deviceIds.stream()).toArray());
        return count == null ? 0 : count;
    }
}
