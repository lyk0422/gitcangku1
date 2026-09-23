package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Placement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 公告展示位表数据访问。展示位创建后不可修改或删除。
 */
@Repository
public class PlacementRepository {

    private final JdbcTemplate jdbc;

    public PlacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Placement> MAPPER = new RowMapper<>() {
        @Override
        public Placement mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Placement(
                    rs.getString("campaign_id"),
                    rs.getString("placement_code"),
                    rs.getInt("daily_cap"),
                    rs.getInt("config_version"),
                    rs.getLong("created_at_utc"));
        }
    };

    private static final String COLUMNS =
            "campaign_id, placement_code, daily_cap, config_version, created_at_utc";

    /** 插入展示位；(campaign_id, placement_code) 冲突由调用方处理。 */
    public void insert(Placement placement) {
        jdbc.update("INSERT INTO campaign_placement "
                        + "(campaign_id, placement_code, daily_cap, config_version, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                placement.campaignId(),
                placement.placementCode(),
                placement.dailyCap(),
                placement.configVersion(),
                placement.createdAtUtc());
    }

    /** 按公告与展示位编号查询。 */
    public Optional<Placement> findByCode(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_placement "
                                + "WHERE campaign_id = ? AND placement_code = ?",
                        MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /** 行锁读取展示位。 */
    public Optional<Placement> lockByCode(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_placement "
                                + "WHERE campaign_id = ? AND placement_code = ? FOR UPDATE",
                        MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /** 列出公告下全部展示位，按配置版本号升序。 */
    public List<Placement> findByCampaign(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_placement "
                        + "WHERE campaign_id = ? ORDER BY config_version",
                MAPPER, campaignId);
    }

    /** 统计公告下展示位数量；调用方须已持公告行锁以保证与新增操作串行。 */
    public int countByCampaign(String campaignId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM campaign_placement WHERE campaign_id = ?",
                Integer.class, campaignId);
        return count == null ? 0 : count;
    }
}
