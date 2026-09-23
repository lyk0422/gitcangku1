package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Placement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 展示位表数据访问。展示位创建后不可修改、不可删除；加锁方法必须在事务内调用。
 */
@Repository
public class PlacementRepository {

    private static final String COLUMNS =
            "campaign_id, placement_code, daily_cap, config_version, created_at_utc";

    private static final RowMapper<Placement> MAPPER = (rs, rowNum) -> new Placement(
            rs.getString("campaign_id"),
            rs.getString("placement_code"),
            rs.getInt("daily_cap"),
            rs.getInt("config_version"),
            rs.getLong("created_at_utc"));

    private final JdbcTemplate jdbc;

    public PlacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入展示位；公告内编号冲突由唯一约束抛出异常，调用方转为 409。
     */
    public void insert(Placement placement) {
        jdbc.update("INSERT INTO placement (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?)",
                placement.campaignId(),
                placement.placementCode(),
                placement.dailyCap(),
                placement.configVersion(),
                placement.createdAtUtc());
    }

    /**
     * 普通读取公告下指定展示位。
     */
    public Optional<Placement> findByCode(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                                + "WHERE campaign_id = ? AND placement_code = ?",
                        MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /**
     * 行锁读取公告下指定展示位，用于申请时锁定其日额度上限。
     */
    public Optional<Placement> lockByCode(String campaignId, String placementCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                                + "WHERE campaign_id = ? AND placement_code = ? FOR UPDATE",
                        MAPPER, campaignId, placementCode)
                .stream()
                .findFirst();
    }

    /**
     * 统计公告已创建的展示位数量（含 DEFAULT）；调用方须已持有公告行锁以避免漏算并发插入。
     */
    public int countByCampaign(String campaignId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM placement WHERE campaign_id = ?",
                Integer.class, campaignId);
        return count == null ? 0 : count;
    }

    /**
     * 列出公告下全部展示位（含 DEFAULT）。
     */
    public List<Placement> listByCampaign(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM placement "
                        + "WHERE campaign_id = ? ORDER BY config_version",
                MAPPER, campaignId);
    }
}
