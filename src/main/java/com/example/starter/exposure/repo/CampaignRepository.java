package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.CampaignWindow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 公告表数据访问，含归因窗口版本历史。加锁方法必须在事务内调用。
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
                    rs.getInt("attribution_window_minutes"),
                    rs.getInt("window_version"),
                    rs.getLong("created_at_utc"));
        }
    };

    private static final RowMapper<CampaignWindow> WINDOW_MAPPER = (rs, rowNum) -> new CampaignWindow(
            rs.getString("campaign_id"),
            rs.getInt("window_version"),
            rs.getInt("window_minutes"),
            rs.getLong("changed_at_utc"));

    private static final String COLUMNS = "campaign_id, daily_total_cap, per_visitor_daily_cap, "
            + "attribution_window_minutes, window_version, created_at_utc";

    /**
     * 按编号查询公告。
     */
    public Optional<Campaign> findById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign WHERE campaign_id = ?", MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取公告；用于窗口调整等需要串行化的写操作。
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
        jdbc.update("INSERT INTO campaign (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.dailyTotalCap(),
                campaign.perVisitorDailyCap(),
                campaign.attributionWindowMinutes(),
                campaign.windowVersion(),
                campaign.createdAtUtc());
    }

    /**
     * 调整当前生效的归因窗口并提升版本号；历史版本行不受影响。
     */
    public void updateWindow(String campaignId, int windowMinutes, int newWindowVersion) {
        int rows = jdbc.update("UPDATE campaign SET attribution_window_minutes = ?, window_version = ? "
                        + "WHERE campaign_id = ?",
                windowMinutes, newWindowVersion, campaignId);
        if (rows != 1) {
            throw new IllegalStateException("campaign row missing for " + campaignId);
        }
    }

    /**
     * 追加一条窗口版本历史（不可变，版本号唯一）。
     */
    public void insertWindowHistory(CampaignWindow window) {
        jdbc.update("INSERT INTO campaign_window_history "
                        + "(campaign_id, window_version, window_minutes, changed_at_utc) VALUES (?, ?, ?, ?)",
                window.campaignId(),
                window.windowVersion(),
                window.windowMinutes(),
                window.changedAtUtc());
    }

    /**
     * 按版本号升序查询窗口版本历史（只读，排序稳定）。
     */
    public List<CampaignWindow> listWindowHistory(String campaignId) {
        return jdbc.query("SELECT campaign_id, window_version, window_minutes, changed_at_utc "
                        + "FROM campaign_window_history WHERE campaign_id = ? ORDER BY window_version ASC",
                WINDOW_MAPPER, campaignId);
    }
}
