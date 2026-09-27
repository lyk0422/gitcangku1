package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ActivityDayConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 活动日预算配置数据访问：activity_day_config（日总额）与 activity_hour_budget（24 小时预算）。
 * 配置整体替换必须在事务内调用，调用方须已持活动行锁。
 */
@Repository
public class ActivityBudgetRepository {

    private final JdbcTemplate jdbc;

    public ActivityBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询某 UTC 日的日配置；未配置返回 empty。
     */
    public Optional<ActivityDayConfig> findConfig(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT activity_id, utc_date, daily_total, updated_at_utc "
                                + "FROM activity_day_config WHERE activity_id = ? AND utc_date = ?",
                        (rs, rowNum) -> new ActivityDayConfig(
                                rs.getString("activity_id"),
                                rs.getDate("utc_date"),
                                rs.getInt("daily_total"),
                                rs.getLong("updated_at_utc")),
                        activityId, java.sql.Date.valueOf(utcDate))
                .stream()
                .findFirst();
    }

    /**
     * 查询某 UTC 日的 24 小时预算，按 hour_of_day 升序；未配置返回空列表。
     */
    public List<Integer> findHourBudgets(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT budget FROM activity_hour_budget "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY hour_of_day",
                (rs, rowNum) -> rs.getInt("budget"),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 整体替换某 UTC 日配置：先删后插日配置与 24 个小时预算。
     * 调用方须已完成全部校验（合计、非负、已开始小时不可下调）并持活动行锁。
     */
    public void replaceConfig(String activityId, LocalDate utcDate, int dailyTotal,
                              List<Integer> hourBudgets, long updatedAtUtc) {
        jdbc.update("DELETE FROM activity_day_config WHERE activity_id = ? AND utc_date = ?",
                activityId, java.sql.Date.valueOf(utcDate));
        jdbc.update("DELETE FROM activity_hour_budget WHERE activity_id = ? AND utc_date = ?",
                activityId, java.sql.Date.valueOf(utcDate));
        jdbc.update("INSERT INTO activity_day_config (activity_id, utc_date, daily_total, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                activityId, java.sql.Date.valueOf(utcDate), dailyTotal, updatedAtUtc);
        for (int hour = 0; hour < hourBudgets.size(); hour++) {
            jdbc.update("INSERT INTO activity_hour_budget (activity_id, utc_date, hour_of_day, budget) "
                            + "VALUES (?, ?, ?, ?)",
                    activityId, java.sql.Date.valueOf(utcDate), hour, hourBudgets.get(hour));
        }
    }
}
