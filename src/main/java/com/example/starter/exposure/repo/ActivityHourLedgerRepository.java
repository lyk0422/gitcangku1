package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动小时账目数据访问。记录活动某 UTC 日某小时的当前占用数与确认/取消/过期累计数。
 * 所有加锁/增减方法必须在事务内调用，依赖 SELECT ... FOR UPDATE 行锁与
 * CHECK (reserved_count &gt;= 0) 约束保证并发下不超卖、不变负。
 */
@Repository
public class ActivityHourLedgerRepository {

    /** 活动某小时账目视图。所有计数单位均为次。 */
    public record HourLedger(
            String activityId,
            LocalDate utcDate,
            int utcHour,
            int reservedCount,
            int confirmedCount,
            int cancelledCount,
            int expiredCount) {
    }

    private static final String COLUMNS =
            "activity_id, utc_date, utc_hour, reserved_count, "
                    + "confirmed_count, cancelled_count, expired_count";

    private final JdbcTemplate jdbc;

    public ActivityHourLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保活动指定小时账目行存在（不存在则以全 0 创建）。 */
    public void ensureRow(String activityId, LocalDate utcDate, int utcHour) {
        jdbc.update("INSERT INTO activity_hour_ledger "
                        + "(activity_id, utc_date, utc_hour, reserved_count, "
                        + "confirmed_count, cancelled_count, expired_count) "
                        + "SELECT ?, ?, ?, 0, 0, 0, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?)",
                activityId, java.sql.Date.valueOf(utcDate), utcHour,
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
    }

    /** 行锁读取活动指定小时账目；账目行不存在视为全 0。 */
    public HourLedger lock(String activityId, LocalDate utcDate, int utcHour) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ? FOR UPDATE",
                rs -> rs.next() ? mapRow(rs) : new HourLedger(activityId, utcDate, utcHour, 0, 0, 0, 0),
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
    }

    /** 行锁列出活动某 UTC 日全部已有小时账目（按小时升序）。 */
    public List<HourLedger> lockByDay(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY utc_hour FOR UPDATE",
                (rs, rowNum) -> mapRow(rs),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /** 只读列出活动某 UTC 日全部已有小时账目（按小时升序）。 */
    public List<HourLedger> listByDay(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY utc_hour",
                (rs, rowNum) -> mapRow(rs),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 条件占用一个小时名额：仅当占用后不超过该小时预算时 reserved_count + 1。
     *
     * @return 是否占用成功；false 表示该小时预算耗尽
     */
    public boolean tryReserve(String activityId, LocalDate utcDate, int utcHour, int hourBudget) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET reserved_count = reserved_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ? "
                        + "AND reserved_count + 1 <= ?",
                activityId, java.sql.Date.valueOf(utcDate), utcHour, hourBudget);
        return rows == 1;
    }

    /** 确认结算：confirmed_count + 1；reserved_count 保持占用，不释放。 */
    public void addConfirmed(String activityId, LocalDate utcDate, int utcHour) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET confirmed_count = confirmed_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
        if (rows != 1) {
            throw new IllegalStateException("hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + utcHour);
        }
    }

    /** 取消/过期释放尚未确认的同一小时预占：reserved_count - 1 且 cancelled_count + 1。 */
    public void releaseAndCountCancel(String activityId, LocalDate utcDate, int utcHour) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET reserved_count = reserved_count - 1, cancelled_count = cancelled_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
        if (rows != 1) {
            throw new IllegalStateException("hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + utcHour);
        }
    }

    /** 过期结算：reserved_count - 1 且 expired_count + 1（CHECK 保证不变负）。 */
    public void releaseAndCountExpire(String activityId, LocalDate utcDate, int utcHour) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET reserved_count = reserved_count - 1, expired_count = expired_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                activityId, java.sql.Date.valueOf(utcDate), utcHour);
        if (rows != 1) {
            throw new IllegalStateException("hour ledger row missing for "
                    + activityId + " " + utcDate + " hour " + utcHour);
        }
    }

    private HourLedger mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new HourLedger(
                rs.getString("activity_id"),
                rs.getDate("utc_date").toLocalDate(),
                rs.getInt("utc_hour"),
                rs.getInt("reserved_count"),
                rs.getInt("confirmed_count"),
                rs.getInt("cancelled_count"),
                rs.getInt("expired_count"));
    }
}
