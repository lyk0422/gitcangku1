package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动小时名额账目数据访问。所有加锁/增减方法必须在事务内调用，
 * 依赖 SELECT ... FOR UPDATE 行锁与 CHECK 约束保证并发下不超卖、不变负。
 * confirmed/cancelled/expired 为历史固化计数，只在对应状态转换时 +1，从不回减。
 */
@Repository
public class ActivityHourLedgerRepository {

    /**
     * 活动某 UTC 日某小时的账目视图。
     *
     * @param hourOfDay UTC 小时，0~23
     * @param used      当前占用名额（RESERVED 与 CONFIRMED 合计），单位次
     * @param confirmed 已确认数，单位次，历史固化
     * @param cancelled 已取消数，单位次，历史固化
     * @param expired   已过期数，单位次，历史固化
     */
    public record HourLedgerEntry(int hourOfDay, int used, int confirmed, int cancelled, int expired) {
    }

    private static final RowMapper<HourLedgerEntry> MAPPER = (rs, rowNum) -> new HourLedgerEntry(
            rs.getInt("hour_of_day"),
            rs.getInt("used"),
            rs.getInt("confirmed"),
            rs.getInt("cancelled"),
            rs.getInt("expired"));

    private final JdbcTemplate jdbc;

    public ActivityHourLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String activityId, LocalDate utcDate, int hourOfDay) {
        jdbc.update("INSERT INTO activity_hour_ledger (activity_id, utc_date, hour_of_day) "
                        + "SELECT ?, ?, ? WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
    }

    /** 行锁读取当前占用名额；账目行不存在视为 0。 */
    public int lockUsed(String activityId, LocalDate utcDate, int hourOfDay) {
        Integer value = jdbc.query("SELECT used FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        return value == null ? 0 : value;
    }

    /** 占用一次小时名额（+1）。调用方须已持行锁并完成容量校验。 */
    public void addUsed(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET used = used + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /** 释放一次小时名额（-1）；CHECK 约束保证不变负。 */
    public void releaseUsed(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET used = used - 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /** 确认计数 +1（历史固化，不回减）。 */
    public void addConfirmed(String activityId, LocalDate utcDate, int hourOfDay) {
        addCounter(activityId, utcDate, hourOfDay, "confirmed");
    }

    /** 取消计数 +1（历史固化，不回减）。 */
    public void addCancelled(String activityId, LocalDate utcDate, int hourOfDay) {
        addCounter(activityId, utcDate, hourOfDay, "cancelled");
    }

    /** 过期计数 +1（历史固化，不回减）。 */
    public void addExpired(String activityId, LocalDate utcDate, int hourOfDay) {
        addCounter(activityId, utcDate, hourOfDay, "expired");
    }

    private void addCounter(String activityId, LocalDate utcDate, int hourOfDay, String column) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET " + column + " = " + column + " + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /** 查询某活动某 UTC 日的全部账目行（按小时升序）；无行的小时由调用方补 0。 */
    public List<HourLedgerEntry> findByActivityAndDate(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT hour_of_day, used, confirmed, cancelled, expired "
                        + "FROM activity_hour_ledger WHERE activity_id = ? AND utc_date = ? "
                        + "ORDER BY hour_of_day",
                MAPPER, activityId, java.sql.Date.valueOf(utcDate));
    }
}
