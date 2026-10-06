package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 访客轮换序号账目数据访问。confirmed_count 为某访客在某公告下已确认的轮换次数，
 * 跨 UTC 日连续；仅确认轮换预占时 +1，取消/过期不推进。
 * 所有加锁/增减方法必须在事务内调用。
 */
@Repository
public class RotationStateRepository {

    private final JdbcTemplate jdbc;

    public RotationStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保账目行存在（不存在则以 0 创建）。 */
    public void ensureRow(String campaignId, String visitorId) {
        jdbc.update("INSERT INTO rotation_state (campaign_id, visitor_id, confirmed_count) "
                        + "SELECT ?, ?, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM rotation_state WHERE campaign_id = ? AND visitor_id = ?)",
                campaignId, visitorId, campaignId, visitorId);
    }

    /** 行锁读取已确认轮换次数；账目行不存在视为 0。 */
    public int lockConfirmedCount(String campaignId, String visitorId) {
        Integer value = jdbc.query("SELECT confirmed_count FROM rotation_state "
                        + "WHERE campaign_id = ? AND visitor_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, campaignId, visitorId);
        return value == null ? 0 : value;
    }

    /** 无锁读取已确认轮换次数；账目行不存在视为 0。 */
    public int getConfirmedCount(String campaignId, String visitorId) {
        Integer value = jdbc.query("SELECT confirmed_count FROM rotation_state "
                        + "WHERE campaign_id = ? AND visitor_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, campaignId, visitorId);
        return value == null ? 0 : value;
    }

    /** 推进一次已确认轮换序号（+1）。调用方须已持行锁。 */
    public void incrementConfirmedCount(String campaignId, String visitorId) {
        int rows = jdbc.update("UPDATE rotation_state SET confirmed_count = confirmed_count + 1 "
                        + "WHERE campaign_id = ? AND visitor_id = ?",
                campaignId, visitorId);
        if (rows != 1) {
            throw new IllegalStateException("rotation state row missing for "
                    + campaignId + " " + visitorId);
        }
    }
}
