package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.VisitorCooldown;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客冷却状态数据访问。加锁方法必须在事务内调用。
 */
@Repository
public class CooldownRepository {

    private static final RowMapper<VisitorCooldown> MAPPER = (rs, rowNum) -> new VisitorCooldown(
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getLong("last_confirmed_at_utc"));

    private final JdbcTemplate jdbc;

    public CooldownRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询访客对公告的冷却状态；无行表示从未确认。 */
    public Optional<VisitorCooldown> find(String campaignId, String visitorId) {
        List<VisitorCooldown> list = jdbc.query(
                "SELECT campaign_id, visitor_id, last_confirmed_at_utc "
                        + "FROM visitor_campaign_cooldown WHERE campaign_id = ? AND visitor_id = ?",
                MAPPER, campaignId, visitorId);
        return list.stream().findFirst();
    }

    /**
     * 行锁查询冷却状态，用于申请阶段与并发确认互斥；无行表示从未确认。
     */
    public Optional<VisitorCooldown> lockForUpdate(String campaignId, String visitorId) {
        List<VisitorCooldown> list = jdbc.query(
                "SELECT campaign_id, visitor_id, last_confirmed_at_utc "
                        + "FROM visitor_campaign_cooldown WHERE campaign_id = ? AND visitor_id = ? FOR UPDATE",
                MAPPER, campaignId, visitorId);
        return list.stream().findFirst();
    }

    /**
     * 插入或更新最近确认时刻（确认成功时调用，同事务）。
     * 先确保行存在再 UPDATE，使并发首次确认序列化在该行锁上。
     */
    public void upsertLastConfirmed(String campaignId, String visitorId, long confirmedAtUtc) {
        jdbc.update("INSERT INTO visitor_campaign_cooldown "
                        + "(campaign_id, visitor_id, last_confirmed_at_utc) "
                        + "SELECT ?, ?, ? WHERE NOT EXISTS ("
                        + "SELECT 1 FROM visitor_campaign_cooldown "
                        + "WHERE campaign_id = ? AND visitor_id = ?)",
                campaignId, visitorId, confirmedAtUtc, campaignId, visitorId);
        jdbc.update("UPDATE visitor_campaign_cooldown SET last_confirmed_at_utc = ? "
                        + "WHERE campaign_id = ? AND visitor_id = ?",
                confirmedAtUtc, campaignId, visitorId);
    }
}
