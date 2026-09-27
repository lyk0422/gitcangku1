package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动小时日结快照数据访问。日结查询首次固化往日每小时确认数与取消数；
 * 快照一旦写入不再改写，后续配置变更、公告迁移都不影响已结算日。
 */
@Repository
public class ActivityHourSnapshotRepository {

    /** 小时快照视图。计数单位均为次。 */
    public record HourSnapshot(
            String activityId,
            LocalDate utcDate,
            int utcHour,
            int confirmedCount,
            int cancelledCount,
            long settledAtUtc) {
    }

    private static final String COLUMNS =
            "activity_id, utc_date, utc_hour, confirmed_count, cancelled_count, settled_at_utc";

    private final JdbcTemplate jdbc;

    public ActivityHourSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 只读列出活动某 UTC 日全部 24 小时快照（含未发生小时的全 0 行由服务层补齐），按小时升序。 */
    public List<HourSnapshot> listByDay(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_hour_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY utc_hour",
                (rs, rowNum) -> new HourSnapshot(
                        rs.getString("activity_id"),
                        rs.getDate("utc_date").toLocalDate(),
                        rs.getInt("utc_hour"),
                        rs.getInt("confirmed_count"),
                        rs.getInt("cancelled_count"),
                        rs.getLong("settled_at_utc")),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /** 活动某 UTC 日是否已固化过快照。 */
    public boolean existsByDay(String activityId, LocalDate utcDate) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM activity_hour_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ?",
                Integer.class, activityId, java.sql.Date.valueOf(utcDate));
        return count != null && count > 0;
    }

    /**
     * 插入某小时快照；若该（活动，日，小时）快照已存在则不写入、不改写。
     * 往日账目已不再变化，并发首次固化时后到者与先到者固化值等价。
     */
    public void insertIfAbsent(HourSnapshot snapshot) {
        jdbc.update("INSERT INTO activity_hour_snapshot "
                        + "(activity_id, utc_date, utc_hour, confirmed_count, cancelled_count, settled_at_utc) "
                        + "SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_hour_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ?)",
                snapshot.activityId(),
                java.sql.Date.valueOf(snapshot.utcDate()),
                snapshot.utcHour(),
                snapshot.confirmedCount(),
                snapshot.cancelledCount(),
                snapshot.settledAtUtc(),
                snapshot.activityId(),
                java.sql.Date.valueOf(snapshot.utcDate()),
                snapshot.utcHour());
    }

}
