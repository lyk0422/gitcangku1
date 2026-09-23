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
                    rs.getInt("config_version"),
                    rs.getLong("created_at_utc"));
        }
    };

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT campaign_id, daily_total_cap, per_visitor_daily_cap, "
                        + "config_version, created_at_utc FROM campaign WHERE campaign_id = ?",
                MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取公告。新增展示位等配置变更须持此锁，以与按展示位申请的事务按提交顺序串行化。
     */
    public Optional<Campaign> lockById(String campaignId) {
        return jdbc.query("SELECT campaign_id, daily_total_cap, per_visitor_daily_cap, "
                        + "config_version, created_at_utc FROM campaign WHERE campaign_id = ? FOR UPDATE",
                MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；config_version 固定从 1 开始（同步创建 DEFAULT 展示位）。
     * 编号冲突由调用方依据唯一约束处理。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, "
                        + "config_version, created_at_utc) VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.configVersion(),
                campaign.createdAtUtc());
    }

    /**
     * 将公告配置版本递增到指定值（调用方已持公告行锁）。
     */
    public void updateConfigVersion(String campaignId, int newConfigVersion) {
        int rows = jdbc.update("UPDATE campaign SET config_version = ? WHERE campaign_id = ?",
                newConfigVersion, campaignId);
        if (rows != 1) {
            throw new IllegalStateException("campaign row missing: " + campaignId);
        }
    }
}
