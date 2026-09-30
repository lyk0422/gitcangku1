package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.DeviceFailureRound;
import com.example.starter.firmware.domain.DeviceFailureRoundStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 设备-版本连续失败轮次数据访问。轮次按 (device_id, version, round_no) 唯一，历史只增不删；
 * uk_failure_round_counting 从数据库层兜底每个设备+版本至多一条 COUNTING 轮次。
 * 所有调用方须先持有设备行锁，串行化同设备同版本的失败回执、拉取与解除。
 */
@Repository
public class DeviceFailureRoundRepository {

    private static final RowMapper<DeviceFailureRound> MAPPER = (rs, rowNum) -> new DeviceFailureRound(
            rs.getLong("id"), rs.getString("device_id"), rs.getString("version"),
            rs.getInt("round_no"), rs.getInt("consecutive_failures"),
            DeviceFailureRoundStatus.valueOf(rs.getString("status")),
            rs.getLong("first_task_id"), rs.getString("first_reason"),
            rs.getObject("second_task_id", Long.class),
            rs.getString("second_reason"), rs.getString("confirmed_by"),
            rs.getString("quarantined_at_utc"), rs.getString("released_by"),
            rs.getString("release_reason"), rs.getString("released_at_utc"));

    private static final String COLUMNS = "id, device_id, version, round_no, consecutive_failures, status,"
            + " first_task_id, first_reason, second_task_id, second_reason, confirmed_by, quarantined_at_utc,"
            + " released_by, release_reason, released_at_utc";

    private final JdbcTemplate jdbc;

    public DeviceFailureRoundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入某设备某版本的第一次失败，开启状态为 COUNTING 的新轮次；round_no 由调用方按 MAX+1 给出。
     */
    public long insertFirst(String deviceId, String version, int roundNo, long taskId, String reason) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO device_failure_round"
                            + " (device_id, version, round_no, consecutive_failures, status,"
                            + " first_task_id, first_reason)"
                            + " VALUES (?, ?, ?, 1, 'COUNTING', ?, ?)",
                    new String[]{"id"});
            ps.setString(1, deviceId);
            ps.setString(2, version);
            ps.setInt(3, roundNo);
            ps.setLong(4, taskId);
            ps.setString(5, reason);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 锁定并读取设备+版本最新一轮记录（按 round_no 倒序取第一条）。
     */
    public Optional<DeviceFailureRound> findLatestForUpdate(String deviceId, String version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_failure_round"
                        + " WHERE device_id = ? AND version = ? ORDER BY round_no DESC LIMIT 1 FOR UPDATE",
                MAPPER, deviceId, version).stream().findFirst();
    }

    public Optional<DeviceFailureRound> findLatest(String deviceId, String version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_failure_round"
                        + " WHERE device_id = ? AND version = ? ORDER BY round_no DESC LIMIT 1",
                MAPPER, deviceId, version).stream().findFirst();
    }

    /**
     * COUNTING 轮次记录第二次失败并转 QUARANTINED，固化两次任务、原因、确认人与时刻。
     * 仅当状态仍为 COUNTING 时生效，返回影响行数。
     */
    public int markQuarantined(long id, long secondTaskId, String secondReason,
                               String confirmedBy, String quarantinedAtUtc) {
        return jdbc.update("UPDATE device_failure_round SET consecutive_failures = 2, status = 'QUARANTINED',"
                        + " second_task_id = ?, second_reason = ?, confirmed_by = ?,"
                        + " quarantined_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'COUNTING'",
                secondTaskId, secondReason, confirmedBy, quarantinedAtUtc, id);
    }

    /**
     * 解除隔离：仅 QUARANTINED 可解除，记录审核人（不同于确认人）、原因与时刻，返回影响行数。
     */
    public int markReleased(long id, String releasedBy, String reason, String releasedAtUtc) {
        return jdbc.update("UPDATE device_failure_round SET status = 'RELEASED', released_by = ?,"
                        + " release_reason = ?, released_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'QUARANTINED'",
                releasedBy, reason, releasedAtUtc, id);
    }

    /**
     * 设备成功升级到该版本：COUNTING 轮次自然终结为 RECOVERED，返回影响行数。
     */
    public int markRecoveredIfCounting(long id) {
        return jdbc.update("UPDATE device_failure_round SET status = 'RECOVERED',"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'COUNTING'", id);
    }

    /**
     * 同设备同版本当前最大轮次号，无记录为 0；调用方持有设备行锁。
     */
    public int maxRoundNo(String deviceId, String version) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(round_no), 0) FROM device_failure_round"
                        + " WHERE device_id = ? AND version = ?",
                Integer.class, deviceId, version);
        return value == null ? 0 : value;
    }

    public List<DeviceFailureRound> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM device_failure_round WHERE device_id = ?"
                        + " ORDER BY version, round_no",
                MAPPER, deviceId);
    }
}
