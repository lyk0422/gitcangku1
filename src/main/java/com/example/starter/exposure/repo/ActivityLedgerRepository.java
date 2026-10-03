package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 活动小时账目数据访问：按（活动, UTC 日, UTC 小时）记录预占占用、确认与释放数。
 * 所有加锁/增减方法必须在事务内调用，依赖行锁与 CHECK 约束保证并发下不超卖、不变负。
 */
@Repository
public class ActivityLedgerRepository {

    /**
     * 某小时账目视图。
     *
     * @param hourOfDay      UTC 小时（0~23）
     * @param reservedCount  尚未确认的预占占用数，单位次
     * @param confirmedCount 已确认数，单位次
     * @param cancelledCount 已释放数（取消与过期合计），单位次
     */
    public record HourLedger(int hourOfDay, int reservedCount, int confirmedCount,
                             int cancelledCount) {
    }

    private final JdbcTemplate jdbc;

    public ActivityLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保某小时账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String activityId, LocalDate utcDate, int hourOfDay) {
        jdbc.update("INSERT INTO activity_hour_ledger (activity_id, utc_date, hour_of_day, "
                        + "reserved_count, confirmed_count, cancelled_count) "
                        + "SELECT ?, ?, ?, 0, 0, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
    }

    /**
     * 行锁读取某小时账目；行不存在返回 null。
     */
    public HourLedger lockHour(String activityId, LocalDate utcDate, int hourOfDay) {
        List<HourLedger> list = jdbc.query("SELECT hour_of_day, reserved_count, confirmed_count, "
                        + "cancelled_count FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ? FOR UPDATE",
                (rs, rowNum) -> new HourLedger(rs.getInt("hour_of_day"), rs.getInt("reserved_count"),
                        rs.getInt("confirmed_count"), rs.getInt("cancelled_count")),
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 原子预占一次活动小时名额：仅当占用后（reserved + confirmed + 1）不超过 budget 才 +1。
     *
     * @return 是否预占成功；false 表示该小时额度已满
     */
    public boolean tryReserveHour(String activityId, LocalDate utcDate, int hourOfDay, int budget) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET reserved_count = reserved_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ? "
                        + "AND reserved_count + confirmed_count + 1 <= ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay, budget);
        return rows == 1;
    }

    /**
     * 确认结算：同一小时预占占用 -1、确认数 +1。账目行必须存在（申请时已创建）。
     */
    public void settleConfirm(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET reserved_count = reserved_count - 1, "
                        + "confirmed_count = confirmed_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /**
     * 释放尚未确认的同小时预占：占用 -1、释放数 +1（取消与过期共用）。
     */
    public void releaseReserved(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET reserved_count = reserved_count - 1, "
                        + "cancelled_count = cancelled_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /**
     * 读取某活动某 UTC 日全部小时账目（无账目的 hour 不在结果中），按小时升序。
     */
    public List<HourLedger> findByDate(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT hour_of_day, reserved_count, confirmed_count, cancelled_count "
                        + "FROM activity_hour_ledger WHERE activity_id = ? AND utc_date = ? "
                        + "ORDER BY hour_of_day",
                (rs, rowNum) -> new HourLedger(rs.getInt("hour_of_day"), rs.getInt("reserved_count"),
                        rs.getInt("confirmed_count"), rs.getInt("cancelled_count")),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 读取某活动某 UTC 日各小时占用合计（reserved + confirmed），按下标返回长度 24 的数组。
     */
    public int[] usedByHour(String activityId, LocalDate utcDate) {
        int[] used = new int[24];
        for (HourLedger ledger : findByDate(activityId, utcDate)) {
            used[ledger.hourOfDay()] = ledger.reservedCount() + ledger.confirmedCount();
        }
        return used;
    }

    /**
     * 读取某活动某 UTC 日已确认总数。
     */
    public int totalConfirmed(String activityId, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT COALESCE(SUM(confirmed_count), 0) "
                        + "FROM activity_hour_ledger WHERE activity_id = ? AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /**
     * 读取某活动某 UTC 日已释放（取消与过期）总数。
     */
    public int totalCancelled(String activityId, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT COALESCE(SUM(cancelled_count), 0) "
                        + "FROM activity_hour_ledger WHERE activity_id = ? AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /**
     * 按小时补齐 0~23 的完整账目视图；无账目行的小时三个计数均为 0。
     */
    public List<HourLedger> fullDay(String activityId, LocalDate utcDate) {
        List<HourLedger> existing = findByDate(activityId, utcDate);
        List<HourLedger> full = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            HourLedger found = null;
            for (HourLedger ledger : existing) {
                if (ledger.hourOfDay() == hour) {
                    found = ledger;
                    break;
                }
            }
            full.add(found != null ? found : new HourLedger(hour, 0, 0, 0));
        }
        return full;
    }
}
