package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.CampaignWindowVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 公告归因窗口版本历史数据访问。版本记录只追加、不修改、不删除。
 */
@Repository
public class CampaignWindowVersionRepository {

    private final JdbcTemplate jdbc;

    public CampaignWindowVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CampaignWindowVersion> MAPPER = (rs, rowNum) ->
            new CampaignWindowVersion(
                    rs.getString("campaign_id"),
                    rs.getInt("version"),
                    rs.getInt("attribution_window_minutes"),
                    rs.getString("operator_id"),
                    rs.getLong("created_at_utc"));

    /**
     * 追加一个窗口版本。
     */
    public void insert(CampaignWindowVersion version) {
        jdbc.update("INSERT INTO campaign_window_version "
                        + "(campaign_id, version, attribution_window_minutes, operator_id, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                version.campaignId(),
                version.version(),
                version.attributionWindowMinutes(),
                version.operatorId(),
                version.createdAtUtc());
    }

    /**
     * 按版本号升序返回公告全部窗口版本，排序稳定。
     */
    public List<CampaignWindowVersion> findByCampaignOrdered(String campaignId) {
        return jdbc.query("SELECT campaign_id, version, attribution_window_minutes, operator_id, created_at_utc "
                        + "FROM campaign_window_version WHERE campaign_id = ? ORDER BY version ASC",
                MAPPER, campaignId);
    }
}
