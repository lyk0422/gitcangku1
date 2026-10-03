package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.CampaignActivity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 公告-活动归属数据访问。一公告至多一个活动；迁移即整行替换。
 */
@Repository
public class CampaignActivityRepository {

    private static final RowMapper<CampaignActivity> MAPPER = (rs, rowNum) -> new CampaignActivity(
            rs.getString("campaign_id"),
            rs.getString("activity_id"),
            rs.getLong("assigned_at_utc"));

    private final JdbcTemplate jdbc;

    public CampaignActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询公告当前所属活动；未归入返回 empty。 */
    public Optional<CampaignActivity> findByCampaign(String campaignId) {
        return jdbc.query("SELECT campaign_id, activity_id, assigned_at_utc "
                        + "FROM campaign_activity WHERE campaign_id = ?",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /** 首次归入活动。 */
    public void insert(CampaignActivity mapping) {
        jdbc.update("INSERT INTO campaign_activity (campaign_id, activity_id, assigned_at_utc) "
                        + "VALUES (?, ?, ?)",
                mapping.campaignId(), mapping.activityId(), mapping.assignedAtUtc());
    }

    /** 迁移到新活动；仅更新归属与时刻，既有预占单不改写。 */
    public void update(CampaignActivity mapping) {
        int rows = jdbc.update("UPDATE campaign_activity SET activity_id = ?, assigned_at_utc = ? "
                        + "WHERE campaign_id = ?",
                mapping.activityId(), mapping.assignedAtUtc(), mapping.campaignId());
        if (rows != 1) {
            throw new IllegalStateException("campaign_activity row missing for " + mapping.campaignId());
        }
    }
}