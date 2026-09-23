package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.SnapshotItemStatus;
import com.example.starter.exposure.domain.WithdrawalItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 撤回快照项数据访问。条件更新方法必须在事务内调用；
 * 决议更新以 status='SETTLING' 为条件，保证并发下每项只有一个终态。
 */
@Repository
public class WithdrawalItemRepository {

    private static final RowMapper<WithdrawalItem> MAPPER = (rs, rowNum) -> new WithdrawalItem(
            rs.getString("withdrawal_key"),
            rs.getString("reservation_id"),
            rs.getInt("campaign_version"),
            rs.getString("visitor_id"),
            rs.getLong("reserved_at_utc"),
            rs.getLong("expires_at_utc"),
            SnapshotItemStatus.valueOf(rs.getString("status")),
            (Long) rs.getObject("decided_at_utc"),
            rs.getString("decision_reason"));

    private static final String COLUMNS =
            "withdrawal_key, reservation_id, campaign_version, visitor_id, reserved_at_utc, "
                    + "expires_at_utc, status, decided_at_utc, decision_reason";

    private final JdbcTemplate jdbc;

    public WithdrawalItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(WithdrawalItem item) {
        jdbc.update("INSERT INTO withdrawal_item (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                item.withdrawalKey(),
                item.reservationId(),
                item.campaignVersion(),
                item.visitorId(),
                item.reservedAtUtc(),
                item.expiresAtUtc(),
                item.status().name(),
                item.decidedAtUtc(),
                item.decisionReason());
    }

    public Optional<WithdrawalItem> findByReservationId(String reservationId) {
        List<WithdrawalItem> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM withdrawal_item WHERE reservation_id = ?",
                MAPPER, reservationId);
        return list.stream().findFirst();
    }

    /**
     * 按撤回键查询全部快照项，按预占单编号排序（只读）。
     */
    public List<WithdrawalItem> findByWithdrawalKey(String withdrawalKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM withdrawal_item WHERE withdrawal_key = ? "
                        + "ORDER BY reservation_id",
                MAPPER, withdrawalKey);
    }

    /**
     * 行锁查询某撤回下仍为 SETTLING 的快照项，按预占单编号排序（结算用，加锁顺序确定）。
     */
    public List<WithdrawalItem> lockSettlingByWithdrawalKey(String withdrawalKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM withdrawal_item "
                        + "WHERE withdrawal_key = ? AND status = 'SETTLING' "
                        + "ORDER BY reservation_id FOR UPDATE",
                MAPPER, withdrawalKey);
    }

    /**
     * 条件决议：仅当快照项仍为 SETTLING 时写入终态、决议时刻与决议来源。
     *
     * @return 是否更新成功（并发决议竞争时只有一个返回 true）
     */
    public boolean decideIfSettling(String reservationId, SnapshotItemStatus target,
                                    long decidedAtUtc, String decisionReason) {
        int rows = jdbc.update("UPDATE withdrawal_item SET status = ?, decided_at_utc = ?, "
                        + "decision_reason = ? WHERE reservation_id = ? AND status = 'SETTLING'",
                target.name(), decidedAtUtc, decisionReason, reservationId);
        return rows == 1;
    }
}
