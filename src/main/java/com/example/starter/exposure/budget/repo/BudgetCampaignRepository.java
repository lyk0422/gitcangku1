package com.example.starter.exposure.budget.repo;

import com.example.starter.exposure.budget.domain.BudgetCampaign;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 活动预算账本数据访问。加锁/版本 CAS 方法必须在事务内调用。
 */
@Repository
public class BudgetCampaignRepository {

    private final JdbcTemplate jdbc;

    public BudgetCampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "campaign_id, tenant_id, window_start_utc, window_end_utc, audience_rule, "
                    + "budget, version, created_at_utc";

    private static final RowMapper<BudgetCampaign> MAPPER = (rs, rowNum) -> new BudgetCampaign(
            rs.getString("campaign_id"),
            rs.getString("tenant_id"),
            rs.getLong("window_start_utc"),
            rs.getLong("window_end_utc"),
            rs.getString("audience_rule"),
            rs.getLong("budget"),
            rs.getLong("version"),
            rs.getLong("created_at_utc"));

    /** 插入活动账本；编号冲突由调用方依据唯一约束处理。 */
    public void insert(BudgetCampaign campaign) {
        jdbc.update("INSERT INTO budget_campaign (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                campaign.campaignId(),
                campaign.tenantId(),
                campaign.windowStartUtc(),
                campaign.windowEndUtc(),
                campaign.audienceRule(),
                campaign.budget(),
                campaign.version(),
                campaign.createdAtUtc());
    }

    public Optional<BudgetCampaign> findById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget_campaign WHERE campaign_id = ?",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /** 行锁读取单个活动账本。 */
    public Optional<BudgetCampaign> lockById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM budget_campaign WHERE campaign_id = ? FOR UPDATE",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /** 行锁读取给定编号集合的全部活动账本，按 campaignId 升序返回（固定加锁顺序防死锁）。 */
    public List<BudgetCampaign> lockByIds(Collection<String> campaignIds) {
        if (campaignIds == null || campaignIds.isEmpty()) {
            return Collections.emptyList();
        }
        String inPlaceholders = String.join(",", campaignIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT " + COLUMNS + " FROM budget_campaign "
                        + "WHERE campaign_id IN (" + inPlaceholders + ") "
                        + "ORDER BY campaign_id ASC FOR UPDATE",
                MAPPER, campaignIds.toArray());
    }

    /**
     * 条件更新预算与版本：仅当当前版本为 expectedVersion 时应用预算增量并 +1 版本。
     * 依赖 CHECK (budget &gt;= 0) 兜底，预算变负会更新失败。
     *
     * @return 是否更新成功（版本被并发改动时返回 false）
     */
    public boolean compareAndSetBudget(String campaignId, long expectedVersion,
                                       long budgetDelta, long nextVersion) {
        int rows = jdbc.update("UPDATE budget_campaign SET budget = budget + ?, version = ? "
                        + "WHERE campaign_id = ? AND version = ? AND budget + ? >= 0",
                budgetDelta, nextVersion, campaignId, expectedVersion, budgetDelta);
        return rows == 1;
    }
}
