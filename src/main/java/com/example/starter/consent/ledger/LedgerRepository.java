package com.example.starter.consent.ledger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.consent.ledger.dto.LedgerEntryResponse;

/**
 * 用途额度与账本持久化访问，基于 JdbcTemplate，所有 SQL 使用参数绑定。
 *
 * <p>账本表仅追加：不存在任何 UPDATE/DELETE 语句；额度行的余额变更与账本条目追加
 * 必须在同一事务内完成，且先取得行锁再核销，保证并发查询不超扣。
 */
@Repository
public class LedgerRepository {

    private static final RowMapper<QuotaRow> QUOTA_MAPPER = (rs, rowNum) -> {
        long balance = rs.getLong("balance");
        Long nullableBalance = rs.wasNull() ? null : balance;
        return new QuotaRow(
                rs.getString("subject_key"),
                rs.getString("purpose"),
                rs.getInt("epoch"),
                nullableBalance,
                QuotaStatus.valueOf(rs.getString("status")),
                rs.getLong("updated_seq"));
    };

    private static final RowMapper<LedgerEntryResponse> ENTRY_MAPPER = (rs, rowNum) -> {
        int objectCount = rs.getInt("object_count");
        Integer nullableObjectCount = rs.wasNull() ? null : objectCount;
        long balanceBefore = rs.getLong("balance_before");
        Long nullableBefore = rs.wasNull() ? null : balanceBefore;
        long balanceAfter = rs.getLong("balance_after");
        Long nullableAfter = rs.wasNull() ? null : balanceAfter;
        Timestamp occurred = rs.getTimestamp("occurred_at_utc");
        return new LedgerEntryResponse(
                rs.getLong("seq"),
                rs.getString("request_id"),
                rs.getString("subject_key"),
                LedgerEntryType.valueOf(rs.getString("operation")),
                rs.getString("purpose"),
                rs.getInt("epoch"),
                QuotaStatus.valueOf(rs.getString("status")),
                nullableObjectCount,
                rs.getString("object_digest"),
                nullableBefore,
                nullableAfter,
                rs.getString("fingerprint"),
                rs.getString("query_snapshot"),
                occurred == null ? null : occurred.toInstant());
    };

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 用途额度行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      额度归属授权代次
     * @param balance    剩余额度（单位：个）；status=CLEARED 时为 null
     * @param status     ACTIVE 可核销 / CLEARED 已清除
     * @param updatedSeq 最近一次改写本行的账本条目序号
     */
    public record QuotaRow(String subjectKey, String purpose, int epoch,
                           Long balance, QuotaStatus status, long updatedSeq) {
    }

    /**
     * 查询额度行；{@code forUpdate=true} 时加行锁（须在事务内调用），并发核销在此串行化。
     */
    Optional<QuotaRow> findQuota(String subjectKey, String purpose, int epoch, boolean forUpdate) {
        String sql = "SELECT subject_key, purpose, epoch, balance, status, updated_seq"
                + " FROM purpose_quota"
                + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        List<QuotaRow> rows = jdbc.query(sql, QUOTA_MAPPER, subjectKey, purpose, epoch);
        return rows.stream().findFirst();
    }

    /**
     * 首次声明额度。并发声明同一行时唯一约束保证只有一个事务成功。
     */
    void insertQuota(String subjectKey, String purpose, int epoch, long balance, long updatedSeq, Instant now) {
        jdbc.update(
                "INSERT INTO purpose_quota (subject_key, purpose, epoch, balance, status,"
                        + " updated_seq, created_at_utc, updated_at_utc)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)",
                subjectKey, purpose, epoch, balance, updatedSeq, Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 在已持有行锁的前提下设置余额（上调或下调），返回受影响行数。
     */
    int updateBalance(String subjectKey, String purpose, int epoch, Long balance, long updatedSeq, Instant now) {
        return jdbc.update(
                "UPDATE purpose_quota SET balance = ?, status = 'ACTIVE', updated_seq = ?, updated_at_utc = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                balance, updatedSeq, Timestamp.from(now), subjectKey, purpose, epoch);
    }

    /**
     * 清除额度：余额置 NULL、状态置 CLEARED；仅 ACTIVE 行可清除，返回受影响行数。
     */
    int clearQuota(String subjectKey, String purpose, int epoch, long updatedSeq, Instant now) {
        return jdbc.update(
                "UPDATE purpose_quota SET balance = NULL, status = 'CLEARED', updated_seq = ?, updated_at_utc = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                updatedSeq, Timestamp.from(now), subjectKey, purpose, epoch);
    }

    /**
     * 授权代次状态行。
     *
     * @param exists 代次是否存在
     * @param status ACTIVE / REVOKED；不存在时为 null
     */
    public record GrantStatusRow(boolean exists, String status) {
    }

    /**
     * 记录行（用于受限查询对象校验）。
     *
     * @param recordKey 记录键
     * @param epoch     记录实际所属代次
     * @param payload   记录内容
     */
    public record RecordData(String recordKey, int epoch, String payload) {
    }

    /**
     * 查询授权代次状态（只读）。
     */
    GrantStatusRow findGrantStatus(String subjectKey, String purpose, int epoch) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM consent_grant WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                (rs, rowNum) -> rs.getString("status"), subjectKey, purpose, epoch);
        return statuses.stream().findFirst()
                .map(status -> new GrantStatusRow(true, status))
                .orElseGet(() -> new GrantStatusRow(false, null));
    }

    /**
     * 按 recordKey 集合批量查询记录及其实际所属代次（只读），用于一次性校验存在性与跨代次。
     */
    List<RecordData> findRecords(String subjectKey, String purpose, List<String> recordKeys) {
        if (recordKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(recordKeys.size(), "?"));
        Object[] args = new Object[recordKeys.size() + 2];
        args[0] = subjectKey;
        args[1] = purpose;
        for (int i = 0; i < recordKeys.size(); i++) {
            args[i + 2] = recordKeys.get(i);
        }
        return jdbc.query(
                "SELECT record_key, epoch, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND record_key IN (" + placeholders + ")",
                (rs, rowNum) -> new RecordData(rs.getString("record_key"), rs.getInt("epoch"),
                        rs.getString("payload")),
                args);
    }

    /**
     * 事务内分配下一个全局账本序号：更新单行取行锁，按提交顺序裁决。
     */
    long nextSeq() {
        int updated = jdbc.update("UPDATE ledger_seq SET next_val = next_val + 1 WHERE id = 1");
        if (updated == 0) {
            throw new IllegalStateException("账本序号分配表未初始化");
        }
        Long nextVal = jdbc.queryForObject("SELECT next_val FROM ledger_seq WHERE id = 1", Long.class);
        if (nextVal == null) {
            throw new IllegalStateException("账本序号生成失败");
        }
        return nextVal - 1;
    }

    /**
     * 追加账本条目；条目永不更新或删除。
     */
    void insertEntry(long seq, LedgerEntryType operation, String requestId, String subjectKey, String purpose,
                     int epoch, QuotaStatus status, Integer objectCount, String objectDigest,
                     Long balanceBefore, Long balanceAfter, String fingerprint,
                     String querySnapshot, Instant occurredAt) {
        jdbc.update(
                "INSERT INTO purpose_ledger (seq, request_id, subject_key, operation, purpose, epoch, status,"
                        + " object_count, object_digest, balance_before, balance_after, fingerprint,"
                        + " query_snapshot, occurred_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                seq, requestId, subjectKey, operation.name(), purpose, epoch, status.name(),
                objectCount, objectDigest, balanceBefore, balanceAfter, fingerprint,
                querySnapshot, Timestamp.from(occurredAt));
    }

    Optional<LedgerEntryResponse> findEntry(long seq) {
        List<LedgerEntryResponse> rows = jdbc.query(
                "SELECT seq, request_id, subject_key, operation, purpose, epoch, status, object_count,"
                        + " object_digest, balance_before, balance_after, fingerprint, query_snapshot,"
                        + " occurred_at_utc FROM purpose_ledger WHERE seq = ?",
                ENTRY_MAPPER, seq);
        return rows.stream().findFirst();
    }

    /**
     * 列出账本条目：时间区间 UTC 左闭右开 {@code [from, to)}，按 seq 升序稳定排序。
     */
    List<LedgerEntryResponse> listEntries(String subjectKey, String purpose, Integer epoch,
                                          Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT seq, request_id, subject_key, operation, purpose, epoch, status, object_count,"
                        + " object_digest, balance_before, balance_after, fingerprint, query_snapshot,"
                        + " occurred_at_utc FROM purpose_ledger WHERE subject_key = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(subjectKey);
        if (purpose != null) {
            sql.append(" AND purpose = ?");
            args.add(purpose);
        }
        if (epoch != null) {
            sql.append(" AND epoch = ?");
            args.add(epoch);
        }
        if (from != null) {
            sql.append(" AND occurred_at_utc >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at_utc < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY seq ASC");
        return jdbc.query(sql.toString(), ENTRY_MAPPER, args.toArray());
    }

    /**
     * 账本诊断聚合行。
     *
     * @param totalCount   条目总数（单位：条）
     * @param queryCount   QUERY 条目数（单位：条）
     * @param totalDeducted 累计核销对象数（单位：个）
     * @param lastSeq      最近条目序号；无条目为 null
     */
    public record LedgerStats(long totalCount, long queryCount, long totalDeducted, Long lastSeq) {
    }

    /**
     * 聚合统计指定主体＋用途＋代次下的账本数据（只读）。
     *
     * <p>{@code totalDeducted} 为历史累计核销对象数量（单位：个），即各 QUERY 条目
     * 扣减前余额与扣减后余额之差之和，与对象条目数 {@code queryCount} 口径不同。
     */
    LedgerStats stats(String subjectKey, String purpose, int epoch) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS total_count,"
                        + " COALESCE(SUM(CASE WHEN operation = 'QUERY' THEN 1 ELSE 0 END), 0) AS query_count,"
                        + " COALESCE(SUM(CASE WHEN operation = 'QUERY'"
                        + " THEN balance_before - balance_after ELSE 0 END), 0) AS total_deducted,"
                        + " MAX(seq) AS last_seq"
                        + " FROM purpose_ledger"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                (rs, rowNum) -> new LedgerStats(
                        rs.getLong("total_count"),
                        rs.getLong("query_count"),
                        rs.getLong("total_deducted"),
                        (Long) rs.getObject("last_seq")),
                subjectKey, purpose, epoch);
    }
}
