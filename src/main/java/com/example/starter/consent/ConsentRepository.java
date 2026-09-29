package com.example.starter.consent;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 授权代次与记录的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 */
@Repository
public class ConsentRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, rowNum) -> new GrantRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            GrantStatus.valueOf(rs.getString("status")));

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("record_key"),
            rs.getString("payload"));

    private static final RowMapper<QuotaRow> QUOTA_MAPPER = (rs, rowNum) -> new QuotaRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getLong("quota_limit"),
            rs.getLong("used_amount"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private static final RowMapper<LedgerRow> LEDGER_MAPPER = (rs, rowNum) -> new LedgerRow(
            rs.getLong("ledger_seq"),
            rs.getString("request_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("canonical_set_digest"),
            rs.getInt("object_count"),
            rs.getLong("balance_before"),
            rs.getLong("balance_after"),
            rs.getLong("quota_limit"),
            rs.getString("query_snapshot"),
            rs.getString("fingerprint"),
            rs.getTimestamp("occurred_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 授权代次行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      代次，从 1 开始
     * @param status     状态：ACTIVE 有效 / REVOKED 已撤回
     */
    public record GrantRow(String subjectKey, Purpose purpose, int epoch, GrantStatus status) {
    }

    /**
     * 记录行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      所属代次
     * @param recordKey  记录键
     * @param payload    记录内容
     */
    public record RecordRow(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload) {
    }

    /**
     * 用途额度行。
     *
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      授权代次
     * @param quotaLimit 查询总额度（单位：对象数）
     * @param usedAmount 已核销对象数（单位：对象数）
     * @param requestId  最近一次额度声明/调整的幂等请求标识
     * @param createdAtUtc 声明时间（UTC）
     * @param updatedAtUtc 最近调整时间（UTC）
     */
    public record QuotaRow(String subjectKey, Purpose purpose, int epoch, long quotaLimit, long usedAmount,
                           String requestId, Instant createdAtUtc, Instant updatedAtUtc) {

        /**
         * 剩余余额＝总额度－已用（单位：对象数）。
         */
        public long remaining() {
            return quotaLimit - usedAmount;
        }
    }

    /**
     * 账本行（只追加证据）。
     *
     * @param ledgerSeq          全局顺序号
     * @param requestId          触发扣减的幂等查询请求标识
     * @param subjectKey         主体标识
     * @param purpose            查询用途
     * @param epoch              扣减发生时的授权代次
     * @param canonicalSetDigest 对象规范集合摘要（SHA-256）
     * @param objectCount        本次核销对象数
     * @param balanceBefore      扣减前余额
     * @param balanceAfter       扣减后余额
     * @param quotaLimit         扣减时总额度快照
     * @param querySnapshot      完整成功响应快照（JSON 原文）
     * @param fingerprint        证据指纹（SHA-256）
     * @param occurredAtUtc      扣减发生时间（UTC，毫秒精度）
     */
    public record LedgerRow(long ledgerSeq, String requestId, String subjectKey, Purpose purpose, int epoch,
                            String canonicalSetDigest, int objectCount, long balanceBefore, long balanceAfter,
                            long quotaLimit, String querySnapshot, String fingerprint, Instant occurredAtUtc) {
    }

    Optional<GrantRow> findGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    Optional<GrantRow> findLatestGrant(String subjectKey, Purpose purpose) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch DESC LIMIT 1",
                GRANT_MAPPER, subjectKey, purpose.name());
        return rows.stream().findFirst();
    }

    /**
     * 列出某主体＋用途的全部授权代次（诊断用），按 epoch 升序。
     */
    List<GrantRow> listGrants(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch ASC",
                GRANT_MAPPER, subjectKey, purpose.name());
    }

    /**
     * 锁定指定授权代次行（SELECT ... FOR UPDATE），使撤回、查询、额度调整、清除按提交顺序串行裁决。
     */
    Optional<GrantRow> lockGrant(String subjectKey, Purpose purpose, int epoch) {
        List<GrantRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status FROM consent_grant"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                GRANT_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    void insertGrant(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                subjectKey, purpose.name(), epoch, requestId);
    }

    /**
     * 仅当代次当前为 ACTIVE 时撤回；返回是否实际发生状态变更。
     */
    boolean revokeGrant(String subjectKey, Purpose purpose, int epoch) {
        int updated = jdbc.update(
                "UPDATE consent_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND status = 'ACTIVE'",
                subjectKey, purpose.name(), epoch);
        return updated > 0;
    }

    Optional<RecordRow> findRecord(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?",
                RECORD_MAPPER, subjectKey, purpose.name(), epoch, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询对象键是否存在于同一主体＋用途的其他代次（用于定位对象跨 epoch 失败）。
     */
    Optional<RecordRow> findRecordAcrossEpochs(String subjectKey, Purpose purpose, String recordKey) {
        List<RecordRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND record_key = ?"
                        + " ORDER BY epoch DESC LIMIT 1",
                RECORD_MAPPER, subjectKey, purpose.name(), recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 按对象键集合批量取当前代次记录；调用方负责保证键已去重。
     */
    List<RecordRow> findRecords(String subjectKey, Purpose purpose, int epoch, List<String> recordKeys) {
        if (recordKeys.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ", " IN (", ")");
        for (int i = 0; i < recordKeys.size(); i++) {
            placeholders.add("?");
        }
        List<Object> args = new ArrayList<>();
        args.add(subjectKey);
        args.add(purpose.name());
        args.add(epoch);
        args.addAll(recordKeys);
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, record_key, payload FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key"
                        + placeholders,
                RECORD_MAPPER, args.toArray());
    }

    void insertRecord(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload, String requestId) {
        jdbc.update(
                "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, recordKey, payload, requestId);
    }

    // ---------- 用途额度 ----------

    Optional<QuotaRow> findQuota(String subjectKey, Purpose purpose, int epoch) {
        List<QuotaRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, quota_limit, used_amount, request_id,"
                        + " created_at_utc, updated_at_utc FROM purpose_quota"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                QUOTA_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 锁定额度行，使并发查询与额度调整串行化。
     */
    Optional<QuotaRow> lockQuota(String subjectKey, Purpose purpose, int epoch) {
        List<QuotaRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, quota_limit, used_amount, request_id,"
                        + " created_at_utc, updated_at_utc FROM purpose_quota"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                QUOTA_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    void insertQuota(String subjectKey, Purpose purpose, int epoch, long quotaLimit, String requestId, Instant now) {
        jdbc.update(
                "INSERT INTO purpose_quota (subject_key, purpose, epoch, quota_limit, used_amount,"
                        + " request_id, created_at_utc, updated_at_utc) VALUES (?, ?, ?, ?, 0, ?, ?, ?)",
                subjectKey, purpose.name(), epoch, quotaLimit, requestId,
                Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 调整总额度；行必须已存在且已在当前事务中锁定。
     */
    void updateQuotaLimit(String subjectKey, Purpose purpose, int epoch, long quotaLimit, String requestId, Instant now) {
        jdbc.update(
                "UPDATE purpose_quota SET quota_limit = ?, request_id = ?, updated_at_utc = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                quotaLimit, requestId, Timestamp.from(now), subjectKey, purpose.name(), epoch);
    }

    /**
     * 条件核销：仅当剩余余额足够时增加已用量，返回是否核销成功。作为行锁之外的第二道防超扣保证。
     */
    boolean deductQuota(String subjectKey, Purpose purpose, int epoch, int amount) {
        int updated = jdbc.update(
                "UPDATE purpose_quota SET used_amount = used_amount + ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND quota_limit - used_amount >= ?",
                amount, subjectKey, purpose.name(), epoch, amount);
        return updated > 0;
    }

    /**
     * 列出某主体＋用途的全部额度（诊断用），按 epoch 升序。
     */
    List<QuotaRow> listQuotas(String subjectKey, Purpose purpose) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, quota_limit, used_amount, request_id,"
                        + " created_at_utc, updated_at_utc FROM purpose_quota"
                        + " WHERE subject_key = ? AND purpose = ? ORDER BY epoch ASC",
                QUOTA_MAPPER, subjectKey, purpose.name());
    }

    // ---------- 查询扣减账本（只追加） ----------

    /**
     * 追加一条账本记录并返回生成的全局顺序号；账本不提供任何更新或删除方法。
     */
    long insertLedger(LedgerRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO purpose_query_ledger (request_id, subject_key, purpose, epoch,"
                            + " canonical_set_digest, object_count, balance_before, balance_after, quota_limit,"
                            + " query_snapshot, fingerprint, occurred_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.requestId());
            ps.setString(2, row.subjectKey());
            ps.setString(3, row.purpose().name());
            ps.setInt(4, row.epoch());
            ps.setString(5, row.canonicalSetDigest());
            ps.setInt(6, row.objectCount());
            ps.setLong(7, row.balanceBefore());
            ps.setLong(8, row.balanceAfter());
            ps.setLong(9, row.quotaLimit());
            ps.setString(10, row.querySnapshot());
            ps.setString(11, row.fingerprint());
            ps.setTimestamp(12, Timestamp.from(row.occurredAtUtc()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("账本顺序号生成失败");
        }
        return key.longValue();
    }

    /**
     * 按主键顺序号取账本条目。
     */
    Optional<LedgerRow> findLedgerBySeq(long ledgerSeq) {
        List<LedgerRow> rows = jdbc.query(ledgerSelectSql() + " WHERE ledger_seq = ?", LEDGER_MAPPER, ledgerSeq);
        return rows.stream().findFirst();
    }

    /**
     * 按触发查询的 requestId 取账本条目（同参重放返回的首次成功扣减）。
     */
    Optional<LedgerRow> findLedgerByRequestId(String requestId) {
        List<LedgerRow> rows = jdbc.query(ledgerSelectSql() + " WHERE request_id = ?", LEDGER_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按“主体＋用途＋epoch”列明明细，按顺序号稳定升序。
     */
    List<LedgerRow> listLedgerByScope(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                ledgerSelectSql() + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY ledger_seq ASC",
                LEDGER_MAPPER, subjectKey, purpose.name(), epoch);
    }

    /**
     * 历史区间查询：时间口径 UTC，区间左闭右开 [fromUtc, toUtc)，排序键为 (occurred_at_utc, ledger_seq)。
     * fromUtc/toUtc 可为 null，分别表示不设下界/上界；limit 限制返回条数。
     */
    List<LedgerRow> listLedgerHistory(String subjectKey, Purpose purpose, Integer epoch,
                                      Instant fromUtc, Instant toUtc, int limit) {
        StringBuilder sql = new StringBuilder(ledgerSelectSql())
                .append(" WHERE subject_key = ? AND purpose = ?");
        List<Object> args = new ArrayList<>();
        args.add(subjectKey);
        args.add(purpose.name());
        if (epoch != null) {
            sql.append(" AND epoch = ?");
            args.add(epoch);
        }
        if (fromUtc != null) {
            sql.append(" AND occurred_at_utc >= ?");
            args.add(Timestamp.from(fromUtc));
        }
        if (toUtc != null) {
            sql.append(" AND occurred_at_utc < ?");
            args.add(Timestamp.from(toUtc));
        }
        sql.append(" ORDER BY occurred_at_utc ASC, ledger_seq ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), LEDGER_MAPPER, args.toArray());
    }

    long countLedger(String subjectKey, Purpose purpose) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purpose_query_ledger WHERE subject_key = ? AND purpose = ?",
                Long.class, subjectKey, purpose.name());
        return count == null ? 0L : count;
    }

    private static String ledgerSelectSql() {
        return "SELECT ledger_seq, request_id, subject_key, purpose, epoch, canonical_set_digest,"
                + " object_count, balance_before, balance_after, quota_limit, query_snapshot, fingerprint,"
                + " occurred_at_utc FROM purpose_query_ledger";
    }

    // ---------- 合规清除 ----------

    /**
     * 将指定 epoch 尚未清除的记录标记清除时间，返回实际新标记的对象数；不物理删除、不改写其他表。
     */
    int markRecordsCleared(String subjectKey, Purpose purpose, int epoch, Instant clearedAtUtc) {
        return jdbc.update(
                "UPDATE consent_record SET cleared_at = ?"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND cleared_at IS NULL",
                Timestamp.from(clearedAtUtc), subjectKey, purpose.name(), epoch);
    }

    /**
     * 统计指定 epoch 已标记清除的记录数（诊断用）。
     */
    long countClearedRecords(String subjectKey, Purpose purpose, int epoch) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND cleared_at IS NOT NULL",
                Long.class, subjectKey, purpose.name(), epoch);
        return count == null ? 0L : count;
    }

    /**
     * 统计指定 epoch 的记录总数（诊断用）。
     */
    long countRecords(String subjectKey, Purpose purpose, int epoch) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                Long.class, subjectKey, purpose.name(), epoch);
        return count == null ? 0L : count;
    }
}
