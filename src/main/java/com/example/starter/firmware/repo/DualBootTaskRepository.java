package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DualBootTask;
import com.example.starter.firmware.domain.DualBootTaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 双分区试启动任务数据访问。open_key 唯一约束保证同设备同发布单至多一个未终结任务；
 * 终结或取消时 open_key 置 NULL，回滚后重试拉取产生新任务ID（新行）。
 */
@Repository
public class DualBootTaskRepository {

    private static final RowMapper<DualBootTask> MAPPER = (rs, rowNum) -> new DualBootTask(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
            DualBootTaskStatus.valueOf(rs.getString("status")),
            rs.getString("target_partition"), rs.getString("target_version"),
            rs.getString("trial_started_at_utc"), rs.getString("trial_deadline_utc"));

    private static final String COLUMNS = "id, release_id, device_id, status, target_partition,"
            + " target_version, trial_started_at_utc, trial_deadline_utc";

    private final JdbcTemplate jdbc;

    public DualBootTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String openKey(long releaseId, String deviceId) {
        return releaseId + ":" + deviceId;
    }

    public long insert(long releaseId, String deviceId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dual_boot_task (release_id, device_id, status, open_key)"
                            + " VALUES (?, ?, 'PENDING', ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setString(3, openKey(releaseId, deviceId));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<DualBootTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_boot_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<DualBootTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_boot_task WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查找同设备同发布单的未终结任务（PENDING 或 TRIAL），至多一条。
     */
    public Optional<DualBootTask> findOpenByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_boot_task WHERE open_key = ?",
                MAPPER, openKey(releaseId, deviceId)).stream().findFirst();
    }

    public List<DualBootTask> findByRelease(long releaseId, DualBootTaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM dual_boot_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_boot_task WHERE release_id = ? AND status = ?"
                + " ORDER BY id", MAPPER, releaseId, statusFilter.name());
    }

    /**
     * 查找发布单下全部 TRIAL 任务（到期判断在 Java 侧按服务端时钟比较）。
     */
    public List<DualBootTask> findTrialsByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dual_boot_task"
                + " WHERE release_id = ? AND status = 'TRIAL' ORDER BY id", MAPPER, releaseId);
    }

    /**
     * 进入 TRIAL：记录目标分区、目标版本与服务端试启动时刻、截止时刻；仅 PENDING 可迁移。
     */
    public int startTrial(long id, String targetPartition, String targetVersion,
                          String startedAtUtc, String deadlineUtc) {
        return jdbc.update("UPDATE dual_boot_task SET status = 'TRIAL', target_partition = ?,"
                        + " target_version = ?, trial_started_at_utc = ?, trial_deadline_utc = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PENDING'",
                targetPartition, targetVersion, startedAtUtc, deadlineUtc, id);
    }

    /**
     * 终结任务（COMMITTED 或 ROLLED_BACK）：仅 PENDING/TRIAL 可迁移，返回影响行数用于只终结一次。
     */
    public int terminate(long id, DualBootTaskStatus terminal) {
        return jdbc.update("UPDATE dual_boot_task SET status = ?, open_key = NULL,"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status IN ('PENDING', 'TRIAL')",
                terminal.name(), id);
    }

    /**
     * 取消发布单下全部未终结任务。
     */
    public int cancelOpenByRelease(long releaseId) {
        return jdbc.update("UPDATE dual_boot_task SET status = 'CANCELLED', open_key = NULL,"
                + " updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status IN ('PENDING', 'TRIAL')", releaseId);
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dual_boot_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }
}