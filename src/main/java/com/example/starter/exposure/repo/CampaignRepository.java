package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 公告表数据访问。公告的有序轮换创意存于 campaign_creative，
 * 按 position 升序读出即轮换顺序。
 */
@Repository
public class CampaignRepository {

    private final JdbcTemplate jdbc;

    public CampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按编号查询公告（含固定有序创意列表）。
     */
    public Optional<Campaign> findById(String campaignId) {
        record CampaignRow(String campaignId, int dailyTotalCap, int perVisitorDailyCap, long createdAtUtc) {
        }
        List<CampaignRow> rows = jdbc.query(
                "SELECT campaign_id, daily_total_cap, per_visitor_daily_cap, created_at_utc "
                        + "FROM campaign WHERE campaign_id = ?",
                (rs, rowNum) -> new CampaignRow(
                        rs.getString("campaign_id"),
                        rs.getInt("daily_total_cap"),
                        rs.getInt("per_visitor_daily_cap"),
                        rs.getLong("created_at_utc")),
                campaignId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        CampaignRow row = rows.get(0);
        return Optional.of(new Campaign(
                row.campaignId(),
                row.dailyTotalCap(),
                row.perVisitorDailyCap(),
                row.createdAtUtc(),
                findCreativeIds(campaignId)));
    }

    /** 按轮换顺序读取公告固定创意编号列表；未配置轮换返回空列表。 */
    public List<String> findCreativeIds(String campaignId) {
        return jdbc.query("SELECT creative_id FROM campaign_creative "
                        + "WHERE campaign_id = ? ORDER BY position ASC",
                (rs, rowNum) -> rs.getString("creative_id"),
                campaignId);
    }

    /**
     * 插入公告及其有序创意；编号冲突由调用方依据唯一约束处理。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, created_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc());
        for (int position = 0; position < campaign.creativeIds().size(); position++) {
            jdbc.update("INSERT INTO campaign_creative (campaign_id, position, creative_id) VALUES (?, ?, ?)",
                    campaign.campaignId(), position, campaign.creativeIds().get(position));
        }
    }
}
