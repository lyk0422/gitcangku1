package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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

    private static final String COLUMNS =
            "campaign_id, daily_total_cap, per_visitor_daily_cap, cooldown_minutes, version, created_at_utc";

    private static final RowMapper<Campaign> MAPPER = new RowMapper<>() {
        @Override
        public Campaign mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Campaign(
                    rs.getString("campaign_id"),
                    rs.getInt("daily_total_cap"),
                    rs.getInt("per_visitor_daily_cap"),
                    rs.getInt("cooldown_minutes"),
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
     * 行锁读取公告；冷却配置修改等需要与申请互斥裁决的场景使用。
     */
    public Optional<Campaign> lockById(String campaignId) {
        List<Campaign> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ? FOR UPDATE",
                MAPPER, campaignId);
        return list.stream().findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。冷却分钟数缺省由调用方给定，版本固定为 0。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign "
                        + "(campaign_id, daily_total_cap, per_visitor_daily_cap, cooldown_minutes, "
                        + "version, created_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.cooldownMinutes(),
                campaign.version(),
                campaign.createdAtUtc());
    }

    /**
     * 乐观锁修改冷却分钟数：仅当 version 等于 expectedVersion 时更新，version +1。
     *
     * @return 是否更新成功；false 表示版本冲突（409）
     */
    public boolean compareAndUpdateCooldown(String campaignId, long expectedVersion,
                                            int cooldownMinutes) {
        int rows = jdbc.update("UPDATE campaign SET cooldown_minutes = ?, version = version + 1 "
                        + "WHERE campaign_id = ? AND version = ?",
                cooldownMinutes, campaignId, expectedVersion);
        return rows == 1;
    }
}
