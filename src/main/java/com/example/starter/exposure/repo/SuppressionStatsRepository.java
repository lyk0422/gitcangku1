package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 静默抑制统计数据访问：按公告、访客与 UTC 日累计抑制次数。
 * 递增方法必须在事务内调用。
 */
@Repository
public class SuppressionStatsRepository {

    private final JdbcTemplate jdbc;

    public SuppressionStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取某公告对某访客某 UTC 日的累计抑制次数；无记录为 0。 */
    public long getCount(String campaignId, String visitorId, LocalDate utcDate) {
        Long value = jdbc.query("SELECT suppressed_count FROM suppression_stats "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0L : value;
    }

    /** 确保当日统计行存在（不存在则以 0 创建），须在事务内调用。 */
    public void ensureRow(String campaignId, String visitorId, LocalDate utcDate) {
        jdbc.update("INSERT INTO suppression_stats (campaign_id, visitor_id, utc_date, suppressed_count) "
                        + "SELECT ?, ?, ?, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM suppression_stats "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?)",
                campaignId, visitorId, java.sql.Date.valueOf(utcDate),
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
    }

    /** 抑制次数 +1；调用前须先 {@link #ensureRow} 建行。 */
    public void increment(String campaignId, String visitorId, LocalDate utcDate) {
        int rows = jdbc.update("UPDATE suppression_stats SET suppressed_count = suppressed_count + 1 "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        if (rows != 1) {
            throw new IllegalStateException("suppression stats row missing for "
                    + campaignId + " " + visitorId + " " + utcDate);
        }
    }
}
