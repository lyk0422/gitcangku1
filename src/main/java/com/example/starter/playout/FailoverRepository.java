package com.example.starter.playout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 主备链路、租约、回执与切换单数据访问层。时间列均为 UTC 纪元毫秒（BIGINT）。
 */
@Repository
public class FailoverRepository {

    private final JdbcTemplate jdbc;

    public FailoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 频道链路行。 */
    public record LinkRow(String channelId, String linkId, String role, boolean healthy,
                          long cachedVersion, long updatedAtMs) {
    }

    /** 链路租约行。 */
    public record LeaseRow(long id, String channelId, String linkId, long generation, String status,
                           long confirmedSeq, long scheduleVersion, Long cutoverSeq, Long orderId,
                           long createdAtMs, Long endedAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 回执行。 */
    public record ReceiptRow(long id, String channelId, String linkId, long generation, long seq,
                             String disposition, long receivedAtMs) {
    }

    /** 紧急插播链路同步行。 */
    public record OverrideSyncRow(String channelId, String linkId, String overrideKey,
                                  boolean synced, long updatedAtMs) {
    }

    /** 切换单行。 */
    public record FailoverOrderRow(long id, String failoverKey, String channelId, long channelVersion,
                                   String sourceLinkId, String targetLinkId, long sourceLastSeq,
                                   long targetLastSeq, long cutoverAtMs, long maxLag, String status,
                                   Long generation, Long safeCutSeq, Long scheduleVersion,
                                   String frozenStackJson, long createdAtMs, Long activatedAtMs) {
        public boolean created() {
            return "CREATED".equals(status);
        }

        public boolean activated() {
            return "ACTIVATED".equals(status);
        }
    }

    private static final RowMapper<LinkRow> LINK_MAPPER = (rs, n) ->
            new LinkRow(rs.getString("channel_id"), rs.getString("link_id"), rs.getString("role"),
                    rs.getBoolean("healthy"), rs.getLong("cached_version"), rs.getLong("updated_at_ms"));

    private static final RowMapper<LeaseRow> LEASE_MAPPER = (rs, n) ->
            new LeaseRow(rs.getLong("id"), rs.getString("channel_id"), rs.getString("link_id"),
                    rs.getLong("generation"), rs.getString("status"), rs.getLong("confirmed_seq"),
                    rs.getLong("schedule_version"), (Long) rs.getObject("cutover_seq"),
                    (Long) rs.getObject("order_id"), rs.getLong("created_at_ms"),
                    (Long) rs.getObject("ended_at_ms"));

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, n) ->
            new ReceiptRow(rs.getLong("id"), rs.getString("channel_id"), rs.getString("link_id"),
                    rs.getLong("generation"), rs.getLong("seq"), rs.getString("disposition"),
                    rs.getLong("received_at_ms"));

    private static final RowMapper<FailoverOrderRow> ORDER_MAPPER = (rs, n) ->
            new FailoverOrderRow(rs.getLong("id"), rs.getString("failover_key"),
                    rs.getString("channel_id"), rs.getLong("channel_version"),
                    rs.getString("source_link_id"), rs.getString("target_link_id"),
                    rs.getLong("source_last_seq"), rs.getLong("target_last_seq"),
                    rs.getLong("cutover_at_ms"), rs.getLong("max_lag"), rs.getString("status"),
                    (Long) rs.getObject("generation"), (Long) rs.getObject("safe_cut_seq"),
                    (Long) rs.getObject("schedule_version"), rs.getString("frozen_stack_json"),
                    rs.getLong("created_at_ms"), (Long) rs.getObject("activated_at_ms"));

    private static final String LEASE_COLUMNS =
            "id, channel_id, link_id, generation, status, confirmed_seq, schedule_version,"
                    + " cutover_seq, order_id, created_at_ms, ended_at_ms";

    // ---------- 链路 ----------

    public void insertLink(String channelId, String linkId, String role, long updatedAtMs) {
        jdbc.update("INSERT INTO playout_channel_link"
                        + " (channel_id, link_id, role, healthy, cached_version, updated_at_ms)"
                        + " VALUES (?, ?, ?, 0, 0, ?)",
                channelId, linkId, role, updatedAtMs);
    }

    public Optional<LinkRow> findLink(String channelId, String linkId) {
        return jdbc.query("SELECT channel_id, link_id, role, healthy, cached_version, updated_at_ms"
                        + " FROM playout_channel_link WHERE channel_id = ? AND link_id = ?",
                LINK_MAPPER, channelId, linkId).stream().findFirst();
    }

    /** 按主键查询链路并加行锁。 */
    public Optional<LinkRow> findLinkForUpdate(String channelId, String linkId) {
        return jdbc.query("SELECT channel_id, link_id, role, healthy, cached_version, updated_at_ms"
                        + " FROM playout_channel_link WHERE channel_id = ? AND link_id = ? FOR UPDATE",
                LINK_MAPPER, channelId, linkId).stream().findFirst();
    }

    public List<LinkRow> findLinks(String channelId) {
        return jdbc.query("SELECT channel_id, link_id, role, healthy, cached_version, updated_at_ms"
                        + " FROM playout_channel_link WHERE channel_id = ? ORDER BY role",
                LINK_MAPPER, channelId);
    }

    /** 更新健康与缓存版本；返回受影响行数，0 表示链路不存在。 */
    public int updateLinkReport(String channelId, String linkId, boolean healthy,
                                long cachedVersion, long updatedAtMs) {
        return jdbc.update("UPDATE playout_channel_link SET healthy = ?, cached_version = ?,"
                        + " updated_at_ms = ? WHERE channel_id = ? AND link_id = ?",
                healthy, cachedVersion, updatedAtMs, channelId, linkId);
    }

    // ---------- 插播同步 ----------

    /** 插入或更新某链路对某插播的同步状态为已同步。 */
    public void upsertOverrideSynced(String channelId, String linkId, String overrideKey,
                                     long updatedAtMs) {
        jdbc.update("INSERT INTO playout_link_override_sync"
                        + " (channel_id, link_id, override_key, synced, updated_at_ms)"
                        + " VALUES (?, ?, ?, 1, ?)"
                        + " ON DUPLICATE KEY UPDATE synced = 1, updated_at_ms = VALUES(updated_at_ms)",
                channelId, linkId, overrideKey, updatedAtMs);
    }

    /** 查询某链路对某插播的同步状态；无记录视为未同步。 */
    public boolean isOverrideSynced(String channelId, String linkId, String overrideKey) {
        List<Boolean> rows = jdbc.queryForList("SELECT synced FROM playout_link_override_sync"
                        + " WHERE channel_id = ? AND link_id = ? AND override_key = ?",
                Boolean.class, channelId, linkId, overrideKey);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    // ---------- 租约 ----------

    public Optional<LeaseRow> findActiveLease(String channelId) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_link_lease"
                        + " WHERE channel_id = ? AND status = 'ACTIVE'",
                LEASE_MAPPER, channelId).stream().findFirst();
    }

    /** 查询当前 ACTIVE 租约并加行锁，切换激活时用于结束旧租约并与并发操作串行。 */
    public Optional<LeaseRow> findActiveLeaseForUpdate(String channelId) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_link_lease"
                        + " WHERE channel_id = ? AND status = 'ACTIVE' FOR UPDATE",
                LEASE_MAPPER, channelId).stream().findFirst();
    }

    public Optional<LeaseRow> findLeaseByGeneration(String channelId, long generation) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_link_lease"
                        + " WHERE channel_id = ? AND generation = ?",
                LEASE_MAPPER, channelId, generation).stream().findFirst();
    }

    public Optional<LeaseRow> findLeaseByGenerationForUpdate(String channelId, long generation) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_link_lease"
                        + " WHERE channel_id = ? AND generation = ? FOR UPDATE",
                LEASE_MAPPER, channelId, generation).stream().findFirst();
    }

    /** 频道当前最大世代，无租约时为 0。 */
    public long maxGeneration(String channelId) {
        Long gen = jdbc.queryForObject("SELECT MAX(generation) FROM playout_link_lease"
                + " WHERE channel_id = ?", Long.class, channelId);
        return gen == null ? 0L : gen;
    }

    /**
     * 插入新租约。同频道已存在 ACTIVE 租约时由 uk_active_slot 唯一索引拒绝
     * （调用方必须先在同一事务结束旧租约）。
     */
    public void insertLease(String channelId, String linkId, long generation, long confirmedSeq,
                            long scheduleVersion, Long cutoverSeq, Long orderId, long createdAtMs) {
        jdbc.update("INSERT INTO playout_link_lease"
                        + " (channel_id, link_id, generation, status, confirmed_seq,"
                        + "  schedule_version, cutover_seq, order_id, created_at_ms)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
                channelId, linkId, generation, confirmedSeq, scheduleVersion,
                cutoverSeq, orderId, createdAtMs);
    }

    /** 结束租约（ACTIVE -> ENDED）；返回受影响行数，0 表示不存在或已结束。 */
    public int endLease(long leaseId, long endedAtMs) {
        return jdbc.update("UPDATE playout_link_lease SET status = 'ENDED', ended_at_ms = ?"
                + " WHERE id = ? AND status = 'ACTIVE'", endedAtMs, leaseId);
    }

    /** 把 ACTIVE 租约游标推进到 newConfirmedSeq（仅允许增大）。 */
    public int advanceLeaseConfirmedSeq(long leaseId, long newConfirmedSeq) {
        return jdbc.update("UPDATE playout_link_lease SET confirmed_seq = ?"
                + " WHERE id = ? AND status = 'ACTIVE' AND confirmed_seq < ?",
                newConfirmedSeq, leaseId, newConfirmedSeq);
    }

    // ---------- 回执 ----------

    public void insertReceipt(String channelId, String linkId, long generation, long seq,
                              String disposition, long receivedAtMs) {
        jdbc.update("INSERT INTO playout_link_receipt"
                        + " (channel_id, link_id, generation, seq, disposition, received_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                channelId, linkId, generation, seq, disposition, receivedAtMs);
    }

    public Optional<ReceiptRow> findReceipt(String channelId, String linkId, long generation, long seq) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, seq, disposition, received_at_ms"
                        + " FROM playout_link_receipt"
                        + " WHERE channel_id = ? AND link_id = ? AND generation = ? AND seq = ?",
                RECEIPT_MAPPER, channelId, linkId, generation, seq).stream().findFirst();
    }

    /**
     * 某链路已缓存回执的最长连续前缀 1..k（跨世代、按 seq 去重）：
     * sequence 空间为频道级并跨世代连续递增，链路在旧世代亲自播放/缓存的内容同样属于其证据。
     * 逐条探测，序号集合通常很小。
     */
    public long contiguousReceiptSeq(String channelId, String linkId) {
        long k = 0;
        while (receiptCount(channelId, linkId, k + 1) > 0) {
            k++;
        }
        return k;
    }

    private int receiptCount(String channelId, String linkId, long seq) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM playout_link_receipt"
                        + " WHERE channel_id = ? AND link_id = ? AND seq = ?",
                Integer.class, channelId, linkId, seq);
        return count == null ? 0 : count;
    }

    /**
     * 某链路已缓存回执中的最大 seq（跨世代），无回执时为 0；切换单创建时作为目标最后回执 sequence。
     */
    public long maxReceiptSeq(String channelId, String linkId) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM playout_link_receipt"
                + " WHERE channel_id = ? AND link_id = ?",
                Long.class, channelId, linkId);
        return max == null ? 0L : max;
    }

    /**
     * 目标已缓存回执流中的缺口序号（升序，跨世代按 seq 去重）：
     * (contiguousSeq, maxReceiptSeq] 内缺失的序号。即目标收到过更大的 seq 但中间有空洞
     * （区别于只是尚未收到后续 seq 的“落后”）。
     */
    public List<Long> findReceiptHoles(String channelId, String linkId,
                                       long contiguousSeq, long maxReceiptSeq) {
        List<Long> present = jdbc.queryForList("SELECT DISTINCT seq FROM playout_link_receipt"
                        + " WHERE channel_id = ? AND link_id = ? AND seq > ? AND seq <= ?"
                        + " ORDER BY seq",
                Long.class, channelId, linkId, contiguousSeq, maxReceiptSeq);
        java.util.Set<Long> presentSet = new java.util.HashSet<>(present);
        List<Long> holes = new java.util.ArrayList<>();
        for (long s = contiguousSeq + 1; s <= maxReceiptSeq; s++) {
            if (!presentSet.contains(s)) {
                holes.add(s);
            }
        }
        return holes;
    }

    /** 已存档的某世代之前（不含）LATE 回执，按到达顺序。 */
    public List<ReceiptRow> findLateReceipts(String channelId, long beforeGeneration) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, seq, disposition, received_at_ms"
                        + " FROM playout_link_receipt"
                        + " WHERE channel_id = ? AND disposition = 'LATE' AND generation < ?"
                        + " ORDER BY received_at_ms, id",
                RECEIPT_MAPPER, channelId, beforeGeneration);
    }

    // ---------- 切换单 ----------

    public long insertFailoverOrder(String failoverKey, String channelId, long channelVersion,
                                    String sourceLinkId, String targetLinkId, long sourceLastSeq,
                                    long targetLastSeq, long cutoverAtMs, long maxLag,
                                    long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_failover_order"
                            + " (failover_key, channel_id, channel_version, source_link_id,"
                            + "  target_link_id, source_last_seq, target_last_seq, cutover_at_ms,"
                            + "  max_lag, status, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, failoverKey);
            ps.setString(2, channelId);
            ps.setLong(3, channelVersion);
            ps.setString(4, sourceLinkId);
            ps.setString(5, targetLinkId);
            ps.setLong(6, sourceLastSeq);
            ps.setLong(7, targetLastSeq);
            ps.setLong(8, cutoverAtMs);
            ps.setLong(9, maxLag);
            ps.setLong(10, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FailoverOrderRow> findFailoverOrder(String failoverKey) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM playout_failover_order"
                        + " WHERE failover_key = ?",
                ORDER_MAPPER, failoverKey).stream().findFirst();
    }

    public Optional<FailoverOrderRow> findFailoverOrderForUpdate(String failoverKey) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM playout_failover_order"
                        + " WHERE failover_key = ? FOR UPDATE",
                ORDER_MAPPER, failoverKey).stream().findFirst();
    }

    /** 激活成功：冻结切点、编排版本、插播栈快照，写入新世代并置 ACTIVATED。 */
    public int markOrderActivated(long orderId, long generation, long safeCutSeq,
                                  long scheduleVersion, String frozenStackJson, long activatedAtMs) {
        return jdbc.update("UPDATE playout_failover_order"
                        + " SET status = 'ACTIVATED', generation = ?, safe_cut_seq = ?,"
                        + "     schedule_version = ?, frozen_stack_json = ?, activated_at_ms = ?"
                        + " WHERE id = ? AND status = 'CREATED'",
                generation, safeCutSeq, scheduleVersion, frozenStackJson, activatedAtMs, orderId);
    }

    private static final String ORDER_COLUMNS =
            "id, failover_key, channel_id, channel_version, source_link_id, target_link_id,"
                    + " source_last_seq, target_last_seq, cutover_at_ms, max_lag, status,"
                    + " generation, safe_cut_seq, schedule_version, frozen_stack_json,"
                    + " created_at_ms, activated_at_ms";
}
