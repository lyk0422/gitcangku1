package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ItemDecision;
import com.example.starter.exposure.domain.SnapshotItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 撤回快照项数据访问。冻结字段（键、版本、visitorKey、reservedAt、expiresAt）只读，
 * 仅决议字段可经 CAS 由 PENDING 进入终态。加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class SnapshotItemRepository {

    private final JdbcTemplate jdbc;

    public SnapshotItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "reservation_id, withdrawal_key, campaign_id, campaign_version, visitor_key, "
                    + "reserved_at_utc, expires_at_utc, decision, receipt_key, occurred_at_utc, decided_at_utc";

    private static final RowMapper<SnapshotItem> MAPPER = (rs, rowNum) -> new SnapshotItem(
            rs.getString("reservation_id"),
            rs.getString("withdrawal_key"),
            rs.getString("campaign_id"),
            rs.getInt("campaign_version"),
            rs.getString("visitor_key"),
            rs.getLong("reserved_at_utc"),
            rs.getLong("expires_at_utc"),
            ItemDecision.valueOf(rs.getString("decision")),
            rs.getString("receipt_key"),
            (Long) rs.getObject("occurred_at_utc"),
            (Long) rs.getObject("decided_at_utc"));

    public void insert(SnapshotItem item) {
        jdbc.update("INSERT INTO exposure_snapshot_item (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                item.reservationId(),
                item.withdrawalKey(),
                item.campaignId(),
                item.campaignVersion(),
                item.visitorKey(),
                item.reservedAtUtc(),
                item.expiresAtUtc(),
                item.decision().name(),
                item.receiptKey(),
                item.occurredAtUtc(),
                item.decidedAtUtc());
    }

    public Optional<SnapshotItem> findByReservationId(String reservationId) {
        List<SnapshotItem> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_snapshot_item WHERE reservation_id = ?",
                MAPPER, reservationId);
        return list.stream().findFirst();
    }

    /** 行锁读取快照项。 */
    public Optional<SnapshotItem> lockByReservationId(String reservationId) {
        List<SnapshotItem> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_snapshot_item WHERE reservation_id = ? FOR UPDATE",
                MAPPER, reservationId);
        return list.stream().findFirst();
    }

    /** 按回执键查询（receipt_key 全局唯一）。 */
    public Optional<SnapshotItem> findByReceiptKey(String receiptKey) {
        List<SnapshotItem> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_snapshot_item WHERE receipt_key = ?",
                MAPPER, receiptKey);
        return list.stream().findFirst();
    }

    /** 查询某撤回下全部快照项（按预占编号排序，保证响应稳定）。 */
    public List<SnapshotItem> findByWithdrawalKey(String withdrawalKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_snapshot_item "
                        + "WHERE withdrawal_key = ? ORDER BY reservation_id",
                MAPPER, withdrawalKey);
    }

    /** 行锁查询某撤回下全部快照项。 */
    public List<SnapshotItem> lockByWithdrawalKey(String withdrawalKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_snapshot_item "
                        + "WHERE withdrawal_key = ? ORDER BY reservation_id FOR UPDATE",
                MAPPER, withdrawalKey);
    }

    /** 统计某撤回下仍为 PENDING 的快照项数。 */
    public int countPending(String withdrawalKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_snapshot_item "
                        + "WHERE withdrawal_key = ? AND decision = 'PENDING'",
                Integer.class, withdrawalKey);
        return count == null ? 0 : count;
    }

    /**
     * 回执路径终态 CAS：仅当仍为 PENDING 时写入决议、回执键、发生时刻与决议时刻。
     *
     * @return 是否更新成功（并发决议只有一个返回 true）
     */
    public boolean decideWithReceipt(String reservationId, ItemDecision target,
                                     String receiptKey, long occurredAtUtc, long decidedAtUtc) {
        int rows = jdbc.update("UPDATE exposure_snapshot_item "
                        + "SET decision = ?, receipt_key = ?, occurred_at_utc = ?, decided_at_utc = ? "
                        + "WHERE reservation_id = ? AND decision = 'PENDING'",
                target.name(), receiptKey, occurredAtUtc, decidedAtUtc, reservationId);
        return rows == 1;
    }

    /**
     * 非回执路径终态 CAS（到期释放、显式结算）：仅当仍为 PENDING 时写入决议与决议时刻。
     *
     * @return 是否更新成功（并发决议只有一个返回 true）
     */
    public boolean decideWithoutReceipt(String reservationId, ItemDecision target, long decidedAtUtc) {
        int rows = jdbc.update("UPDATE exposure_snapshot_item "
                        + "SET decision = ?, decided_at_utc = ? "
                        + "WHERE reservation_id = ? AND decision = 'PENDING'",
                target.name(), decidedAtUtc, reservationId);
        return rows == 1;
    }
}
