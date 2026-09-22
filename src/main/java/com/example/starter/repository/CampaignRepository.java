package com.example.starter.repository;

import java.util.Optional;

import com.example.starter.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 公告表数据访问。
 */
@Repository
public class CampaignRepository {

    private static final RowMapper<Campaign> MAPPER = (rs, n) -> {
        Campaign c = new Campaign();
        c.setId(rs.getLong("id"));
        c.setCampaignId(rs.getString("campaign_id"));
        c.setDailyTotalCap(rs.getInt("daily_total_cap"));
        c.setVisitorCap(rs.getInt("visitor_cap"));
        c.setCreatedAt(rs.getLong("created_at"));
        return c;
    };

    private final JdbcTemplate jdbc;

    public CampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Campaign campaign) {
        jdbc.update("""
                INSERT INTO campaign (campaign_id, daily_total_cap, visitor_cap, created_at)
                VALUES (?, ?, ?, ?)
                """,
                campaign.getCampaignId(), campaign.getDailyTotalCap(),
                campaign.getVisitorCap(), campaign.getCreatedAt());
    }

    public Optional<Campaign> findByCampaignId(String campaignId) {
        return jdbc.query("SELECT * FROM campaign WHERE campaign_id = ?", MAPPER, campaignId)
                .stream().findFirst();
    }
}
