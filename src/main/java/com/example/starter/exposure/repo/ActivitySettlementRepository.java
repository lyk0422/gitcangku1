package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动日结快照数据访问。快照行一经写入不再更新，
 * 后续配置变更不改写已结算日。
 */
@Repository
public class ActivitySettlementRepository {

    /**
     * 日结快照行视图。
     *
     * @param hourOfDay      小时序号，取值 0..23
     * @param confirmedCount 该小时确认数，单位次
     * @param cancelledCount 该小时取消数，单位次
     * @param expiredCount   该小时过期数，单位次
     * @param settledAtUtc   快照固化时刻（epoch 毫秒，UTC）
     */
    public record SettlementRow(int hourOfDay, int confirmedCount, int cancelledCount,
                                int expiredCount, long settledAtUtc) {
    }

    private static final RowMapper<SettlementRow> MAPPER = (rs, rowNum) -> new SettlementRow(
            rs.getInt("hour_of_day"),
            rs.getInt("confirmed_count"),
            rs.getInt("cancelled_count"),
            rs.getInt("expired_count"),
            rs.getLong("settled_at_utc"));

    private final JdbcTemplate jdbc;

    public ActivitySettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按小时升序读取活动某 UTC 日的快照行；未固化时返回空或部分行。 */
    public List<SettlementRow> findRows(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT hour_of_day, confirmed_count, cancelled_count, expired_count, settled_at_utc "
                        + "FROM activity_day_settlement "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY hour_of_day",
                MAPPER, activityId, java.sql.Date.valueOf(utcDate));
    }

    /** 固化某小时快照；已存在则跳过（并发固化结果一致，不产生主键冲突）。 */
    public void insertIfAbsent(String activityId, LocalDate utcDate, int hourOfDay,
                               int confirmedCount, int cancelledCount, int expiredCount, long settledAtUtc) {
        jdbc.update("INSERT INTO activity_day_settlement "
                        + "(activity_id, utc_date, hour_of_day, confirmed_count, cancelled_count, "
                        + "expired_count, settled_at_utc) "
                        + "SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_day_settlement "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay,
                confirmedCount, cancelledCount, expiredCount, settledAtUtc,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
    }
}
