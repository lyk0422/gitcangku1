package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
        public Campaign mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new Campaign(
                    rs.getString("campaign_id"),
                    rs.getInt("daily_total_cap"),
                    rs.getInt("per_visitor_daily_cap"),
                    rs.getInt("version"),
                    rs.getLong("created_at_utc"));
        }
    };

    private static final String COLUMNS =
            "campaign_id, daily_total_cap, per_visitor_daily_cap, version, created_at_utc";

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ?",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取公告（含当前版本号），必须在事务内调用。
     */
    public Optional<Campaign> lockById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ? FOR UPDATE",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。版本号初始为 0。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign "
                        + "(campaign_id, daily_total_cap, per_visitor_daily_cap, version, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.version(),
                campaign.createdAtUtc());
    }

    /**
     * 公告版本号 CAS：仅当当前版本为 expect 时更新为 target。
     *
     * @return 是否更新成功；false 表示名单被并发事务先行变更（版本不匹配）
     */
    public boolean compareAndSetVersion(String campaignId, int expect, int target) {
        int rows = jdbc.update("UPDATE campaign SET version = ? WHERE campaign_id = ? AND version = ?",
                target, campaignId, expect);
        return rows == 1;
    }
}
