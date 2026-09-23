package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CommandStatus;
import com.example.starter.firmware.domain.DispatchCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 下发指令数据访问。同活动同设备至多一条未决指令，由 uk_command_pending_device 唯一约束保证。
 */
@Repository
public class DispatchCommandRepository {

    private static final RowMapper<DispatchCommand> MAPPER = (rs, rowNum) -> new DispatchCommand(
            rs.getLong("id"), rs.getLong("campaign_id"), rs.getString("device_id"),
            rs.getLong("cohort_id"), rs.getInt("generation"),
            CommandStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS = "id, campaign_id, device_id, cohort_id, generation, status";

    private final JdbcTemplate jdbc;

    public DispatchCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 签发未决指令；pending_device 取值 device_id，配合唯一约束保证同设备至多一条未决指令。
     */
    public long insertPending(long campaignId, String deviceId, long cohortId, int generation) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispatch_command (campaign_id, device_id, cohort_id, generation,"
                            + " status, pending_device) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                    new String[]{"id"});
            ps.setLong(1, campaignId);
            ps.setString(2, deviceId);
            ps.setLong(3, cohortId);
            ps.setInt(4, generation);
            ps.setString(5, deviceId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<DispatchCommand> findPending(long campaignId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dispatch_command"
                        + " WHERE campaign_id = ? AND device_id = ? AND status = 'PENDING'",
                MAPPER, campaignId, deviceId).stream().findFirst();
    }

    /**
     * 按代次查找指令，用于迟到回执定位其原队列。
     */
    public Optional<DispatchCommand> findByGeneration(long campaignId, String deviceId, int generation) {
        return jdbc.query("SELECT " + COLUMNS + " FROM dispatch_command"
                        + " WHERE campaign_id = ? AND device_id = ? AND generation = ?"
                        + " ORDER BY id DESC",
                MAPPER, campaignId, deviceId, generation).stream().findFirst();
    }

    /**
     * 迁移废弃未决指令：仅当仍为 PENDING 时生效，返回影响行数。
     */
    public int supersedeIfPending(long commandId) {
        return jdbc.update("UPDATE dispatch_command SET status = 'SUPERSEDED', pending_device = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PENDING'", commandId);
    }

    /**
     * 回执结算未决指令：仅当仍为 PENDING 时生效，返回影响行数。
     */
    public int settleIfPending(long campaignId, String deviceId, int generation) {
        return jdbc.update("UPDATE dispatch_command SET status = 'SETTLED', pending_device = NULL,"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE campaign_id = ? AND device_id = ? AND generation = ?"
                        + " AND status = 'PENDING'",
                campaignId, deviceId, generation);
    }
}
