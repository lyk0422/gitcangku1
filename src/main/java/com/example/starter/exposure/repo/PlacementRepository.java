package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Placement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 展示位表数据访问。展示位创建后不可修改、不可删除，故仅提供插入与只读查询。
 * 加锁/计数方法必须在事务内调用，与公告行锁配合实现最多 20 个唯一 code 与
 * configVersion 竞争控制。
 */
@Repository
public class PlacementRepository {

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

    /** 插入展示位；(campaign_id, placement_code) 冲突由调用方按唯一约束处理。 */
    public void insert(Placement placement) {
        jdbc.update("INSERT INTO placement (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                placement.campaignId(),
                placement.placementCode(),
                placement.dailyCap(),
                placement.configVersion(),
                placement.createdAtUtc());
    }

    /** 按公告 + 展示位编号查询。 */
    public Optional<Placement> findById(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                                + "WHERE campaign_id = ? AND placement_code = ?",
                        MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /** 行锁读取展示位，用于申请时校验展示位存在。 */
    public Optional<Placement> lockById(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                                + "WHERE campaign_id = ? AND placement_code = ? FOR UPDATE",
                        MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /** 列出某公告下全部展示位（按配置版本升序）。 */
    public List<Placement> findByCampaign(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                        + "WHERE campaign_id = ? ORDER BY config_version",
                MAPPER, campaignId);
    }

    /** 统计某公告当前展示位数量；调用方须持有公告行锁以使计数对并发新增稳定。 */
    public int countByCampaign(String campaignId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM placement WHERE campaign_id = ?",
                Integer.class, campaignId);
        return value == null ? 0 : value;
    }
}
