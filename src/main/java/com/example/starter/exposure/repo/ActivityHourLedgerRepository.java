package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 活动某 UTC 日某小时名额账目数据访问。所有方法必须在事务内调用，
 * 依赖 SELECT ... FOR UPDATE 行锁与 CHECK (used_hour &gt;= 0) 约束
 * 保证并发跨公告申请不超卖、取消/过期不变负。
 */
@Repository
public class ActivityHourLedgerRepository {

    private final JdbcTemplate jdbc;

    public ActivityHourLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保活动当日当小时账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String activityId, LocalDate utcDate, int hour) {
        jdbc.update("INSERT INTO quota_activity_hour_ledger (activity_id, utc_date, utc_hour, used_hour) "
                        + "SELECT ?, ?, ?, 0 WHERE NOT EXISTS (SELECT 1 FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hour,
                activityId, java.sql.Date.valueOf(utcDate), hour);
    }

    /** 行锁读取活动当日当小时已占用名额；账目行不存在视为 0。 */
    public int lockUsed(String activityId, LocalDate utcDate, int hour) {
        Integer value = jdbc.query("SELECT used_hour FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), hour);
        return value == null ? 0 : value;
    }

    /** 普通读取活动当日当小时已占用名额（只读诊断用）；账目行不存在视为 0。 */
    public int getUsed(String activityId, LocalDate utcDate, int hour) {
        Integer value = jdbc.query("SELECT used_hour FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), hour);
        return value == null ? 0 : value;
    }

    /** 占用一次活动当小时名额（+1）。调用方须已持行锁并完成容量校验。 */
    public void add(String activityId, LocalDate utcDate, int hour) {
        int rows = jdbc.update("UPDATE quota_activity_hour_ledger SET used_hour = used_hour + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                activityId, java.sql.Date.valueOf(utcDate), hour);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + hour);
        }
    }

    /** 释放一次活动当小时名额（-1），仅用于取消/过期尚未确认的同一小时预占。 */
    public void release(String activityId, LocalDate utcDate, int hour) {
        int rows = jdbc.update("UPDATE quota_activity_hour_ledger SET used_hour = used_hour - 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                activityId, java.sql.Date.valueOf(utcDate), hour);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + hour);
        }
    }
}
