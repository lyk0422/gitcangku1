package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 公告表数据访问。加锁/版本方法必须在事务内调用。
 */
@Repository
public class CampaignRepository {

    private final JdbcTemplate jdbc;

    public CampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "campaign_id, daily_total_cap, per_visitor_daily_cap, version, created_at_utc";

    private static final RowMapper<Campaign> MAPPER = new RowMapper<>() {
        @Override
        public Campaign mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Campaign(
                    rs.getString("campaign_id"),
                    rs.getInt("daily_total_cap"),
                    rs.getInt("per_visitor_daily_cap"),
                    rs.getLong("version"),
                    rs.getLong("created_at_utc"));
        }
    };

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
     * 行锁读取公告；用于序列化曝光裁决与抑制名单变更（按事务提交顺序裁决）。
     */
    public Optional<Campaign> lockById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ? FOR UPDATE",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。初始版本为 0。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.version(),
                campaign.createdAtUtc());
    }

    /**
     * 活动版本 +1（无条件）。调用方须已持公告行锁。
     */
    public void bumpVersion(String campaignId) {
        int rows = jdbc.update("UPDATE campaign SET version = version + 1 WHERE campaign_id = ?",
                campaignId);
        if (rows != 1) {
            throw new IllegalStateException("campaign row missing for " + campaignId);
        }
    }

    /**
     * 乐观锁版本递增：仅当当前版本等于 expectedVersion 时 +1。
     *
     * @return 是否递增成功；false 表示版本不匹配（调用方应返回 409）
     */
    public boolean compareAndBumpVersion(String campaignId, long expectedVersion) {
        int rows = jdbc.update("UPDATE campaign SET version = version + 1 "
                        + "WHERE campaign_id = ? AND version = ?",
                campaignId, expectedVersion);
        return rows == 1;
    }
}
