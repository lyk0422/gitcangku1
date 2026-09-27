package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Activity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 活动与活动小时预算数据访问。预算为每 UTC 日重复使用的 24 小时模板；
 * 配置修改整体替换 24 行预算并对版本号 CAS。
 */
@Repository
public class ActivityRepository {

    private final JdbcTemplate jdbc;

    public ActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Activity> MAPPER = (rs, rowNum) -> new Activity(
            rs.getString("activity_id"),
            rs.getInt("daily_total_cap"),
            rs.getInt("version"),
            rs.getLong("created_at_utc"));

    private static final String COLUMNS = "activity_id, daily_total_cap, version, created_at_utc";

    /** 按编号查询活动。 */
    public Optional<Activity> findById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ?",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /** 行锁读取活动；配置修改前必须调用以串行化并发修改。 */
    public Optional<Activity> lockById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ? FOR UPDATE",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /** 插入活动；编号冲突由调用方依据唯一约束处理。初始版本为 1。 */
    public void insert(Activity activity) {
        jdbc.update("INSERT INTO activity (" + COLUMNS + ") VALUES (?, ?, ?, ?)",
                activity.activityId(),
                activity.dailyTotalCap(),
                activity.version(),
                activity.createdAtUtc());
    }

    /**
     * 条件更新配置：仅当当前版本等于 expectedVersion 时更新日总额度并将版本 +1。
     *
     * @return 是否更新成功；false 表示版本已被并发修改
     */
    public boolean compareAndSetConfig(String activityId, int expectedVersion, int dailyTotalCap) {
        int rows = jdbc.update("UPDATE activity SET daily_total_cap = ?, version = version + 1 "
                        + "WHERE activity_id = ? AND version = ?",
                dailyTotalCap, activityId, expectedVersion);
        return rows == 1;
    }

    /**
     * 读取活动 24 个小时预算（按 UTC 小时 0~23 顺序）；缺失小时按 0 处理。
     */
    public List<Integer> getBudgets(String activityId) {
        Integer[] budgets = new Integer[24];
        jdbc.query("SELECT hour_of_day, budget FROM activity_hour_budget WHERE activity_id = ?",
                rs -> {
                    budgets[rs.getInt("hour_of_day")] = rs.getInt("budget");
                }, activityId);
        List<Integer> result = new ArrayList<>(24);
        for (Integer budget : budgets) {
            result.add(budget == null ? 0 : budget);
        }
        return result;
    }

    /**
     * 整体替换 24 个小时预算；调用方须已持活动行锁并完成全部校验。
     *
     * @param budgets 恰好 24 个非负预算，按下标对应 UTC 小时 0~23
     */
    public void replaceBudgets(String activityId, List<Integer> budgets) {
        jdbc.update("DELETE FROM activity_hour_budget WHERE activity_id = ?", activityId);
        for (int hour = 0; hour < budgets.size(); hour++) {
            jdbc.update("INSERT INTO activity_hour_budget (activity_id, hour_of_day, budget) "
                    + "VALUES (?, ?, ?)", activityId, hour, budgets.get(hour));
        }
    }
}
