package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 公告表数据访问。加锁方法必须在事务内调用。
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
                    rs.getString("activity_id"),
                    rs.getInt("daily_total_cap"),
                    rs.getInt("per_visitor_daily_cap"),
                    rs.getLong("created_at_utc"));
        }
    };

    private static final String COLUMNS =
            "campaign_id, activity_id, daily_total_cap, per_visitor_daily_cap, created_at_utc";

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ?",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /** 行锁读取公告（迁移/申请加锁顺序的起点）。 */
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
        jdbc.update("INSERT INTO campaign "
                        + "(campaign_id, activity_id, daily_total_cap, per_visitor_daily_cap, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.activityId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc());
    }

    /**
     * 活动迁移：把公告当前归属活动改为 newActivityId（可为 null 表示移出活动）。
     * 只影响迁移之后的新预占，既有预占不迁移。
     */
    public void updateActivity(String campaignId, String newActivityId) {
        int rows = jdbc.update("UPDATE campaign SET activity_id = ? WHERE campaign_id = ?",
                newActivityId, campaignId);
        if (rows != 1) {
            throw new IllegalStateException("campaign row missing: " + campaignId);
        }
    }
}
