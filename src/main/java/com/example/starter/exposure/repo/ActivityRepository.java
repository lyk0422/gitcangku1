package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Activity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 活动表数据访问。加锁/版本 CAS 方法必须在事务内调用。
 */
@Repository
public class ActivityRepository {

    private final JdbcTemplate jdbc;

    public ActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String BUDGET_COLUMNS;

    static {
        StringBuilder columns = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            if (h > 0) {
                columns.append(", ");
            }
            columns.append(String.format("budget_h%02d", h));
        }
        BUDGET_COLUMNS = columns.toString();
    }

    private final RowMapper<Activity> mapper = (rs, rowNum) -> map(rs);

    static Activity map(ResultSet rs) throws SQLException {
        int[] budgets = new int[24];
        for (int h = 0; h < 24; h++) {
            budgets[h] = rs.getInt(String.format("budget_h%02d", h));
        }
        return new Activity(
                rs.getString("activity_id"),
                rs.getInt("daily_total_cap"),
                budgets,
                rs.getLong("version"),
                rs.getLong("created_at_utc"),
                rs.getLong("updated_at_utc"));
    }

    /** 按编号查询活动。 */
    public Optional<Activity> findById(String activityId) {
        return jdbc.query(selectSql() + " WHERE activity_id = ?", mapper, activityId)
                .stream().findFirst();
    }

    /** 行锁读取活动。 */
    public Optional<Activity> lockById(String activityId) {
        return jdbc.query(selectSql() + " WHERE activity_id = ? FOR UPDATE", mapper, activityId)
                .stream().findFirst();
    }

    /** 插入活动（初版 version=0）。 */
    public void insert(Activity activity) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO activity (activity_id, daily_total_cap, " + BUDGET_COLUMNS
                        + ", version, created_at_utc, updated_at_utc) VALUES (?, ?, ");
        for (int h = 0; h < 24; h++) {
            sql.append(h == 0 ? "?" : ", ?");
        }
        sql.append(", 0, ?, ?)");
        Object[] args = new Object[28];
        args[0] = activity.activityId();
        args[1] = activity.dailyTotalCap();
        for (int h = 0; h < 24; h++) {
            args[2 + h] = activity.hourlyBudget()[h];
        }
        args[26] = activity.createdAtUtc();
        args[27] = activity.updatedAtUtc();
        jdbc.update(sql.toString(), args);
    }

    /**
     * 携带期望版本号的配置修改 CAS：仅当当前 version = expectedVersion 时更新，版本号 +1。
     *
     * @return 是否更新成功；false 表示版本不匹配（并发修改）
     */
    public boolean updateWithVersion(Activity activity, long expectedVersion) {
        StringBuilder sql = new StringBuilder(
                "UPDATE activity SET daily_total_cap = ?, " + BUDGET_COLUMNS.replace(", ", " = ?, ")
                        + " = ?, version = version + 1, updated_at_utc = ? "
                        + "WHERE activity_id = ? AND version = ?");
        Object[] args = new Object[28];
        args[0] = activity.dailyTotalCap();
        for (int h = 0; h < 24; h++) {
            args[1 + h] = activity.hourlyBudget()[h];
        }
        args[25] = activity.updatedAtUtc();
        args[26] = activity.activityId();
        args[27] = expectedVersion;
        return jdbc.update(sql.toString(), args) == 1;
    }

    private String selectSql() {
        return "SELECT activity_id, daily_total_cap, " + BUDGET_COLUMNS
                + ", version, created_at_utc, updated_at_utc FROM activity";
    }
}
