package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.CampaignCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 公告表数据访问。
 */
@Repository
public class CampaignRepository {

    private final JdbcTemplate jdbc;

    public CampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Campaign> MAPPER = new RowMapper<>() {
        @Override
        public Campaign mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Campaign(
                    rs.getString("campaign_id"),
                    CampaignCategory.valueOf(rs.getString("category")),
                    rs.getInt("daily_total_cap"),
                    rs.getInt("per_visitor_daily_cap"),
                    rs.getLong("created_at_utc"));
        }
    };

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT campaign_id, category, daily_total_cap, per_visitor_daily_cap, created_at_utc "
                        + "FROM campaign WHERE campaign_id = ?", MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign "
                        + "(campaign_id, category, daily_total_cap, per_visitor_daily_cap, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.category().name(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc());
    }
}
