package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.Cohort;
import com.example.starter.firmware.domain.CohortStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 投放队列数据访问。队列规模与轮次统计只在持有活动行锁的事务内增减。
 */
@Repository
public class CohortRepository {

    private static final RowMapper<Cohort> MAPPER = (rs, rowNum) -> new Cohort(
            rs.getLong("id"), rs.getLong("campaign_id"), rs.getString("code"),
            rs.getString("firmware_version"), rs.getString("region"),
            rs.getInt("region_quota"), rs.getInt("device_cap"), rs.getInt("gray_percent"),
            rs.getInt("device_count"), CohortStatus.valueOf(rs.getString("status")),
            rs.getInt("success_count"), rs.getInt("monitor_round"),
            rs.getInt("round_success"), rs.getInt("round_failed"),
            rs.getInt("sample_floor"), rs.getInt("failure_threshold_percent"));

    private static final String COLUMNS = "id, campaign_id, code, firmware_version, region,"
            + " region_quota, device_cap, gray_percent, device_count, status, success_count,"
            + " monitor_round, round_success, round_failed, sample_floor, failure_threshold_percent";

    private final JdbcTemplate jdbc;

    public CohortRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long campaignId, String code, String firmwareVersion, String region,
                       int regionQuota, int deviceCap, int grayPercent,
                       int sampleFloor, int failureThresholdPercent) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cohort (campaign_id, code, firmware_version, region, region_quota,"
                            + " device_cap, gray_percent, device_count, status, success_count,"
                            + " monitor_round, round_success, round_failed, sample_floor,"
                            + " failure_threshold_percent)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', 0, 1, 0, 0, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, campaignId);
            ps.setString(2, code);
            ps.setString(3, firmwareVersion);
            ps.setString(4, region);
            ps.setInt(5, regionQuota);
            ps.setInt(6, deviceCap);
            ps.setInt(7, grayPercent);
            ps.setInt(8, sampleFloor);
            ps.setInt(9, failureThresholdPercent);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Cohort> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cohort WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按活动与队列ID查找；用于校验目标队列确属本活动。
     */
    public Optional<Cohort> findByCampaignAndId(long campaignId, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cohort WHERE campaign_id = ? AND id = ?",
                MAPPER, campaignId, id).stream().findFirst();
    }

    public List<Cohort> findByCampaign(long campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM cohort WHERE campaign_id = ? ORDER BY id",
                MAPPER, campaignId);
    }

    /**
     * 队列规模调整（正数加、负数减），调用方必须已持有活动行锁。
     */
    public void adjustDeviceCount(long cohortId, int delta) {
        jdbc.update("UPDATE cohort SET device_count = device_count + ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", delta, cohortId);
    }

    /**
     * 回执结算：按结果累计成功数与当前监控轮次统计，调用方必须已持有活动行锁。
     */
    public void incrementSettledStats(long cohortId, ReceiptResult result) {
        if (result == ReceiptResult.SUCCESS) {
            jdbc.update("UPDATE cohort SET success_count = success_count + 1,"
                    + " round_success = round_success + 1, updated_at = CURRENT_TIMESTAMP"
                    + " WHERE id = ?", cohortId);
        } else {
            jdbc.update("UPDATE cohort SET round_failed = round_failed + 1,"
                    + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", cohortId);
        }
    }

    /**
     * 仅当仍为 ACTIVE 时转为 PAUSED，返回影响行数；调用方已持有活动行锁。
     */
    public int pauseIfActive(long cohortId) {
        return jdbc.update("UPDATE cohort SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'ACTIVE'", cohortId);
    }

    /**
     * 人工恢复：仅当仍为 PAUSED 时生效；开启新监控轮次并清零轮次统计。
     */
    public int resumeIfPaused(long cohortId) {
        return jdbc.update("UPDATE cohort SET status = 'ACTIVE',"
                + " monitor_round = monitor_round + 1, round_success = 0, round_failed = 0,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PAUSED'", cohortId);
    }
}
