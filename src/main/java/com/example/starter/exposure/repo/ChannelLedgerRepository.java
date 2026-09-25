package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 渠道某 UTC 日总量账目数据访问。所有加锁/增减方法必须在事务内调用，
 * 依赖 SELECT ... FOR UPDATE 行锁与 CHECK (used &gt;= 0) 约束保证并发下不超卖、不变负。
 */
@Repository
public class ChannelLedgerRepository {

    private final JdbcTemplate jdbc;

    public ChannelLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 渠道当日已占用额度（RESERVED 与 CONFIRMED 合计）；账目行不存在视为 0。 */
    public int getUsed(String channelKey, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT used FROM channel_daily_ledger "
                        + "WHERE channel_key = ? AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                channelKey, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /** 确保渠道当日账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String channelKey, LocalDate utcDate) {
        jdbc.update("INSERT INTO channel_daily_ledger (channel_key, utc_date, used) "
                        + "SELECT ?, ?, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM channel_daily_ledger WHERE channel_key = ? AND utc_date = ?)",
                channelKey, java.sql.Date.valueOf(utcDate),
                channelKey, java.sql.Date.valueOf(utcDate));
    }

    /** 行锁读取渠道当日已占用额度；账目行不存在视为 0。 */
    public int lockUsed(String channelKey, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT used FROM channel_daily_ledger "
                        + "WHERE channel_key = ? AND utc_date = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                channelKey, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /** 占用一个渠道名额（+1）。调用方须已持行锁并完成容量校验。 */
    public void add(String channelKey, LocalDate utcDate, int delta) {
        int rows = jdbc.update("UPDATE channel_daily_ledger SET used = used + ? "
                        + "WHERE channel_key = ? AND utc_date = ?",
                delta, channelKey, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("channel ledger row missing for " + channelKey + " " + utcDate);
        }
    }

    /** 释放一个渠道名额（-1）；CHECK 约束保证不变负，CAS 保证不重复释放。 */
    public void release(String channelKey, LocalDate utcDate) {
        int rows = jdbc.update("UPDATE channel_daily_ledger SET used = used - 1 "
                        + "WHERE channel_key = ? AND utc_date = ?",
                channelKey, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("channel ledger row missing for " + channelKey + " " + utcDate);
        }
    }
}
