package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.CampaignCategory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

/**
 * 静默抑制计数数据访问：按公告、访客与 UTC 日累计 +1，按日查询累计次数。
 * 计数写入与申请事务原子提交，保证并发抑制计数不丢。
 */
@Repository
public class SuppressionCounterRepository {

    private final JdbcTemplate jdbc;

    public SuppressionCounterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询当日某公告某访客的抑制次数；账目行不存在视为 0。 */
    public int getCount(String campaignId, String visitorId, LocalDate utcDate) {
        Integer value = jdbc.query("SELECT suppressed_count FROM suppression_counter "
                        + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                campaignId, visitorId, java.sql.Date.valueOf(utcDate));
        return value == null ? 0 : value;
    }

    /**
     * 一次抑制计数 +1：行已存在则 UPDATE +1；不存在则插入 1。
     * 仅使用标准 UPDATE/INSERT；并发首撞唯一键时等待对方落定后在 UPDATE/INSERT 间有限重试，
     * 对方提交则追加计数，对方回滚则自行插入。
     */
    public void increment(String campaignId, String visitorId, LocalDate utcDate,
                          CampaignCategory category) {
        java.sql.Date date = java.sql.Date.valueOf(utcDate);
        for (int attempt = 0; attempt < 5; attempt++) {
            int rows = jdbc.update("UPDATE suppression_counter SET suppressed_count = suppressed_count + 1 "
                            + "WHERE campaign_id = ? AND visitor_id = ? AND utc_date = ?",
                    campaignId, visitorId, date);
            if (rows == 1) {
                return;
            }
            try {
                jdbc.update("INSERT INTO suppression_counter "
                                + "(campaign_id, visitor_id, utc_date, category, suppressed_count) "
                                + "VALUES (?, ?, ?, ?, 1)",
                        campaignId, visitorId, date, category.name());
                return;
            } catch (DuplicateKeyException concurrentFirstInsert) {
                // 并发事务抢先插入（可能尚未提交），进入下一轮：其提交则 UPDATE 命中，回滚则 INSERT 成功
            }
        }
        throw new IllegalStateException("failed to increment suppression counter after retries: "
                + campaignId + "/" + visitorId + "/" + utcDate);
    }
}
