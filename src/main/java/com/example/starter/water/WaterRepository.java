package com.example.starter.water;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 灌区配水数据的 JDBC 持久化入口。所有写路径依赖行级锁（SELECT ... FOR UPDATE）保证并发正确性。
 */
@Repository
public class WaterRepository {

    private final JdbcTemplate jdbc;

    public WaterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记（如需要）并按渠道 ID 加行锁，串行化同渠道窗口创建。 */
    public void lockChannel(String channelId) {
        try {
            jdbc.update("INSERT INTO water_channel (channel_id) VALUES (?)", channelId);
        } catch (DuplicateKeyException ignored) {
            // 渠道已登记，直接进入加锁。
        }
        jdbc.queryForObject("SELECT channel_id FROM water_channel WHERE channel_id = ? FOR UPDATE",
                String.class, channelId);
    }

    /** 判断同渠道是否存在与 [startMs, endMs) 重叠的窗口；首尾相接不算重叠。 */
    public boolean existsOverlappingWindow(String channelId, long startMs, long endMs) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM water_window WHERE channel_id = ? AND start_epoch_ms < ? AND end_epoch_ms > ?",
                Integer.class, channelId, endMs, startMs);
        return count != null && count > 0;
    }

    /** 插入窗口并返回自增 ID。 */
    public long insertWindow(String windowKey, String channelId, long startMs, long endMs,
            BigDecimal plannedVolume, long createdMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO water_window (window_key, channel_id, start_epoch_ms, end_epoch_ms,"
                            + " planned_volume, created_epoch_ms) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, windowKey);
            ps.setString(2, channelId);
            ps.setLong(3, startMs);
            ps.setLong(4, endMs);
            ps.setBigDecimal(5, plannedVolume);
            ps.setLong(6, createdMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按内部 ID 查询窗口，不存在返回 null。 */
    public WaterWindow findWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM water_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 按内部 ID 查询并加行锁，不存在返回 null。 */
    public WaterWindow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM water_window WHERE id = ? FOR UPDATE", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 判断窗口业务键是否已存在。 */
    public boolean existsWindowKey(String windowKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM water_window WHERE window_key = ?", Integer.class, windowKey);
        return count != null && count > 0;
    }

    /** 插入申请并返回自增 ID。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal volume,
            String applicant, String status, long nowMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO water_allocation (allocation_key, window_id, user_id, volume, applicant,"
                            + " status, created_epoch_ms, updated_epoch_ms) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, volume);
            ps.setString(5, applicant);
            ps.setString(6, status);
            ps.setLong(7, nowMs);
            ps.setLong(8, nowMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按业务键查询申请，不存在返回 null。 */
    public WaterAllocation findAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM water_allocation WHERE allocation_key = ?", ALLOCATION_MAPPER, allocationKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 更新申请状态与变更时间。 */
    public void updateAllocationStatus(long id, String status, long updatedMs) {
        jdbc.update("UPDATE water_allocation SET status = ?, updated_epoch_ms = ? WHERE id = ?",
                status, updatedMs, id);
    }

    /** 汇总窗口内全部 APPROVED 申请的水量，无记录时返回 0.000。 */
    public BigDecimal sumApprovedVolume(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume), 0) FROM water_allocation WHERE window_id = ? AND status = 'APPROVED'",
                BigDecimal.class, windowId);
        return sum == null ? new BigDecimal("0.000") : sum;
    }

    /** 按窗口列出全部申请（按 ID 升序）。 */
    public List<WaterAllocation> listAllocationsByWindow(long windowId) {
        return jdbc.query("SELECT * FROM water_allocation WHERE window_id = ? ORDER BY id",
                ALLOCATION_MAPPER, windowId);
    }

    /** 插入限供记录并返回自增 ID。 */
    public long insertRestriction(long windowId, BigDecimal limitVolume, long createdMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO water_restriction (window_id, limit_volume, status, created_epoch_ms,"
                            + " cancelled_epoch_ms) VALUES (?,?,?,?,NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, windowId);
            ps.setBigDecimal(2, limitVolume);
            ps.setString(3, WaterRestriction.STATUS_ACTIVE);
            ps.setLong(4, createdMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 查询窗口当前生效的限供，不存在返回 null。 */
    public WaterRestriction findActiveRestriction(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM water_restriction WHERE window_id = ? AND status = 'ACTIVE'",
                    RESTRICTION_MAPPER, windowId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 将限供记录置为已取消。 */
    public void cancelRestriction(long id, long cancelledMs) {
        jdbc.update("UPDATE water_restriction SET status = 'CANCELLED', cancelled_epoch_ms = ? WHERE id = ?",
                cancelledMs, id);
    }

    /** 按窗口列出全部限供记录（按 ID 升序）。 */
    public List<WaterRestriction> listRestrictionsByWindow(long windowId) {
        return jdbc.query("SELECT * FROM water_restriction WHERE window_id = ? ORDER BY id",
                RESTRICTION_MAPPER, windowId);
    }

    /** 按命令键查询幂等记录，不存在返回 null。 */
    public CommandRecord findCommand(String commandKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM water_command WHERE command_key = ?", COMMAND_MAPPER, commandKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 写入幂等记录；键已存在时抛出 DuplicateKeyException。 */
    public void insertCommand(String commandKey, String operation, String paramsHash,
            int httpStatus, String responseBody, long createdMs) {
        jdbc.update("INSERT INTO water_command (command_key, operation, params_hash, http_status,"
                        + " response_body, created_epoch_ms) VALUES (?,?,?,?,?,?)",
                commandKey, operation, paramsHash, httpStatus, responseBody, createdMs);
    }

    /** 回填命令执行成功后的响应快照（与占位插入同事务）。 */
    public void updateCommandResponse(String commandKey, int httpStatus, String responseBody) {
        jdbc.update("UPDATE water_command SET http_status = ?, response_body = ? WHERE command_key = ?",
                httpStatus, responseBody, commandKey);
    }

    private static final RowMapper<WaterWindow> WINDOW_MAPPER = (rs, rowNum) -> new WaterWindow(
            rs.getLong("id"),
            rs.getString("window_key"),
            rs.getString("channel_id"),
            Instant.ofEpochMilli(rs.getLong("start_epoch_ms")),
            Instant.ofEpochMilli(rs.getLong("end_epoch_ms")),
            rs.getBigDecimal("planned_volume"),
            Instant.ofEpochMilli(rs.getLong("created_epoch_ms")));

    private static final RowMapper<WaterAllocation> ALLOCATION_MAPPER = (rs, rowNum) -> new WaterAllocation(
            rs.getLong("id"),
            rs.getString("allocation_key"),
            rs.getLong("window_id"),
            rs.getString("user_id"),
            rs.getBigDecimal("volume"),
            rs.getString("applicant"),
            rs.getString("status"),
            Instant.ofEpochMilli(rs.getLong("created_epoch_ms")),
            Instant.ofEpochMilli(rs.getLong("updated_epoch_ms")));

    private static final RowMapper<WaterRestriction> RESTRICTION_MAPPER = (rs, rowNum) -> new WaterRestriction(
            rs.getLong("id"),
            rs.getLong("window_id"),
            rs.getBigDecimal("limit_volume"),
            rs.getString("status"),
            Instant.ofEpochMilli(rs.getLong("created_epoch_ms")),
            nullableInstant(rs, "cancelled_epoch_ms"));

    private static final RowMapper<CommandRecord> COMMAND_MAPPER = (rs, rowNum) -> new CommandRecord(
            rs.getString("command_key"),
            rs.getString("operation"),
            rs.getString("params_hash"),
            rs.getInt("http_status"),
            rs.getString("response_body"),
            Instant.ofEpochMilli(rs.getLong("created_epoch_ms")));

    /** 读取可空 epoch 毫秒列，NULL 时返回 null。 */
    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
