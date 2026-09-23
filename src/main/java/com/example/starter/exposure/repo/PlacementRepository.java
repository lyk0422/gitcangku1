package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Placement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 展示位表数据访问。展示位只增不改不删；加锁方法必须在事务内调用，
 * 调用方须先持公告行锁（见 {@link CampaignRepository#lockById}）以串行化配置变更。
 */
@Repository
public class PlacementRepository {

    /** 含 DEFAULT 在内的每公告展示位数量上限。 */
    public static final int MAX_PLACEMENTS_PER_CAMPAIGN = 20;

    private final JdbcTemplate jdbc;

    public PlacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Placement> MAPPER = (rs, rowNum) -> new Placement(
            rs.getString("campaign_id"),
            rs.getString("placement_code"),
            rs.getInt("daily_cap"),
            rs.getInt("config_version"),
            rs.getLong("created_at_utc"));

    private static final String COLUMNS =
            "campaign_id, placement_code, daily_cap, config_version, created_at_utc";

    /** 插入展示位；(campaignId, placementCode) 冲突由唯一约束抛出。 */
    public void insert(Placement placement) {
        jdbc.update("INSERT INTO placement (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                placement.campaignId(),
                placement.placementCode(),
                placement.dailyCap(),
                placement.configVersion(),
                placement.createdAtUtc());
    }

    /** 普通读取单个展示位，不存在返回 empty。 */
    public Optional<Placement> findById(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                        + "WHERE campaign_id = ? AND placement_code = ?",
                MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /** 行锁读取单个展示位。 */
    public Optional<Placement> lockById(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                        + "WHERE campaign_id = ? AND placement_code = ? FOR UPDATE",
                MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /** 查询公告下全部展示位（含 DEFAULT），按 config_version 升序。 */
    public List<Placement> findByCampaign(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                        + "WHERE campaign_id = ? ORDER BY config_version",
                MAPPER, campaignId);
    }

    /** 行锁读取公告下全部展示位（调用方已持公告行锁），按 config_version 升序。 */
    public List<Placement> lockByCampaign(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                        + "WHERE campaign_id = ? ORDER BY config_version FOR UPDATE",
                MAPPER, campaignId);
    }

    /** 统计公告下展示位总数（含 DEFAULT）。 */
    public int countByCampaign(String campaignId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM placement WHERE campaign_id = ?",
                Integer.class, campaignId);
        return count == null ? 0 : count;
    }
}
