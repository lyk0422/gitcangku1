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
                    rs.getLong("created_at_utc"),
                    rs.getString("activity_id"));
        }
    };

    private static final String COLUMNS =
            "campaign_id, daily_total_cap, per_visitor_daily_cap, created_at_utc, activity_id";

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ?", MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取公告；不存在返回 empty。必须在事务内调用。
     */
    public Optional<Campaign> lockById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ? FOR UPDATE",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc(),
                campaign.activityId());
    }

    /**
     * 迁移公告所属活动；只影响迁移后的新预占，既有预占固化原归属。
     */
    public void updateActivityId(String campaignId, String activityId) {
        int rows = jdbc.update("UPDATE campaign SET activity_id = ? WHERE campaign_id = ?",
                activityId, campaignId);
        if (rows != 1) {
            throw new IllegalStateException("campaign row missing for " + campaignId);
        }
    }
}
