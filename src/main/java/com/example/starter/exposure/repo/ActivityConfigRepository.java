package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ActivityDayConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 活动按 UTC 日配置数据访问：日总额 + 24 个小时预算 + 版本历史。
 * 行锁方法必须在事务内调用。
 */
@Repository
public class ActivityConfigRepository {

    /**
     * 配置历史条目视图。
     *
     * @param activityId    活动编号
     * @param utcDate       配置所属 UTC 日
     * @param version       配置版本
     * @param dailyTotalCap 日总确认额度，单位次
     * @param hourlyBudgets 24 个小时预算，下标即小时
     * @param requestId     触发本次修改的幂等键
     * @param changedAtUtc  修改时刻，epoch 毫秒，UTC
     */
    public record ConfigHistoryEntry(String activityId, LocalDate utcDate, int version,
                                     int dailyTotalCap, List<Integer> hourlyBudgets,
                                     String requestId, long changedAtUtc) {
    }

    private final JdbcTemplate jdbc;

    public ActivityConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询活动某 UTC 日配置（含 24 个小时预算）；未配置返回 empty。 */
    public Optional<ActivityDayConfig> findConfig(String activityId, LocalDate utcDate) {
        var rows = jdbc.query("SELECT daily_total_cap, version, updated_at_utc "
                        + "FROM activity_day_config WHERE activity_id = ? AND utc_date = ?",
                (rs, rowNum) -> new Object[]{rs.getInt(1), rs.getInt(2), rs.getLong(3)},
                activityId, java.sql.Date.valueOf(utcDate));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] head = rows.get(0);
        return Optional.of(new ActivityDayConfig(activityId, utcDate,
                (Integer) head[0], loadBudgets(activityId, utcDate),
                (Integer) head[1], (Long) head[2]));
    }

    /** 行锁读取配置版本；未配置返回 empty。用于 expectedVersion 校验。 */
    public Optional<Integer> lockVersion(String activityId, LocalDate utcDate) {
        Integer version = jdbc.query("SELECT version FROM activity_day_config "
                        + "WHERE activity_id = ? AND utc_date = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate));
        return Optional.ofNullable(version);
    }

    /** 读取 24 个小时预算，按下标（小时）升序；缺行视为未配置，由调用方校验完整性。 */
    public List<Integer> loadBudgets(String activityId, LocalDate utcDate) {
        Integer[] budgets = new Integer[24];
        jdbc.query("SELECT hour_of_day, budget FROM activity_hour_budget "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY hour_of_day",
                rs -> {
                    budgets[rs.getInt(1)] = rs.getInt(2);
                },
                activityId, java.sql.Date.valueOf(utcDate));
        List<Integer> result = new ArrayList<>(24);
        for (Integer budget : budgets) {
            result.add(budget);
        }
        return result;
    }

    /** 写入新一日配置（首版）。 */
    public void insertConfig(String activityId, LocalDate utcDate, int dailyTotalCap,
                             int version, long updatedAtUtc) {
        jdbc.update("INSERT INTO activity_day_config "
                        + "(activity_id, utc_date, daily_total_cap, version, updated_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                activityId, java.sql.Date.valueOf(utcDate), dailyTotalCap, version, updatedAtUtc);
    }

    /** 更新既有配置的日总额与版本。 */
    public void updateConfig(String activityId, LocalDate utcDate, int dailyTotalCap,
                             int version, long updatedAtUtc) {
        int rows = jdbc.update("UPDATE activity_day_config "
                        + "SET daily_total_cap = ?, version = ?, updated_at_utc = ? "
                        + "WHERE activity_id = ? AND utc_date = ?",
                dailyTotalCap, version, updatedAtUtc,
                activityId, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("activity_day_config row missing for "
                    + activityId + " " + utcDate);
        }
    }

    /** 整批替换某日的 24 个小时预算：先删后插，同事务提交。 */
    public void replaceBudgets(String activityId, LocalDate utcDate, List<Integer> hourlyBudgets) {
        jdbc.update("DELETE FROM activity_hour_budget WHERE activity_id = ? AND utc_date = ?",
                activityId, java.sql.Date.valueOf(utcDate));
        for (int hour = 0; hour < hourlyBudgets.size(); hour++) {
            jdbc.update("INSERT INTO activity_hour_budget (activity_id, utc_date, hour_of_day, budget) "
                            + "VALUES (?, ?, ?, ?)",
                    activityId, java.sql.Date.valueOf(utcDate), hour, hourlyBudgets.get(hour));
        }
    }

    /** 追加一条配置历史快照。 */
    public void insertHistory(ConfigHistoryEntry entry) {
        String joined = entry.hourlyBudgets().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        jdbc.update("INSERT INTO activity_config_history "
                        + "(activity_id, utc_date, version, daily_total_cap, hourly_budgets, "
                        + "request_id, changed_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                entry.activityId(), java.sql.Date.valueOf(entry.utcDate()), entry.version(),
                entry.dailyTotalCap(), joined, entry.requestId(), entry.changedAtUtc());
    }

    /** 查询某日全部配置历史，按版本升序。 */
    public List<ConfigHistoryEntry> listHistory(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT version, daily_total_cap, hourly_budgets, request_id, changed_at_utc "
                        + "FROM activity_config_history "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY version",
                (rs, rowNum) -> {
                    List<Integer> budgets = new ArrayList<>();
                    for (String part : rs.getString("hourly_budgets").split(",")) {
                        budgets.add(Integer.valueOf(part));
                    }
                    return new ConfigHistoryEntry(activityId, utcDate,
                            rs.getInt("version"), rs.getInt("daily_total_cap"), budgets,
                            rs.getString("request_id"), rs.getLong("changed_at_utc"));
                },
                activityId, java.sql.Date.valueOf(utcDate));
    }
}