package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 活动小时名额账目数据访问。所有加锁/增减方法必须在事务内调用，
 * 依赖 SELECT ... FOR UPDATE 行锁与 CHECK (used &gt;= 0) 约束保证并发下不超卖、不变负。
 */
@Repository
public class ActivityHourLedgerRepository {

    private final JdbcTemplate jdbc;

    public ActivityHourLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保活动某 UTC 日某小时账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String activityId, LocalDate utcDate, int hourOfDay) {
        jdbc.update("INSERT INTO activity_hour_ledger (activity_id, utc_date, hour_of_day, used) "
                        + "SELECT ?, ?, ?, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
    }

    /** 行锁读取该小时已占用名额；账目行不存在视为 0。 */
    public int lockUsed(String activityId, LocalDate utcDate, int hourOfDay) {
        Integer value = jdbc.query("SELECT used FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        return value == null ? 0 : value;
    }

    /**
     * 占用该小时名额（+delta）。调用方须已持行锁并完成容量校验。
     */
    public void addUsed(String activityId, LocalDate utcDate, int hourOfDay, int delta) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET used = used + ? "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                delta, activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + hourOfDay);
        }
    }

    /** 释放该小时名额（-1）；CHECK 约束保证不变负。 */
    public void release(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET used = used - 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + hourOfDay);
        }
    }

    /** 读取活动某 UTC 日各小时已占用名额；无账目行的小时不在返回表中（视为 0）。 */
    public Map<Integer, Integer> usedByHour(String activityId, LocalDate utcDate) {
        Map<Integer, Integer> used = new HashMap<>();
        jdbc.query("SELECT hour_of_day, used FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ?",
                rs -> {
                    used.put(rs.getInt("hour_of_day"), rs.getInt("used"));
                },
                activityId, java.sql.Date.valueOf(utcDate));
        return used;
    }
}
