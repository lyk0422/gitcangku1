package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结快照数据访问。快照一旦写入不再修改，后续配置变更不改写已结算日。
 */
@Repository
public class ActivitySnapshotRepository {

    /**
     * 日结快照单小时行视图。
     *
     * @param hourOfDay        UTC 小时，0-23
     * @param confirmedCount   该小时已确认数，单位次
     * @param cancelledCount   该小时已取消数，单位次
     * @param expiredCount     该小时已过期数，单位次
     * @param snapshottedAtUtc 快照固化时刻，epoch 毫秒，UTC
     */
    public record SnapshotHourRow(int hourOfDay, int confirmedCount, int cancelledCount,
                                  int expiredCount, long snapshottedAtUtc) {
    }

    private final JdbcTemplate jdbc;

    public ActivitySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询某 UTC 日已固化的快照行，按 hour_of_day 升序；未固化返回空列表。
     */
    public List<SnapshotHourRow> findByActivityAndDate(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT hour_of_day, confirmed_count, cancelled_count, expired_count, "
                        + "snapshotted_at_utc FROM activity_day_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY hour_of_day",
                (rs, rowNum) -> new SnapshotHourRow(
                        rs.getInt("hour_of_day"),
                        rs.getInt("confirmed_count"),
                        rs.getInt("cancelled_count"),
                        rs.getInt("expired_count"),
                        rs.getLong("snapshotted_at_utc")),
                activityId, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 固化单小时快照（不存在才插入）；并发日结由活动行锁串行化，重复调用不产生重复行。
     */
    public void insertIfAbsent(String activityId, LocalDate utcDate, int hourOfDay,
                               int confirmedCount, int cancelledCount, int expiredCount,
                               long snapshottedAtUtc) {
        jdbc.update("INSERT INTO activity_day_snapshot "
                        + "(activity_id, utc_date, hour_of_day, confirmed_count, cancelled_count, "
                        + "expired_count, snapshotted_at_utc) "
                        + "SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_day_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay,
                confirmedCount, cancelledCount, expiredCount, snapshottedAtUtc,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
    }
}
