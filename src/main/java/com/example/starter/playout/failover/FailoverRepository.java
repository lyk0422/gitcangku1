package com.example.starter.playout.failover;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 主备链路、租约、切换单与回执的数据访问层。时间列均为 UTC 纪元毫秒（BIGINT）。
 */
@Repository
public class FailoverRepository {

    private final JdbcTemplate jdbc;

    public FailoverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 链路配置行；cachedOverrideSignature 为链路已同步未决插播栈签名（NULL 表示未上报）。 */
    public record LinkRow(String channelId, String role, String linkId, boolean healthy,
                          long cachedScheduleVersion, String cachedOverrideSignature,
                          long updatedAtMs) {
    }

    /** 租约行。 */
    public record LeaseRow(long id, String channelId, String linkId, long generation,
                           String status, long cutSequence, String scheduleSnapshot,
                           String overrideSnapshot, long startedAtMs, Long endedAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 切换单行。 */
    public record FailoverOrderRow(String failoverKey, String channelId, long channelVersion,
                                   String sourceLinkId, String targetLinkId,
                                   long sourceLastSequence, long targetLastSequence,
                                   long cutoverAtMs, String status, String rejectCode,
                                   Long newGeneration, Long cutSequence,
                                   String createdRequestId, long createdAtMs, Long activatedAtMs) {
    }

    /** 回执行。 */
    public record ReceiptRow(long id, String channelId, String linkId, long generation,
                             long sequenceNo, long receivedAtMs, String disposition) {
    }

    /** 频道各业务日最新发布版本行。 */
    public record PublicationVersionRow(LocalDate businessDay, long publicationId,
                                        long publishedVersion) {
    }

    /** 未决（ACTIVE）紧急插播的最小信息行。 */
    public record ActiveOverrideRow(String overrideKey, int priority, long startMs, long endMs) {
    }

    private static final RowMapper<LinkRow> LINK_MAPPER = (rs, n) ->
            new LinkRow(rs.getString("channel_id"), rs.getString("role"), rs.getString("link_id"),
                    rs.getBoolean("healthy"), rs.getLong("cached_schedule_version"),
                    rs.getString("cached_override_signature"), rs.getLong("updated_at_ms"));

    private static final RowMapper<LeaseRow> LEASE_MAPPER = (rs, n) ->
            new LeaseRow(rs.getLong("id"), rs.getString("channel_id"), rs.getString("link_id"),
                    rs.getLong("generation"), rs.getString("status"), rs.getLong("cut_sequence"),
                    rs.getString("schedule_snapshot"), rs.getString("override_snapshot"),
                    rs.getLong("started_at_ms"), (Long) rs.getObject("ended_at_ms"));

    private static final RowMapper<FailoverOrderRow> ORDER_MAPPER = (rs, n) ->
            new FailoverOrderRow(rs.getString("failover_key"), rs.getString("channel_id"),
                    rs.getLong("channel_version"), rs.getString("source_link_id"),
                    rs.getString("target_link_id"), rs.getLong("source_last_sequence"),
                    rs.getLong("target_last_sequence"), rs.getLong("cutover_at_ms"),
                    rs.getString("status"), rs.getString("reject_code"),
                    (Long) rs.getObject("new_generation"), (Long) rs.getObject("cut_sequence"),
                    rs.getString("created_request_id"), rs.getLong("created_at_ms"),
                    (Long) rs.getObject("activated_at_ms"));

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, n) ->
            new ReceiptRow(rs.getLong("id"), rs.getString("channel_id"), rs.getString("link_id"),
                    rs.getLong("generation"), rs.getLong("sequence_no"),
                    rs.getLong("received_at_ms"), rs.getString("disposition"));

    private static final RowMapper<PublicationVersionRow> PUBLICATION_VERSION_MAPPER = (rs, n) ->
            new PublicationVersionRow(rs.getDate("business_day").toLocalDate(),
                    rs.getLong("publication_id"), rs.getLong("published_version"));

    private static final RowMapper<ActiveOverrideRow> ACTIVE_OVERRIDE_MAPPER = (rs, n) ->
            new ActiveOverrideRow(rs.getString("override_key"), rs.getInt("priority"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"));

    // ---------- 链路 ----------

    public void insertLink(String channelId, String role, String linkId, long nowMs) {
        jdbc.update("INSERT INTO playout_link"
                        + " (channel_id, role, link_id, healthy, cached_schedule_version,"
                        + "  cached_override_signature, updated_at_ms)"
                        + " VALUES (?, ?, ?, 1, 0, NULL, ?)",
                channelId, role, linkId, nowMs);
    }

    public List<LinkRow> findLinks(String channelId) {
        return jdbc.query("SELECT channel_id, role, link_id, healthy, cached_schedule_version,"
                        + " cached_override_signature, updated_at_ms FROM playout_link"
                        + " WHERE channel_id = ? ORDER BY role",
                LINK_MAPPER, channelId);
    }

    public Optional<LinkRow> findLink(String channelId, String linkId) {
        return jdbc.query("SELECT channel_id, role, link_id, healthy, cached_schedule_version,"
                        + " cached_override_signature, updated_at_ms FROM playout_link"
                        + " WHERE channel_id = ? AND link_id = ?",
                LINK_MAPPER, channelId, linkId).stream().findFirst();
    }

    public Optional<LinkRow> findLinkForUpdate(String channelId, String linkId) {
        return jdbc.query("SELECT channel_id, role, link_id, healthy, cached_schedule_version,"
                        + " cached_override_signature, updated_at_ms FROM playout_link"
                        + " WHERE channel_id = ? AND link_id = ? FOR UPDATE",
                LINK_MAPPER, channelId, linkId).stream().findFirst();
    }

    /** 锁定频道全部链路行，切换事务内与链路状态上报串行。 */
    public List<LinkRow> findLinksForUpdate(String channelId) {
        return jdbc.query("SELECT channel_id, role, link_id, healthy, cached_schedule_version,"
                        + " cached_override_signature, updated_at_ms FROM playout_link"
                        + " WHERE channel_id = ? ORDER BY role FOR UPDATE",
                LINK_MAPPER, channelId);
    }

    public int updateLinkState(String channelId, String linkId, boolean healthy,
                               long cachedScheduleVersion, String cachedOverrideSignature,
                               long nowMs) {
        return jdbc.update("UPDATE playout_link SET healthy = ?, cached_schedule_version = ?,"
                        + " cached_override_signature = ?, updated_at_ms = ?"
                        + " WHERE channel_id = ? AND link_id = ?",
                healthy, cachedScheduleVersion, cachedOverrideSignature, nowMs,
                channelId, linkId);
    }

    // ---------- 租约 ----------

    public long insertLease(String channelId, String linkId, long generation, long cutSequence,
                            String scheduleSnapshot, String overrideSnapshot, long nowMs) {
        return jdbc.update("INSERT INTO playout_lease"
                        + " (channel_id, link_id, generation, status, active_marker, cut_sequence,"
                        + "  schedule_snapshot, override_snapshot, started_at_ms)"
                        + " VALUES (?, ?, ?, 'ACTIVE', 'A', ?, ?, ?, ?)",
                channelId, linkId, generation, cutSequence,
                scheduleSnapshot, overrideSnapshot, nowMs);
    }

    public Optional<LeaseRow> findActiveLeaseForUpdate(String channelId) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, status, cut_sequence,"
                        + " schedule_snapshot, override_snapshot, started_at_ms, ended_at_ms"
                        + " FROM playout_lease WHERE channel_id = ? AND status = 'ACTIVE' FOR UPDATE",
                LEASE_MAPPER, channelId).stream().findFirst();
    }

    public Optional<LeaseRow> findActiveLease(String channelId) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, status, cut_sequence,"
                        + " schedule_snapshot, override_snapshot, started_at_ms, ended_at_ms"
                        + " FROM playout_lease WHERE channel_id = ? AND status = 'ACTIVE'",
                LEASE_MAPPER, channelId).stream().findFirst();
    }

    public List<LeaseRow> findLeases(String channelId) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, status, cut_sequence,"
                        + " schedule_snapshot, override_snapshot, started_at_ms, ended_at_ms"
                        + " FROM playout_lease WHERE channel_id = ? ORDER BY generation",
                LEASE_MAPPER, channelId);
    }

    public long maxGeneration(String channelId) {
        Long max = jdbc.queryForObject(
                "SELECT MAX(generation) FROM playout_lease WHERE channel_id = ?",
                Long.class, channelId);
        return max == null ? 0L : max;
    }

    public int endLease(long leaseId, long endedAtMs) {
        return jdbc.update("UPDATE playout_lease SET status = 'ENDED', active_marker = NULL,"
                + " ended_at_ms = ? WHERE id = ? AND status = 'ACTIVE'", endedAtMs, leaseId);
    }

    // ---------- 切换单 ----------

    public void insertActivatedOrder(FailoverOrderRow row) {
        jdbc.update("INSERT INTO playout_failover_order"
                        + " (failover_key, channel_id, channel_version, source_link_id,"
                        + "  target_link_id, source_last_sequence, target_last_sequence,"
                        + "  cutover_at_ms, status, reject_code, new_generation, cut_sequence,"
                        + "  created_request_id, created_at_ms, activated_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVATED', NULL, ?, ?, ?, ?, ?)",
                row.failoverKey(), row.channelId(), row.channelVersion(),
                row.sourceLinkId(), row.targetLinkId(),
                row.sourceLastSequence(), row.targetLastSequence(), row.cutoverAtMs(),
                row.newGeneration(), row.cutSequence(),
                row.createdRequestId(), row.createdAtMs(), row.activatedAtMs());
    }

    public Optional<FailoverOrderRow> findOrder(String failoverKey) {
        return jdbc.query("SELECT failover_key, channel_id, channel_version, source_link_id,"
                        + " target_link_id, source_last_sequence, target_last_sequence,"
                        + " cutover_at_ms, status, reject_code, new_generation, cut_sequence,"
                        + " created_request_id, created_at_ms, activated_at_ms"
                        + " FROM playout_failover_order WHERE failover_key = ?",
                ORDER_MAPPER, failoverKey).stream().findFirst();
    }

    // ---------- 回执 ----------

    public long insertReceipt(String channelId, String linkId, long generation, long sequence,
                              String disposition, long nowMs) {
        return jdbc.update("INSERT INTO playout_receipt"
                        + " (channel_id, link_id, generation, sequence_no, received_at_ms, disposition)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                channelId, linkId, generation, sequence, nowMs, disposition);
    }

    public List<ReceiptRow> findReceipts(String channelId, String linkId, long generation) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, sequence_no,"
                        + " received_at_ms, disposition FROM playout_receipt"
                        + " WHERE channel_id = ? AND link_id = ? AND generation = ?"
                        + " ORDER BY sequence_no",
                RECEIPT_MAPPER, channelId, linkId, generation);
    }

    public boolean existsReceipt(String channelId, long generation, long sequence) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM playout_receipt"
                + " WHERE channel_id = ? AND generation = ? AND sequence_no = ?",
                Integer.class, channelId, generation, sequence);
        return count != null && count > 0;
    }

    public Optional<ReceiptRow> findReceipt(String channelId, long generation, long sequence) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, sequence_no,"
                        + " received_at_ms, disposition FROM playout_receipt"
                        + " WHERE channel_id = ? AND generation = ? AND sequence_no = ?",
                RECEIPT_MAPPER, channelId, generation, sequence).stream().findFirst();
    }

    /** 查询全部 LATE 回执（含旧世代与非活动链路），按到达顺序返回。 */
    public List<ReceiptRow> findLateReceipts(String channelId) {
        return jdbc.query("SELECT id, channel_id, link_id, generation, sequence_no,"
                        + " received_at_ms, disposition FROM playout_receipt"
                        + " WHERE channel_id = ? AND disposition = 'LATE'"
                        + " ORDER BY received_at_ms, id",
                RECEIPT_MAPPER, channelId);
    }

    // ---------- 编排与插播证据（只读） ----------

    /** 每个业务日最新一次发布（published_version 最大）。 */
    public List<PublicationVersionRow> findPublicationVersions(String channelId) {
        return jdbc.query("SELECT p.business_day AS business_day, p.id AS publication_id,"
                        + " p.published_version AS published_version"
                        + " FROM playout_publication p"
                        + " INNER JOIN ("
                        + "   SELECT business_day, MAX(published_version) AS max_version"
                        + "   FROM playout_publication WHERE channel_id = ? GROUP BY business_day"
                        + " ) m ON p.business_day = m.business_day"
                        + " AND p.published_version = m.max_version"
                        + " WHERE p.channel_id = ? ORDER BY p.business_day",
                PUBLICATION_VERSION_MAPPER, channelId, channelId);
    }

    /** 频道当前未决（ACTIVE）紧急插播，按优先级降序、开始升序排列（与播出决定口径一致）。 */
    public List<ActiveOverrideRow> findActiveOverrides(String channelId) {
        return jdbc.query("SELECT override_key, priority, start_ms, end_ms"
                        + " FROM playout_emergency_override"
                        + " WHERE channel_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY priority DESC, start_ms ASC, override_key ASC",
                ACTIVE_OVERRIDE_MAPPER, channelId);
    }

    /** 锁定频道全部 ACTIVE 插播行，切换事务内与插播开始/结束并发串行。 */
    public List<ActiveOverrideRow> findActiveOverridesForUpdate(String channelId) {
        return jdbc.query("SELECT override_key, priority, start_ms, end_ms"
                        + " FROM playout_emergency_override"
                        + " WHERE channel_id = ? AND status = 'ACTIVE' FOR UPDATE",
                ACTIVE_OVERRIDE_MAPPER, channelId);
    }
}
