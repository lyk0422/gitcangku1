package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceFailureRound;
import com.example.starter.firmware.domain.RoundStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 设备版本连续失败轮次数据访问。
 * 活动轮次（ACTIVE/QUARANTINED）由唯一约束 uk_failure_round_active 保证每设备每版本至多一个，
 * 所有检查与状态流转必须在持有设备行锁的事务内完成，与拉取/回执竞争形成一致提交顺序。
 */
@Repository
public class DeviceFailureRoundRepository {

    private static final RowMapper<DeviceFailureRound> MAPPER = (rs, rowNum) -> new DeviceFailureRound(
            rs.getLong("id"), rs.getString("device_id"), rs.getString("to_version"),
            rs.getInt("round_no"), rs.getInt("consecutive_failed"),
            RoundStatus.valueOf(rs.getString("status")),
            (Long) rs.getObject("first_task_id"), (Long) rs.getObject("second_task_id"),
            rs.getString("first_failure_reason"), rs.getString("second_failure_reason"),
            rs.getString("confirmed_by"), rs.getString("quarantined_at_utc"),
            rs.getString("released_by"), rs.getString("release_reason"), rs.getString("released_at_utc"));

    private static final String COLUMNS = "id, device_id, to_version, round_no, consecutive_failed, status,"
            + " first_task_id, second_task_id, first_failure_reason, second_failure_reason, confirmed_by,"
            + " quarantined_at_utc, released_by, release_reason, released_at_utc";

    private final JdbcTemplate jdbc;

    public DeviceFailureRoundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建第一次失败后的活动轮次（ACTIVE），active_key 由调用方按 deviceId:toVersion 传入。
     */
    public long insert(String deviceId, String toVersion, int roundNo, long firstTaskId,
                       String firstFailureReason, String activeKey) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_failure_round (device_id, to_version, round_no, consecutive_failed, status,"
                            + " first_task_id, first_failure_reason, active_key)"
                            + " VALUES (?, ?, ?, 1, 'ACTIVE', ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, deviceId);
            ps.setString(2, toVersion);
            ps.setInt(3, roundNo);
            ps.setLong(4, firstTaskId);
            ps.setString(5, firstFailureReason);
            ps.setString(6, activeKey);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 第二次失败提交：固化第二次任务与失败原因，ACTIVE 原子转 QUARANTINED 并记录确认人与隔离时刻。
     */
    public int quarantine(long id, long secondTaskId, String secondFailureReason,
                          String confirmedBy, String quarantinedAtUtc) {
        return jdbc.update("UPDATE device_failure_round SET consecutive_failed = 2, status = 'QUARANTINED',"
                        + " second_task_id = ?, second_failure_reason = ?, confirmed_by = ?,"
                        + " quarantined_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                secondTaskId, secondFailureReason, confirmedBy, quarantinedAtUtc, id);
    }

    /**
     * 人工解除：QUARANTINED 原子转 RELEASED，释放活动槽位（active_key 置 NULL），历史失败字段保留。
     */
    public int release(long id, String releasedBy, String releaseReason, String releasedAtUtc) {
        return jdbc.update("UPDATE device_failure_round SET status = 'RELEASED', active_key = NULL,"
                        + " released_by = ?, release_reason = ?, released_at_utc = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'QUARANTINED'",
                releasedBy, releaseReason, releasedAtUtc, id);
    }

    public Optional<DeviceFailureRound> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_failure_round WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询活动轮次（ACTIVE 或 QUARANTINED）；解除后为 RELEASED，不再占用活动槽位。
     */
    public Optional<DeviceFailureRound> findActive(String deviceId, String toVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_failure_round"
                        + " WHERE active_key = ?", MAPPER, activeKey(deviceId, toVersion))
                .stream().findFirst();
    }

    public List<DeviceFailureRound> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_failure_round WHERE device_id = ? ORDER BY id",
                MAPPER, deviceId);
    }

    /**
     * 该设备该版本的下一轮次序号（历史轮次数 + 1）。
     */
    public int nextRoundNo(String deviceId, String toVersion) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_failure_round WHERE device_id = ? AND to_version = ?",
                Long.class, deviceId, toVersion);
        return (count == null ? 0 : count.intValue()) + 1;
    }

    public static String activeKey(String deviceId, String toVersion) {
        return deviceId + ":" + toVersion;
    }
}
