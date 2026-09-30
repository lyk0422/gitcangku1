package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ActivityDayConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 活动 UTC 日匀速投放配置数据访问：配置主表 + 24 行小时预算。
 * 加锁方法必须在事务内调用。
 */
@Repository
public class ActivityDayConfigRepository {

    private static final int HOURS_PER_DAY = 24;

    private final JdbcTemplate jdbc;

    public ActivityDayConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按活动与 UTC 日读取配置（无锁）；不存在返回 empty。
     */
    public Optional<ActivityDayConfig> findConfig(String activityId, LocalDate utcDate) {
        return load(activityId, utcDate, false);
    }

    /**
     * 行锁读取配置主行后装配 24 小时预算；不存在返回 empty。必须在事务内调用。
     */
    public Optional<ActivityDayConfig> lockConfig(String activityId, LocalDate utcDate) {
        return load(activityId, utcDate, true);
    }

    /**
     * 插入配置主行与 24 行小时预算；主键冲突由调用方依据唯一约束处理。
     */
    public void insert(ActivityDayConfig config) {
        jdbc.update("INSERT INTO activity_day_config "
                        + "(activity_id, utc_date, daily_total_cap, version, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                config.activityId(), java.sql.Date.valueOf(config.utcDate()),
                config.dailyTotalCap(), config.version(), config.updatedAtUtc());
        insertBudgets(config);
    }

    /**
     * 版本 CAS 更新：仅当当前版本等于 expectedVersion 时更新主行并重写 24 行预算。
     *
     * @return 是否更新成功（并发修改时只有一个返回 true）
     */
    public boolean update(ActivityDayConfig config, long expectedVersion) {
        int rows = jdbc.update("UPDATE activity_day_config "
                        + "SET daily_total_cap = ?, version = ?, updated_at_utc = ? "
                        + "WHERE activity_id = ? AND utc_date = ? AND version = ?",
                config.dailyTotalCap(), config.version(), config.updatedAtUtc(),
                config.activityId(), java.sql.Date.valueOf(config.utcDate()), expectedVersion);
        if (rows != 1) {
            return false;
        }
        jdbc.update("DELETE FROM activity_hour_budget WHERE activity_id = ? AND utc_date = ?",
                config.activityId(), java.sql.Date.valueOf(config.utcDate()));
        insertBudgets(config);
        return true;
    }

    private Optional<ActivityDayConfig> load(String activityId, LocalDate utcDate, boolean forUpdate) {
        String sql = "SELECT activity_id, utc_date, daily_total_cap, version, updated_at_utc "
                + "FROM activity_day_config WHERE activity_id = ? AND utc_date = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        List<ActivityDayConfig> head = jdbc.query(sql,
                (rs, rowNum) -> new ActivityDayConfig(
                        rs.getString("activity_id"),
                        rs.getDate("utc_date").toLocalDate(),
                        rs.getInt("daily_total_cap"),
                        Collections.emptyList(),
                        rs.getLong("version"),
                        rs.getLong("updated_at_utc")),
                activityId, java.sql.Date.valueOf(utcDate));
        if (head.isEmpty()) {
            return Optional.empty();
        }
        ActivityDayConfig config = head.get(0);
        List<Integer> budgets = new ArrayList<>(Collections.nCopies(HOURS_PER_DAY, 0));
        jdbc.query("SELECT hour_of_day, budget FROM activity_hour_budget "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY hour_of_day",
                rs -> {
                    budgets.set(rs.getInt("hour_of_day"), rs.getInt("budget"));
                },
                activityId, java.sql.Date.valueOf(utcDate));
        return Optional.of(new ActivityDayConfig(
                config.activityId(), config.utcDate(), config.dailyTotalCap(),
                Collections.unmodifiableList(budgets), config.version(), config.updatedAtUtc()));
    }

    private void insertBudgets(ActivityDayConfig config) {
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            jdbc.update("INSERT INTO activity_hour_budget (activity_id, utc_date, hour_of_day, budget) "
                            + "VALUES (?, ?, ?, ?)",
                    config.activityId(), java.sql.Date.valueOf(config.utcDate()),
                    hour, config.hourlyBudgets().get(hour));
        }
    }
}
