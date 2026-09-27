package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Activity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 活动表数据访问。加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class ActivityRepository {

    private static final String COLUMNS =
            "activity_id, daily_total_cap, "
                    + "budget_h00, budget_h01, budget_h02, budget_h03, "
                    + "budget_h04, budget_h05, budget_h06, budget_h07, "
                    + "budget_h08, budget_h09, budget_h10, budget_h11, "
                    + "budget_h12, budget_h13, budget_h14, budget_h15, "
                    + "budget_h16, budget_h17, budget_h18, budget_h19, "
                    + "budget_h20, budget_h21, budget_h22, budget_h23, "
                    + "version, created_at_utc, updated_at_utc";

    private final JdbcTemplate jdbc;

    public ActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Activity> MAPPER = new RowMapper<>() {
        @Override
        public Activity mapRow(ResultSet rs, int rowNum) throws SQLException {
            List<Integer> budgets = new ArrayList<>(Activity.HOURS_PER_DAY);
            for (int hour = 0; hour < Activity.HOURS_PER_DAY; hour++) {
                budgets.add(rs.getInt("budget_h" + String.format("%02d", hour)));
            }
            return new Activity(
                    rs.getString("activity_id"),
                    rs.getInt("daily_total_cap"),
                    budgets,
                    rs.getInt("version"),
                    rs.getLong("created_at_utc"),
                    rs.getLong("updated_at_utc"));
        }
    };

    /** 按编号查询活动。 */
    public Optional<Activity> findById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ?",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /** 行锁读取活动（申请预占、配置修改时使用）。 */
    public Optional<Activity> lockById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ? FOR UPDATE",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /** 插入活动；编号冲突由调用方依据唯一约束处理。初始版本号为 1。 */
    public void insert(Activity activity) {
        List<Integer> b = activity.hourlyBudgets();
        jdbc.update("INSERT INTO activity (activity_id, daily_total_cap, "
                        + "budget_h00, budget_h01, budget_h02, budget_h03, "
                        + "budget_h04, budget_h05, budget_h06, budget_h07, "
                        + "budget_h08, budget_h09, budget_h10, budget_h11, "
                        + "budget_h12, budget_h13, budget_h14, budget_h15, "
                        + "budget_h16, budget_h17, budget_h18, budget_h19, "
                        + "budget_h20, budget_h21, budget_h22, budget_h23, "
                        + "version, created_at_utc, updated_at_utc) "
                        + "VALUES (?, ?, ?,?,?,?,?,?, ?,?,?,?,?,?, ?,?,?,?,?,?, ?,?,?,?,?,?, ?, ?, ?)",
                activity.activityId(),
                activity.dailyTotalCap(),
                b.get(0), b.get(1), b.get(2), b.get(3),
                b.get(4), b.get(5), b.get(6), b.get(7),
                b.get(8), b.get(9), b.get(10), b.get(11),
                b.get(12), b.get(13), b.get(14), b.get(15),
                b.get(16), b.get(17), b.get(18), b.get(19),
                b.get(20), b.get(21), b.get(22), b.get(23),
                activity.version(),
                activity.createdAtUtc(),
                activity.updatedAtUtc());
    }

    /**
     * 携带期望版本修改活动配置（乐观锁 CAS）：仅当当前版本等于 expectedVersion 时更新，
     * 新版本 = expectedVersion + 1。
     *
     * @return 是否更新成功；false 表示版本不匹配（并发修改）或活动不存在
     */
    public boolean compareAndUpdate(Activity activity, int expectedVersion, long updatedAtUtc) {
        List<Integer> b = activity.hourlyBudgets();
        int rows = jdbc.update("UPDATE activity SET daily_total_cap = ?, "
                        + "budget_h00 = ?, budget_h01 = ?, budget_h02 = ?, budget_h03 = ?, "
                        + "budget_h04 = ?, budget_h05 = ?, budget_h06 = ?, budget_h07 = ?, "
                        + "budget_h08 = ?, budget_h09 = ?, budget_h10 = ?, budget_h11 = ?, "
                        + "budget_h12 = ?, budget_h13 = ?, budget_h14 = ?, budget_h15 = ?, "
                        + "budget_h16 = ?, budget_h17 = ?, budget_h18 = ?, budget_h19 = ?, "
                        + "budget_h20 = ?, budget_h21 = ?, budget_h22 = ?, budget_h23 = ?, "
                        + "version = ?, updated_at_utc = ? WHERE activity_id = ? AND version = ?",
                activity.dailyTotalCap(),
                b.get(0), b.get(1), b.get(2), b.get(3),
                b.get(4), b.get(5), b.get(6), b.get(7),
                b.get(8), b.get(9), b.get(10), b.get(11),
                b.get(12), b.get(13), b.get(14), b.get(15),
                b.get(16), b.get(17), b.get(18), b.get(19),
                b.get(20), b.get(21), b.get(22), b.get(23),
                expectedVersion + 1, updatedAtUtc,
                activity.activityId(), expectedVersion);
        return rows == 1;
    }
}
