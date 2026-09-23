package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Withdrawal;
import com.example.starter.exposure.domain.WithdrawalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 公告版本撤回单数据访问。加锁/条件更新方法必须在事务内调用。
 */
@Repository
public class WithdrawalRepository {

    private static final RowMapper<Withdrawal> MAPPER = (rs, rowNum) -> new Withdrawal(
            rs.getString("withdrawal_key"),
            rs.getString("campaign_id"),
            rs.getInt("campaign_version"),
            rs.getLong("cutoff_at_utc"),
            WithdrawalStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            (Long) rs.getObject("completed_at_utc"));

    private static final String COLUMNS =
            "withdrawal_key, campaign_id, campaign_version, cutoff_at_utc, status, "
                    + "created_at_utc, completed_at_utc";

    private final JdbcTemplate jdbc;

    public WithdrawalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Withdrawal withdrawal) {
        jdbc.update("INSERT INTO withdrawal (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                withdrawal.withdrawalKey(),
                withdrawal.campaignId(),
                withdrawal.campaignVersion(),
                withdrawal.cutoffAtUtc(),
                withdrawal.status().name(),
                withdrawal.createdAtUtc(),
                withdrawal.completedAtUtc());
    }

    public Optional<Withdrawal> findByKey(String withdrawalKey) {
        List<Withdrawal> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM withdrawal WHERE withdrawal_key = ?",
                MAPPER, withdrawalKey);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取撤回单。
     */
    public Optional<Withdrawal> lockByKey(String withdrawalKey) {
        List<Withdrawal> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM withdrawal WHERE withdrawal_key = ? FOR UPDATE",
                MAPPER, withdrawalKey);
        return list.stream().findFirst();
    }

    /**
     * 是否已存在某公告指定版本的撤回单（任意状态）；存在即禁止该版本新预占。
     */
    public boolean existsByCampaignAndVersion(String campaignId, int campaignVersion) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM withdrawal WHERE campaign_id = ? AND campaign_version = ?",
                Integer.class, campaignId, campaignVersion);
        return count != null && count > 0;
    }

    /**
     * 当快照内不再有 SETTLING 项时，原子地把撤回单置为 COMPLETED。
     *
     * @return 是否本次调用完成收口（并发下只有一个事务返回 true）
     */
    public boolean completeIfAllTerminal(String withdrawalKey, long completedAtUtc) {
        int rows = jdbc.update("UPDATE withdrawal SET status = 'COMPLETED', completed_at_utc = ? "
                        + "WHERE withdrawal_key = ? AND status = 'SETTLING' AND NOT EXISTS ("
                        + "SELECT 1 FROM withdrawal_item "
                        + "WHERE withdrawal_key = ? AND status = 'SETTLING')",
                completedAtUtc, withdrawalKey, withdrawalKey);
        return rows == 1;
    }
}
