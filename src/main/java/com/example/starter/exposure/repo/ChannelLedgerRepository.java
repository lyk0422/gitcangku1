package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 渠道某 UTC 日用量账目数据访问。used_total 为当前占用数（RESERVED 与 CONFIRMED 合计），
 * confirmed 为已确认数。加锁/增减方法必须在事务内调用，依赖行锁与 CHECK 约束
 * 保证并发下同一渠道多个公告共享日额度不超卖、不变负。
 */
@Repository
public class ChannelLedgerRepository {

    /** 渠道某日用量视图。 */
    public record ChannelUsage(int usedTotal, int confirmed) {
    }

    private final JdbcTemplate jdbc;

    public ChannelLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保渠道当日账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String channelKey, LocalDate utcDate) {
        jdbc.update("INSERT INTO channel_daily_ledger (channel_key, utc_date, used_total, confirmed) "
                        + "SELECT ?, ?, 0, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM channel_daily_ledger WHERE channel_key = ? AND utc_date = ?)",
                channelKey, java.sql.Date.valueOf(utcDate),
                channelKey, java.sql.Date.valueOf(utcDate));
    }

    /** 行锁读取渠道当日用量；账目行不存在视为 0。 */
    public ChannelUsage lockUsage(String channelKey, LocalDate utcDate) {
        ChannelUsage value = jdbc.query("SELECT used_total, confirmed FROM channel_daily_ledger "
                        + "WHERE channel_key = ? AND utc_date = ? FOR UPDATE",
                rs -> rs.next() ? new ChannelUsage(rs.getInt(1), rs.getInt(2)) : null,
                channelKey, java.sql.Date.valueOf(utcDate));
        return value == null ? new ChannelUsage(0, 0) : value;
    }

    /** 读取渠道当日用量（无锁）；账目行不存在视为 0。 */
    public ChannelUsage getUsage(String channelKey, LocalDate utcDate) {
        ChannelUsage value = jdbc.query("SELECT used_total, confirmed FROM channel_daily_ledger "
                        + "WHERE channel_key = ? AND utc_date = ?",
                rs -> rs.next() ? new ChannelUsage(rs.getInt(1), rs.getInt(2)) : null,
                channelKey, java.sql.Date.valueOf(utcDate));
        return value == null ? new ChannelUsage(0, 0) : value;
    }

    /** 占用一次渠道当日额度（+1）。调用方须已持行锁并完成容量校验。 */
    public void addUsed(String channelKey, LocalDate utcDate) {
        int rows = jdbc.update("UPDATE channel_daily_ledger SET used_total = used_total + 1 "
                        + "WHERE channel_key = ? AND utc_date = ?",
                channelKey, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("channel ledger row missing for " + channelKey + " " + utcDate);
        }
    }

    /** 确认结算：已确认数 +1（占用数不变，预占时已计入）。 */
    public void addConfirmed(String channelKey, LocalDate utcDate) {
        int rows = jdbc.update("UPDATE channel_daily_ledger SET confirmed = confirmed + 1 "
                        + "WHERE channel_key = ? AND utc_date = ?",
                channelKey, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("channel ledger row missing for " + channelKey + " " + utcDate);
        }
    }

    /** 释放一次渠道当日额度（-1）；CHECK 约束保证不变负。仅用于取消/过期 RESERVED 预占。 */
    public void releaseUsed(String channelKey, LocalDate utcDate) {
        int rows = jdbc.update("UPDATE channel_daily_ledger SET used_total = used_total - 1 "
                        + "WHERE channel_key = ? AND utc_date = ?",
                channelKey, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("channel ledger row missing for " + channelKey + " " + utcDate);
        }
    }
}
