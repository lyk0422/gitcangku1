package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Withdrawal;
import com.example.starter.exposure.domain.WithdrawalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 公告版本撤回单数据访问。加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class WithdrawalRepository {

    private final JdbcTemplate jdbc;

    public WithdrawalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "withdrawal_key, campaign_id, campaign_version, cutoff_at_utc, status, "
                    + "created_at_utc, completed_at_utc";

    private static final RowMapper<Withdrawal> MAPPER = (rs, rowNum) -> new Withdrawal(
            rs.getString("withdrawal_key"),
            rs.getString("campaign_id"),
            rs.getInt("campaign_version"),
            rs.getLong("cutoff_at_utc"),
            WithdrawalStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            (Long) rs.getObject("completed_at_utc"));

    public void insert(Withdrawal withdrawal) {
        jdbc.update("INSERT INTO exposure_withdrawal (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
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
                "SELECT " + COLUMNS + " FROM exposure_withdrawal WHERE withdrawal_key = ?",
                MAPPER, withdrawalKey);
        return list.stream().findFirst();
    }

    /** 行锁读取撤回单。 */
    public Optional<Withdrawal> lockByKey(String withdrawalKey) {
        List<Withdrawal> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_withdrawal WHERE withdrawal_key = ? FOR UPDATE",
                MAPPER, withdrawalKey);
        return list.stream().findFirst();
    }

    /**
     * 条件完成：仅当仍为 SETTLING 时转 COMPLETED 并记录完成时刻。
     *
     * @return 是否更新成功（并发下只有一个事务返回 true）
     */
    public boolean markCompleted(String withdrawalKey, long completedAtUtc) {
        int rows = jdbc.update("UPDATE exposure_withdrawal SET status = 'COMPLETED', completed_at_utc = ? "
                        + "WHERE withdrawal_key = ? AND status = 'SETTLING'",
                completedAtUtc, withdrawalKey);
        return rows == 1;
    }
}
