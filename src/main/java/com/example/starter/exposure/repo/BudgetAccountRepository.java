package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.BudgetAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 活动预算账本数据访问。加锁/增减方法必须在事务内调用；
 * 预算恒等式由表级 CHECK 约束兜底。
 */
@Repository
public class BudgetAccountRepository {

    private final JdbcTemplate jdbc;

    public BudgetAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BudgetAccount> MAPPER = (rs, rowNum) -> new BudgetAccount(
            rs.getString("campaign_id"),
            rs.getString("tenant_id"),
            rs.getLong("window_start_utc"),
            rs.getLong("window_end_utc"),
            rs.getString("audience_rule"),
            rs.getInt("budget"),
            rs.getInt("in_flight"),
            rs.getInt("confirmed"),
            rs.getInt("version"),
            rs.getLong("created_at_utc"));

    private static final String COLUMNS =
            "campaign_id, tenant_id, window_start_utc, window_end_utc, audience_rule, "
                    + "budget, in_flight, confirmed, version, created_at_utc";

    /** 插入账本；编号冲突由调用方依据唯一约束处理。 */
    public void insert(BudgetAccount account) {
        jdbc.update("INSERT INTO campaign_budget_account (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                account.campaignId(),
                account.tenantId(),
                account.windowStartUtc(),
                account.windowEndUtc(),
                account.audienceRule(),
                account.budget(),
                account.inFlight(),
                account.confirmed(),
                account.version(),
                account.createdAtUtc());
    }

    /** 按编号查询账本（无锁）。 */
    public Optional<BudgetAccount> findById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_budget_account WHERE campaign_id = ?",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /** 行锁读取单个账本；不存在返回 empty。 */
    public Optional<BudgetAccount> lockById(String campaignId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_budget_account "
                        + "WHERE campaign_id = ? FOR UPDATE",
                        MAPPER, campaignId)
                .stream()
                .findFirst();
    }

    /**
     * 按编号升序行锁读取多个账本；固定升序加锁避免多账本死锁。
     *
     * @param campaignIds 已去重的活动编号（方法内自行排序）
     */
    public List<BudgetAccount> lockByIds(List<String> campaignIds) {
        List<String> sorted = campaignIds.stream().distinct().sorted().toList();
        StringJoiner placeholders = new StringJoiner(", ");
        for (int i = 0; i < sorted.size(); i++) {
            placeholders.add("?");
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM campaign_budget_account "
                        + "WHERE campaign_id IN (" + placeholders + ") ORDER BY campaign_id FOR UPDATE",
                MAPPER, sorted.toArray());
    }

    /** 在途预占 +1。调用方须已持行锁并完成可转余额校验。 */
    public void incrementInFlight(String campaignId) {
        int rows = jdbc.update("UPDATE campaign_budget_account SET in_flight = in_flight + 1 "
                + "WHERE campaign_id = ?", campaignId);
        if (rows != 1) {
            throw new IllegalStateException("budget account row missing for " + campaignId);
        }
    }

    /**
     * 在途预占 -1（取消或过期释放）；CHECK 约束保证不变负。
     * 带 in_flight &gt; 0 守卫：账本建立前创建的预占未计入在途，释放时跳过。
     */
    public void decrementInFlight(String campaignId) {
        jdbc.update("UPDATE campaign_budget_account SET in_flight = in_flight - 1 "
                + "WHERE campaign_id = ? AND in_flight > 0", campaignId);
    }

    /**
     * 回执确认：在途 -1、已确认 +1；始终归属预占创建时的原 campaign。
     * 带 in_flight &gt; 0 守卫：账本建立前创建的预占未计入在途，回执时跳过。
     */
    public void confirmOne(String campaignId) {
        jdbc.update("UPDATE campaign_budget_account "
                + "SET in_flight = in_flight - 1, confirmed = confirmed + 1 "
                + "WHERE campaign_id = ? AND in_flight > 0", campaignId);
    }

    /**
     * 预算转移：一次性写入新总预算并逐活动增版。调用方须已持行锁并完成全部校验。
     */
    public void applyTransfer(String campaignId, int newBudget) {
        int rows = jdbc.update("UPDATE campaign_budget_account SET budget = ?, version = version + 1 "
                + "WHERE campaign_id = ?", newBudget, campaignId);
        if (rows != 1) {
            throw new IllegalStateException("budget account row missing for " + campaignId);
        }
    }
}
