package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
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
                    rs.getInt("daily_total_cap"),
                    rs.getInt("per_visitor_daily_cap"),
                    rs.getString("channel_key"),
                    rs.getLong("created_at_utc"));
        }
    };

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT campaign_id, daily_total_cap, per_visitor_daily_cap, channel_key, created_at_utc "
                        + "FROM campaign WHERE campaign_id = ?", MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取公告；渠道迁移等写路径使用，必须在事务内调用。
     */
    public Optional<Campaign> lockById(String campaignId) {
        return jdbc.query("SELECT campaign_id, daily_total_cap, per_visitor_daily_cap, channel_key, created_at_utc "
                        + "FROM campaign WHERE campaign_id = ? FOR UPDATE", MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, channel_key, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.channelKey(),
                campaign.createdAtUtc());
    }

    /**
     * 迁移公告归属渠道；只影响迁移后的新申请，既有预占按固化渠道结算。
     *
     * @param channelKey 新渠道编号；null 表示迁出渠道（不再受渠道频控）
     */
    public void updateChannelKey(String campaignId, String channelKey) {
        int rows = jdbc.update("UPDATE campaign SET channel_key = ? WHERE campaign_id = ?",
                channelKey, campaignId);
        if (rows != 1) {
            throw new IllegalStateException("campaign row missing for " + campaignId);
        }
    }
}
