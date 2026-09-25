package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心双人激活记录数据访问；记录不可变：只插入与查询，不提供更新或删除。
 */
@Repository
public class SiteActivationRepository {

    /**
     * 双人激活记录行（不可变）。generation 为本次激活产生的代次；
     * targetCap 为激活时上限快照。
     */
    public record SiteActivationRow(
            long id,
            String experimentId,
            String siteCode,
            int generation,
            String activationKey,
            String firstActor,
            long firstConfirmedAt,
            String secondActor,
            long secondConfirmedAt,
            int targetCap) {
    }

    private static final RowMapper<SiteActivationRow> MAPPER = (rs, n) -> new SiteActivationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("site_code"),
            rs.getInt("generation"),
            rs.getString("activation_key"),
            rs.getString("first_actor"),
            rs.getLong("first_confirmed_at"),
            rs.getString("second_actor"),
            rs.getLong("second_confirmed_at"),
            rs.getInt("target_cap"));

    private static final String COLUMNS =
            "id, experiment_id, site_code, generation, activation_key, "
                    + "first_actor, first_confirmed_at, second_actor, second_confirmed_at, target_cap";

    private final JdbcTemplate jdbc;

    public SiteActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入不可变激活记录；与中心状态变更在同一事务提交。
     */
    public void insert(SiteActivationRow row) {
        jdbc.update("INSERT INTO site_activation ("
                        + "experiment_id, site_code, generation, activation_key, "
                        + "first_actor, first_confirmed_at, second_actor, second_confirmed_at, target_cap"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.siteCode(), row.generation(), row.activationKey(),
                row.firstActor(), row.firstConfirmedAt(),
                row.secondActor(), row.secondConfirmedAt(), row.targetCap());
    }

    /**
     * 按代次升序查询中心全部激活记录。
     */
    public List<SiteActivationRow> findBySite(String experimentId, String siteCode) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM site_activation "
                        + "WHERE experiment_id = ? AND site_code = ? ORDER BY generation",
                MAPPER, experimentId, siteCode);
    }

    /**
     * 查询中心最新一代激活记录；从未激活返回 null。
     */
    public SiteActivationRow findLatest(String experimentId, String siteCode) {
        List<SiteActivationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM site_activation "
                        + "WHERE experiment_id = ? AND site_code = ? "
                        + "ORDER BY generation DESC LIMIT 1",
                MAPPER, experimentId, siteCode);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
