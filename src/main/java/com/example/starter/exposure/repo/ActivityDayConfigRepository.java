package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ActivityDayConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 活动 UTC 日投放配置数据访问。配置行携带版本号，修改以 expectedVersion 做 CAS；
 * 加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class ActivityDayConfigRepository {

    private final JdbcTemplate jdbc;

    public ActivityDayConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ActivityDayConfig> MAPPER = new RowMapper<>() {
        @Override
        public ActivityDayConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            int[] hours = new int[ActivityDayConfig.HOURS_PER_DAY];
            for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
                hours[h] = rs.getInt(hourColumn(h));
            }
            return new ActivityDayConfig(
                    rs.getString("activity_id"),
                    rs.getDate("utc_date").toLocalDate(),
                    rs.getInt("daily_total_cap"),
                    hours,
                    rs.getInt("version"),
                    rs.getLong("updated_at_utc"));
        }
    };

    private static final String COLUMNS;

    static {
        StringBuilder cols = new StringBuilder(
                "activity_id, utc_date, daily_total_cap, version, updated_at_utc");
        for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
            cols.append(", ").append(hourColumn(h));
        }
        COLUMNS = cols.toString();
    }

    /** 小时预算列名：h00～h23。 */
    public static String hourColumn(int hour) {
        return String.format("h%02d", hour);
    }

    /** 普通读取某日配置（无锁）；不存在返回 empty。 */
    public Optional<ActivityDayConfig> find(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_day_config "
                        + "WHERE activity_id = ? AND utc_date = ?",
                        MAPPER, activityId, java.sql.Date.valueOf(utcDate))
                .stream()
                .findFirst();
    }

    /** 行锁读取某日配置；不存在返回 empty。 */
    public Optional<ActivityDayConfig> lock(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_day_config "
                        + "WHERE activity_id = ? AND utc_date = ? FOR UPDATE",
                        MAPPER, activityId, java.sql.Date.valueOf(utcDate))
                .stream()
                .findFirst();
    }

    /** 查询活动在一个闭开日期区间 [from, to) 内的全部日配置，按 UTC 日升序。 */
    public List<ActivityDayConfig> findRange(String activityId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_day_config "
                        + "WHERE activity_id = ? AND utc_date >= ? AND utc_date < ? ORDER BY utc_date",
                        MAPPER, activityId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }

    /** 插入首版配置（version=1）；同一活动日重复插入由唯一约束拒绝。 */
    public void insert(ActivityDayConfig config) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO activity_day_config (activity_id, utc_date, daily_total_cap, version, updated_at_utc");
        StringBuilder placeholders = new StringBuilder("VALUES (?, ?, ?, 1, ?");
        for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
            sql.append(", ").append(hourColumn(h));
            placeholders.append(", ?");
        }
        sql.append(") ").append(placeholders).append(")");

        Object[] args = new Object[4 + ActivityDayConfig.HOURS_PER_DAY];
        args[0] = config.activityId();
        args[1] = java.sql.Date.valueOf(config.utcDate());
        args[2] = config.dailyTotalCap();
        args[3] = config.updatedAtUtc();
        for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
            args[4 + h] = config.hourlyCap(h);
        }
        jdbc.update(sql.toString(), args);
    }

    /**
     * 按期望版本 CAS 修改配置并把版本 +1；仅当当前版本等于 expectedVersion 时生效。
     *
     * @return 是否更新成功；false 表示版本不匹配（并发修改或配置不存在）
     */
    public boolean compareAndSetUpdate(ActivityDayConfig config, int expectedVersion) {
        StringBuilder sql = new StringBuilder(
                "UPDATE activity_day_config SET daily_total_cap = ?, version = version + 1, updated_at_utc = ?");
        for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
            sql.append(", ").append(hourColumn(h)).append(" = ?");
        }
        sql.append(" WHERE activity_id = ? AND utc_date = ? AND version = ?");

        Object[] args = new Object[5 + ActivityDayConfig.HOURS_PER_DAY];
        args[0] = config.dailyTotalCap();
        args[1] = config.updatedAtUtc();
        for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
            args[2 + h] = config.hourlyCap(h);
        }
        args[2 + ActivityDayConfig.HOURS_PER_DAY] = config.activityId();
        args[3 + ActivityDayConfig.HOURS_PER_DAY] = java.sql.Date.valueOf(config.utcDate());
        args[4 + ActivityDayConfig.HOURS_PER_DAY] = expectedVersion;
        int rows = jdbc.update(sql.toString(), args);
        return rows == 1;
    }
}
