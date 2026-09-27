package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Activity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 活动表数据访问。加锁方法必须在事务内调用。
 */
@Repository
public class ActivityRepository {

    private final JdbcTemplate jdbc;

    public ActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Activity> MAPPER = (rs, rowNum) -> new Activity(
            rs.getString("activity_id"),
            rs.getInt("version"),
            rs.getLong("created_at_utc"));

    private static final String COLUMNS = "activity_id, version, created_at_utc";

    public Optional<Activity> findById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ?",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取活动；用于串行化预算配置变更与日结固化。
     */
    public Optional<Activity> lockById(String activityId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity WHERE activity_id = ? FOR UPDATE",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /**
     * 插入活动；编号冲突由调用方依据唯一约束处理。初始版本号 0。
     */
    public void insert(Activity activity) {
        jdbc.update("INSERT INTO activity (" + COLUMNS + ") VALUES (?, ?, ?)",
                activity.activityId(), activity.version(), activity.createdAtUtc());
    }

    /**
     * 配置版本号 +1。调用方须已持活动行锁并校验 expectedVersion 与当前版本一致。
     */
    public void incrementVersion(String activityId) {
        int rows = jdbc.update("UPDATE activity SET version = version + 1 WHERE activity_id = ?",
                activityId);
        if (rows != 1) {
            throw new IllegalStateException("activity row missing for " + activityId);
        }
    }
}
