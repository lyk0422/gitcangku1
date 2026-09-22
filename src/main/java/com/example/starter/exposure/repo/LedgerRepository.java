package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 两级额度账目数据访问：公告某 UTC 日总额度账目、访客某 UTC 日额度账目。
 * 所有加锁/增减方法必须在事务内调用，依赖 SELECT ... FOR UPDATE 行锁与
 * CHECK (used_* &gt;= 0) 约束保证并发下不超卖、不变负。
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 公告当日已占用额度；账目行不存在视为 0。 */
    public int getUsedTotal(String campaignId, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT used_total FROM quota_total_ledger "
                + "WHERE campaign_id = ? AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, campaignId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /** 访客当日已占用额度；账目行不存在视为 0。 */
    public int getUsedVisitor(String campaignId, String visitorId, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /** 确保公告当日账目行存在（不存在则以 0 创建）。 */
    public void ensureTotalRow(String campaignId, LocalDate utcDate) {
        jdbc.update("INSERT INTO quota_total_ledger (campaign_id, utc_date, used_total) "
                        + "SELECT ?, ?, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM quota_total_ledger WHERE campaign_id = ? AND utc_date = ?)",
                campaignId, java.sql.Date.valueOf(utcDate),
                campaignId, java.sql.Date.valueOf(utcDate));
    }

    /** 确保访客当日账目行存在（不存在则以 0 创建）。 */
    public void ensureVisitorRow(String campaignId, String visitorId, LocalDate utcDate) {
        jdbc.update("INSERT INTO quota_visitor_ledger (campaign_id, visitor_id, utc_date, used_visitor) "
                        + "SELECT ?, ?, ?, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM quota_visitor_ledger "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?)",
                campaignId, visitorId, java.sql.Date.valueOf(utcDate),
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
    }

    /** 行锁读取公告当日已占用额度；账目行不存在视为 0。 */
    public int lockUsedTotal(String campaignId, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT used_total FROM quota_total_ledger "
                        + "WHERE campaign_id = ? AND utc_date = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, campaignId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /** 行锁读取访客当日已占用额度；账目行不存在视为 0。 */
    public int lockUsedVisitor(String campaignId, String visitorId, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT used_visitor FROM quota_visitor_ledger "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /**
     * 条件占用一次公告当日额度：仅当占用后不超过 cap 时才 +1。
     *
     * @return 是否占用成功；false 表示总额度已满
     */
    public boolean tryIncrementTotal(String campaignId, LocalDate utcDate, int cap) {
        int rows = jdbc.update("UPDATE quota_total_ledger SET used_total = used_total + 1 "
                        + "WHERE campaign_id = ? AND utc_date = ? AND used_total + 1 <= ?",
                campaignId, java.sql.Date.valueOf(utcDate), cap);
        return rows == 1;
    }

    /**
     * 条件占用一次访客当日额度：仅当占用后不超过 cap 时才 +1。
     *
     * @return 是否占用成功；false 表示访客额度已满
     */
    public boolean tryIncrementVisitor(String campaignId, String visitorId, LocalDate utcDate, int cap) {
        int rows = jdbc.update("UPDATE quota_visitor_ledger SET used_visitor = used_visitor + 1 "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ? AND used_visitor + 1 <= ?",
                campaignId, visitorId, java.sql.Date.valueOf(utcDate), cap);
        return rows == 1;
    }

    /**
     * 占用一次公告当日额度（+1）。调用方须已持行锁并完成容量校验。
     */
    public void addTotal(String campaignId, LocalDate utcDate, int delta) {
        int rows = jdbc.update("UPDATE quota_total_ledger SET used_total = used_total + ? "
                        + "WHERE campaign_id = ? AND utc_date = ?",
                delta, campaignId, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("total ledger row missing for " + campaignId + " " + utcDate);
        }
    }

    /**
     * 占用一次访客当日额度（+1）。调用方须已持行锁并完成容量校验。
     */
    public void addVisitor(String campaignId, String visitorId, LocalDate utcDate, int delta) {
        int rows = jdbc.update("UPDATE quota_visitor_ledger SET used_visitor = used_visitor + ? "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                delta, campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("visitor ledger row missing for "
                    + campaignId + " " + visitorId + " " + utcDate);
        }
    }

    /** 释放一次公告当日额度（-1）；CHECK 约束保证不变负。 */
    public void releaseTotal(String campaignId, LocalDate utcDate) {
        int rows = jdbc.update("UPDATE quota_total_ledger SET used_total = used_total - 1 "
                        + "WHERE campaign_id = ? AND utc_date = ?",
                campaignId, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("total ledger row missing for " + campaignId + " " + utcDate);
        }
    }

    /** 释放一次访客当日额度（-1）；CHECK 约束保证不变负。 */
    public void releaseVisitor(String campaignId, String visitorId, LocalDate utcDate) {
        int rows = jdbc.update("UPDATE quota_visitor_ledger SET used_visitor = used_visitor - 1 "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("visitor ledger row missing for "
                    + campaignId + " " + visitorId + " " + utcDate);
        }
    }
}
