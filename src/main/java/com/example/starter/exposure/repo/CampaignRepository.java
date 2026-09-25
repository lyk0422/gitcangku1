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
                    rs.getString("category"),
                    rs.getInt("version"),
                    rs.getInt("silence_start_sec"),
                    rs.getInt("silence_end_sec"),
                    rs.getLong("min_interval_millis"),
                    rs.getLong("created_at_utc"));
        }
    };

    private static final String COLUMNS =
            "campaign_id, daily_total_cap, per_visitor_daily_cap, category, version, "
                    + "silence_start_sec, silence_end_sec, min_interval_millis, created_at_utc";

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ?",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /** 行锁读取公告（类别修改等场景串行化）。 */
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
        jdbc.update("INSERT INTO campaign (campaign_id, daily_total_cap, per_visitor_daily_cap, "
                        + "category, version, silence_start_sec, silence_end_sec, "
                        + "min_interval_millis, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.category(),
                campaign.version(),
                campaign.silenceStartSec(),
                campaign.silenceEndSec(),
                campaign.minIntervalMillis(),
                campaign.createdAtUtc());
    }

    /**
     * CAS 修改活动类别并将活动版本 +1；不同步迁移任何旧同意。
     *
     * @return 是否更新成功（并发修改竞争时为 false）
     */
    public boolean updateCategory(String campaignId, String newCategory, int expectedVersion) {
        int rows = jdbc.update("UPDATE campaign SET category = ?, version = version + 1 "
                        + "WHERE campaign_id = ? AND version = ?",
                newCategory, campaignId, expectedVersion);
        return rows == 1;
    }
}
