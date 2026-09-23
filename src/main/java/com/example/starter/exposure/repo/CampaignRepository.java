package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 公告表数据访问。加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class CampaignRepository {

    private final JdbcTemplate jdbc;

    public CampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "campaign_id, daily_total_cap, per_visitor_daily_cap, created_at_utc, "
                    + "current_version, withdrawn_at_utc";

    private static final RowMapper<Campaign> MAPPER = new RowMapper<>() {
        @Override
        public Campaign mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Campaign(
                    rs.getString("campaign_id"),
                    rs.getInt("daily_total_cap"),
                    rs.getInt("per_visitor_daily_cap"),
                    rs.getLong("created_at_utc"),
                    rs.getInt("current_version"),
                    (Long) rs.getObject("withdrawn_at_utc"));
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
     * 行锁查询公告。撤回、结算等需要与新预占互斥的操作使用。
     */
    public Optional<Campaign> lockById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ? FOR UPDATE",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 插入公告；编号冲突由调用方依据唯一约束处理。current_version 默认 1，未撤回。
     */
    public void insert(Campaign campaign) {
        jdbc.update("INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, "
                        + "created_at_utc, current_version, withdrawn_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.createdAtUtc(),
                campaign.currentVersion(),
                campaign.withdrawnAtUtc());
    }

    /**
     * 标记撤回：仅当公告仍未撤回时写入撤回时刻（版本仅用于受理前的乐观校验，不自增）。
     *
     * @return 是否更新成功；false 表示已被并发撤回
     */
    public boolean markWithdrawn(String campaignId, long withdrawnAtUtc) {
        int rows = jdbc.update("UPDATE campaign SET withdrawn_at_utc = ? "
                        + "WHERE campaign_id = ? AND withdrawn_at_utc IS NULL",
                withdrawnAtUtc, campaignId);
        return rows == 1;
    }
}
