package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 活动当天每小时生效预算冻结记录数据访问。方法必须在事务内调用。
 *
 * <p>已开始小时（h &lt;= 当前 UTC 小时）一旦在某版本配置下生效，即冻结当天该小时
 * “生效过的最高预算”，配置修改不得低于该值；冻结按 UTC 日独立，次日不携带历史。</p>
 */
@Repository
public class ActivityHourBudgetFreezeRepository {

    private final JdbcTemplate jdbc;

    public ActivityHourBudgetFreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取当天该小时冻结的最高生效预算；不存在返回 null。 */
    public Integer find(String activityId, LocalDate utcDate, int hour) {
        return jdbc.query("SELECT frozen_budget FROM activity_hour_budget_freeze "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), hour);
    }

    /**
     * 把“当前版本在该小时生效的预算”并入冻结：记录不存在则创建，
     * 已存在则取两者较大值（GREATEST），保证已开始小时当天预算单调不降。
     */
    public void freezeMax(String activityId, LocalDate utcDate, int hour,
                          int effectiveBudget, long createdAtUtc) {
        jdbc.update("INSERT INTO activity_hour_budget_freeze "
                        + "(activity_id, utc_date, utc_hour, frozen_budget, created_at_utc) "
                        + "SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM activity_hour_budget_freeze "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hour, effectiveBudget, createdAtUtc,
                activityId, java.sql.Date.valueOf(utcDate), hour);
        jdbc.update("UPDATE activity_hour_budget_freeze SET frozen_budget = GREATEST(frozen_budget, ?) "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?",
                effectiveBudget, activityId, java.sql.Date.valueOf(utcDate), hour);
    }
}
