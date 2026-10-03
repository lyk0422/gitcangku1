package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Activity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 活动表数据访问。
 */
@Repository
public class ActivityRepository {

    private static final RowMapper<Activity> MAPPER = (rs, rowNum) -> new Activity(
            rs.getString("activity_id"),
            rs.getLong("created_at_utc"));

    private final JdbcTemplate jdbc;

    public ActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编号查询活动。 */
    public Optional<Activity> findById(String activityId) {
        return jdbc.query("SELECT activity_id, created_at_utc FROM activity WHERE activity_id = ?",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }

    /** 行锁读取活动，用于串行化配置修改。 */
    public Optional<Activity> lockById(String activityId) {
        return jdbc.query("SELECT activity_id, created_at_utc FROM activity "
                        + "WHERE activity_id = ? FOR UPDATE",
                        MAPPER, activityId)
                .stream()
                .findFirst();
    }
    /** 插入活动；编号冲突由调用方依据唯一约束处理。 */
    public void insert(Activity activity) {
        jdbc.update("INSERT INTO activity (activity_id, created_at_utc) VALUES (?, ?)",
                activity.activityId(), activity.createdAtUtc());
    }
}