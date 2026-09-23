package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 公告表数据访问。加锁方法必须在事务内调用：新增展示位与申请并发时，
 * 通过对公告行加锁并按 config_version 做条件更新来保证配置版本严格递增。
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
     * 行锁读取公告；配置/申请竞争的事务内使用。
     */
    public Optional<Campaign> lockById(String campaignId) {
        return jdbc.query("SELECT campaign_id, daily_total_cap, per_visitor_daily_cap, "
                        + "config_version, created_at_utc FROM campaign WHERE campaign_id = ? FOR UPDATE",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。初始 config_version 固定为 1。
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
     * 条件递增配置版本：仅当当前版本等于 expectedConfigVersion 时 +1。
     *
     * @return 是否更新成功；false 表示公告配置已被其他事务变更（版本不匹配）
     */
    public boolean compareAndIncrementConfigVersion(String campaignId, int expectedConfigVersion) {
        int rows = jdbc.update("UPDATE campaign SET config_version = config_version + 1 "
                        + "WHERE campaign_id = ? AND config_version = ?",
                campaignId, expectedConfigVersion);
        return rows == 1;
    }
}
