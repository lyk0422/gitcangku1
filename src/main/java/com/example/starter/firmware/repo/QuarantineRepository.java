package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.QuarantineRound;
import com.example.starter.firmware.domain.QuarantineStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 设备隔离轮次数据访问。
 * 活跃轮次（TRACKING/QUARANTINED）由 active_slot 上的唯一约束保证同设备同目标版本至多一条；
 * 解除时先将旧轮置 RELEASED 并把 active_slot 置 NULL，再插入新一轮 TRACKING。
 */
@Repository
public class QuarantineRepository {

    private static final RowMapper<QuarantineRound> MAPPER = (rs, rowNum) -> new QuarantineRound(
            rs.getLong("id"), rs.getString("device_id"), rs.getString("target_version"),
            rs.getInt("round_no"), rs.getInt("consecutive_failures"),
            QuarantineStatus.valueOf(rs.getString("status")),
            rs.getObject("first_task_id", Long.class), rs.getObject("first_release_id", Long.class),
            rs.getString("first_reason"), rs.getString("first_failed_at_utc"),
            rs.getObject("second_task_id", Long.class), rs.getObject("second_release_id", Long.class),
            rs.getString("second_reason"), rs.getString("second_failed_at_utc"),
            rs.getString("confirmed_by"), rs.getString("quarantined_at_utc"),
            rs.getString("released_by"), rs.getString("released_at_utc"),
            rs.getString("release_reason"), rs.getObject("next_round_id", Long.class));

    private static final String COLUMNS = "id, device_id, target_version, round_no, consecutive_failures, status,"
            + " first_task_id, first_release_id, first_reason, first_failed_at_utc,"
            + " second_task_id, second_release_id, second_reason, second_failed_at_utc,"
            + " confirmed_by, quarantined_at_utc, released_by, released_at_utc, release_reason, next_round_id";

    private final JdbcTemplate jdbc;

    public QuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String activeSlot(String deviceId, String targetVersion) {
        return deviceId + "|" + targetVersion;
    }

    public Optional<QuarantineRound> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_round WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<QuarantineRound> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_round WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查同设备同目标版本的活跃轮次（TRACKING 或 QUARANTINED）。
     */
    public Optional<QuarantineRound> findActive(String deviceId, String targetVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_round WHERE active_slot = ?",
                MAPPER, activeSlot(deviceId, targetVersion)).stream().findFirst();
    }

    public Optional<QuarantineRound> findActiveForUpdate(String deviceId, String targetVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_round WHERE active_slot = ? FOR UPDATE",
                MAPPER, activeSlot(deviceId, targetVersion)).stream().findFirst();
    }

    public List<QuarantineRound> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_quarantine_round WHERE device_id = ? ORDER BY id",
                MAPPER, deviceId);
    }

    public int maxRoundNo(String deviceId, String targetVersion) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(round_no), 0) FROM device_quarantine_round"
                        + " WHERE device_id = ? AND target_version = ?",
                Integer.class, deviceId, targetVersion);
        return max == null ? 0 : max;
    }

    /**
     * 首次失败：开启一个 TRACKING 轮次并固化第一次失败，consecutive_failures=1。
     */
    public long insertFirstFailure(String deviceId, String targetVersion, int roundNo,
                                   long firstTaskId, long firstReleaseId, String firstReason,
                                   String firstFailedAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_quarantine_round (device_id, target_version, round_no,"
                            + " consecutive_failures, status, first_task_id, first_release_id, first_reason,"
                            + " first_failed_at_utc, active_slot)"
                            + " VALUES (?, ?, ?, 1, 'TRACKING', ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, deviceId);
            ps.setString(2, targetVersion);
            ps.setInt(3, roundNo);
            ps.setLong(4, firstTaskId);
            ps.setLong(5, firstReleaseId);
            ps.setString(6, firstReason);
            ps.setString(7, firstFailedAtUtc);
            ps.setString(8, activeSlot(deviceId, targetVersion));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 成功回执中断本轮连续失败：仅将连续计数清零，已固化的第一次失败依据作为历史保留；
     * 此后再次失败时由 markFirstFailure 覆盖为新一轮连续失败的依据。
     */
    public int resetTracking(long id) {
        return jdbc.update("UPDATE device_quarantine_round SET consecutive_failures = 0"
                + " WHERE id = ? AND status = 'TRACKING'", id);
    }

    /**
     * 计数清零后再次失败：重新固化第一次失败依据并把计数置1，轮次仍为 TRACKING。
     */
    public int markFirstFailure(long id, long firstTaskId, long firstReleaseId,
                                String firstReason, String firstFailedAtUtc) {
        return jdbc.update("UPDATE device_quarantine_round SET consecutive_failures = 1, first_task_id = ?,"
                + " first_release_id = ?, first_reason = ?, first_failed_at_utc = ?"
                + " WHERE id = ? AND status = 'TRACKING'",
                firstTaskId, firstReleaseId, firstReason, firstFailedAtUtc, id);
    }

    /**
     * 第二次失败：TRACKING -> QUARANTINED，计数置2并固化第二次失败与确认人。
     * 仅当仍为 TRACKING 时生效，返回影响行数；调用方持有设备行锁，并发回执至多一行生效。
     */
    public int quarantine(long id, long secondTaskId, long secondReleaseId, String secondReason,
                          String secondFailedAtUtc, String confirmedBy, String quarantinedAtUtc) {
        return jdbc.update("UPDATE device_quarantine_round SET consecutive_failures = 2, status = 'QUARANTINED',"
                + " second_task_id = ?, second_release_id = ?, second_reason = ?, second_failed_at_utc = ?,"
                + " confirmed_by = ?, quarantined_at_utc = ? WHERE id = ? AND status = 'TRACKING'",
                secondTaskId, secondReleaseId, secondReason, secondFailedAtUtc,
                confirmedBy, quarantinedAtUtc, id);
    }

    /**
     * 解除第一步：QUARANTINED -> RELEASED 并释放 active_slot。返回影响行数。
     */
    public int markReleased(long id, String releasedBy, String releasedAtUtc, String releaseReason) {
        return jdbc.update("UPDATE device_quarantine_round SET status = 'RELEASED', active_slot = NULL,"
                + " released_by = ?, released_at_utc = ?, release_reason = ?"
                + " WHERE id = ? AND status = 'QUARANTINED'",
                releasedBy, releasedAtUtc, releaseReason, id);
    }

    /**
     * 解除第二步：把新开启轮次的ID回填到旧轮次，形成解除记录链。
     */
    public void linkNextRound(long releasedRoundId, long nextRoundId) {
        jdbc.update("UPDATE device_quarantine_round SET next_round_id = ? WHERE id = ?",
                nextRoundId, releasedRoundId);
    }

    /**
     * 解除后开启的新一轮 TRACKING，consecutive_failures=0，历史失败不迁入。
     */
    public long insertNextRound(String deviceId, String targetVersion, int roundNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_quarantine_round (device_id, target_version, round_no,"
                            + " consecutive_failures, status, active_slot) VALUES (?, ?, ?, 0, 'TRACKING', ?)",
                    new String[]{"id"});
            ps.setString(1, deviceId);
            ps.setString(2, targetVersion);
            ps.setInt(3, roundNo);
            ps.setString(4, activeSlot(deviceId, targetVersion));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
