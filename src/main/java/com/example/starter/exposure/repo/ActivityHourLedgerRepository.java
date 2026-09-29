package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 活动小时名额账目数据访问。记录活动某 UTC 日某小时当前占用数
 * （RESERVED 与 CONFIRMED 合计）。加锁/增减方法必须在事务内调用，依赖
 * SELECT ... FOR UPDATE 行锁与 CHECK (used_hour &gt;= 0) 保证并发不超卖、不变负。
 */
@Repository
public class ActivityHourLedgerRepository {

    private final JdbcTemplate jdbc;

    public ActivityHourLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保活动某小时账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String activityId, LocalDate utcDate, int utcHour) {
        jdbc.update("INSERT INTO quota_activity_hour_ledger (activity_id, utc_date, utc_hour, used_hour) "
                        + "SELECT ?, ?, ?, 0 WHERE NOT EXISTS (SELECT 1 FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?)",
                activityId, java.sql.Date.valueOf(utcDate), utcHour,
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
    }

    /** 无锁读取活动某小时已占用名额（只读诊断用）；账目行不存在视为 null。 */
    public Integer jdbcReadUsed(String activityId, LocalDate utcDate, int utcHour) {
        return jdbc.query("SELECT used_hour FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
    }

    /** 行锁读取活动某小时已占用名额；账目行不存在视为 0。 */
    public int lockUsed(String activityId, LocalDate utcDate, int utcHour) {
        Integer value = jdbc.query("SELECT used_hour FROM quota_activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
        return value == null ? 0 : value;
    }

    /** 占用活动某小时名额（+delta）。调用方须已持行锁并完成容量校验。 */
    public void add(String activityId, LocalDate utcDate, int utcHour, int delta) {
        int rows = jdbc.update("UPDATE quota_activity_hour_ledger SET used_hour = used_hour + ? "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                delta, activityId, java.sql.Date.valueOf(utcDate), utcHour);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + utcHour);
        }
    }

    /** 释放活动某小时名额（-1）；CHECK 约束保证不变负。 */
    public void release(String activityId, LocalDate utcDate, int utcHour) {
        int rows = jdbc.update("UPDATE quota_activity_hour_ledger SET used_hour = used_hour - 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + utcHour);
        }
    }
}
