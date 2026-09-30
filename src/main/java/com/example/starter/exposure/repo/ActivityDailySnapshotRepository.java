package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结每小时快照数据访问。固化按 (activity, utcDate) 整体一次事务写入 24 行，
 * 已固化日只读不改写。方法必须在事务内调用。
 */
@Repository
public class ActivityDailySnapshotRepository {

    private final JdbcTemplate jdbc;

    public ActivityDailySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 某活动某日是否已固化日结快照。 */
    public boolean exists(String activityId, LocalDate utcDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM activity_daily_hour_snapshot WHERE activity_id = ? AND utc_date = ?",
                Integer.class, activityId, java.sql.Date.valueOf(utcDate));
        return count != null && count > 0;
    }

    /** 写入某活动某日某小时的确认数与取消数快照。 */
    public void insertHour(String activityId, LocalDate utcDate, int hour,
                           int confirmedCount, int cancelledCount, long createdAtUtc) {
        jdbc.update("INSERT INTO activity_daily_hour_snapshot "
                        + "(activity_id, utc_date, utc_hour, confirmed_count, cancelled_count, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                activityId, java.sql.Date.valueOf(utcDate), hour, confirmedCount, cancelledCount, createdAtUtc);
    }

    /** 幂等写入：并发固化时胜出行已存在则保留，不改写已结算数据。 */
    public void insertHourIfAbsent(String activityId, LocalDate utcDate, int hour,
                                   int confirmedCount, int cancelledCount, long createdAtUtc) {
        jdbc.update("INSERT INTO activity_daily_hour_snapshot "
                        + "(activity_id, utc_date, utc_hour, confirmed_count, cancelled_count, created_at_utc) "
                        + "SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM activity_daily_hour_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hour, confirmedCount, cancelledCount, createdAtUtc,
                activityId, java.sql.Date.valueOf(utcDate), hour);
    }

    /** 某活动某日的 24 行快照，按小时升序；未固化返回空列表。 */
    public List<HourCount> findDay(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT utc_hour, confirmed_count, cancelled_count FROM activity_daily_hour_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY utc_hour ASC",
                (rs, rowNum) -> new HourCount(rs.getInt("utc_hour"),
                        rs.getInt("confirmed_count"), rs.getInt("cancelled_count")),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 单小时日结视图。
     *
     * @param hour           UTC 小时序号（0～23，区间左闭右开）
     * @param confirmedCount 该小时固化的确认数，单位次
     * @param cancelledCount 该小时固化的取消数（含过期释放的预占不计入，仅主动取消），单位次
     */
    public record HourCount(int hour, int confirmedCount, int cancelledCount) {
    }
}
