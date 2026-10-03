package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Activity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 活动与 24 小时预算数据访问。加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class ActivityRepository {

    private final JdbcTemplate jdbc;

    public ActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Activity> MAPPER = (rs, rowNum) -> new Activity(
            rs.getString("activity_id"),
            rs.getInt("daily_confirm_cap"),
            rs.getLong("version"),
            rs.getLong("created_at_utc"));

    private static final String COLUMNS = "activity_id, daily_confirm_cap, version, created_at_utc";

    public Optional<Activity> findById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ?",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取活动，用于配置修改的串行化裁决。
     */
    public Optional<Activity> lockById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ? FOR UPDATE",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /**
     * 插入活动（初始版本 1）；编号冲突由调用方依据唯一约束处理。
     */
    public void insert(Activity activity) {
        jdbc.update("INSERT INTO activity (activity_id, daily_confirm_cap, version, created_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                activity.activityId(),
                activity.dailyConfirmCap(),
                activity.version(),
                activity.createdAtUtc());
    }

    /**
     * 乐观校验修改配置：仅当当前版本等于 expectedVersion 时更新总确认额度并版本 +1。
     *
     * @return 是否更新成功；false 表示版本冲突
     */
    public boolean compareAndUpdateConfig(String activityId, int dailyConfirmCap, long expectedVersion) {
        int rows = jdbc.update("UPDATE activity SET daily_confirm_cap = ?, version = version + 1 "
                        + "WHERE activity_id = ? AND version = ?",
                dailyConfirmCap, activityId, expectedVersion);
        return rows == 1;
    }

    /**
     * 读取活动 24 小时预算，按下标返回长度 24 的数组；未配置的小时为 null。
     */
    public Integer[] findBudgets(String activityId) {
        Integer[] budgets = new Integer[24];
        jdbc.query("SELECT hour_of_day, budget FROM activity_hour_budget WHERE activity_id = ?",
                rs -> {
                    budgets[rs.getInt("hour_of_day")] = rs.getInt("budget");
                }, activityId);
        return budgets;
    }

    /**
     * 整体替换 24 小时预算（同事务内先删后插）。
     */
    public void replaceBudgets(String activityId, List<Integer> hourlyBudgets) {
        jdbc.update("DELETE FROM activity_hour_budget WHERE activity_id = ?", activityId);
        for (int hour = 0; hour < hourlyBudgets.size(); hour++) {
            jdbc.update("INSERT INTO activity_hour_budget (activity_id, hour_of_day, budget) "
                            + "VALUES (?, ?, ?)",
                    activityId, hour, hourlyBudgets.get(hour));
        }
    }
}
