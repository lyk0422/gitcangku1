package com.example.starter.playout;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 播出编排数据访问层。所有时间列均为 UTC 纪元毫秒（BIGINT），业务日为 Asia/Shanghai 日历日。
 */
@Repository
public class PlayoutRepository {

    private final JdbcTemplate jdbc;

    public PlayoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 素材行。 */
    public record AssetRow(String id, long durationMs) {
    }

    /** 频道行。 */
    public record ChannelRow(String id, String fallbackAssetId) {
    }

    /** 授权行。 */
    public record GrantRow(long id, String channelId, String assetId,
                           long validFromMs, long validToMs, boolean revoked) {
    }

    /** 草稿行。 */
    public record DraftRow(String channelId, LocalDate businessDay, long version) {
    }

    /** 草稿片段行。 */
    public record SegmentRow(String id, String assetId, long startMs, long endMs) {
    }

    /** 发布快照行。 */
    public record PublicationRow(long id, String channelId, LocalDate businessDay,
                                 long publishedVersion, long draftVersion) {
    }

    /** 发布快照片段行，grantId 为发布时选定的覆盖授权。 */
    public record PublicationSegmentRow(long id, long publicationId, String segmentId,
                                        String assetId, long grantId,
                                        long startMs, long endMs) {
    }

    /** 幂等请求记录行；responseBody 为 NULL 表示尚未成功完成（同事务回滚后记录不保留）。 */
    public record RequestRow(String requestId, String operation, String paramsHash,
                             String responseBody) {
    }

    /** 紧急插播行；status 为 ACTIVE / CANCELLED，grantId 始终保留创建时指定的原授权关联。 */
    public record OverrideRow(String overrideKey, String channelId, String assetId, long grantId,
                              int priority, long startMs, long endMs, LocalDate businessDay,
                              String status, String cancelRequestId, Long cancelledAtMs,
                              long createdAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private static final RowMapper<AssetRow> ASSET_MAPPER = (rs, n) ->
            new AssetRow(rs.getString("id"), rs.getLong("duration_ms"));

    private static final RowMapper<ChannelRow> CHANNEL_MAPPER = (rs, n) ->
            new ChannelRow(rs.getString("id"), rs.getString("fallback_asset_id"));

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, n) ->
            new GrantRow(rs.getLong("id"), rs.getString("channel_id"), rs.getString("asset_id"),
                    rs.getLong("valid_from_ms"), rs.getLong("valid_to_ms"), rs.getBoolean("revoked"));

    private static final RowMapper<DraftRow> DRAFT_MAPPER = (rs, n) ->
            new DraftRow(rs.getString("channel_id"), rs.getDate("business_day").toLocalDate(),
                    rs.getLong("version"));

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) ->
            new SegmentRow(rs.getString("id"), rs.getString("asset_id"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"));

    private static final RowMapper<PublicationRow> PUBLICATION_MAPPER = (rs, n) ->
            new PublicationRow(rs.getLong("id"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(),
                    rs.getLong("published_version"), rs.getLong("draft_version"));

    private static final RowMapper<PublicationSegmentRow> PUBLICATION_SEGMENT_MAPPER = (rs, n) ->
            new PublicationSegmentRow(rs.getLong("id"), rs.getLong("publication_id"),
                    rs.getString("segment_id"), rs.getString("asset_id"), rs.getLong("grant_id"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) ->
            new RequestRow(rs.getString("request_id"), rs.getString("operation"),
                    rs.getString("params_hash"), rs.getString("response_body"));

    private static final RowMapper<OverrideRow> OVERRIDE_MAPPER = (rs, n) ->
            new OverrideRow(rs.getString("override_key"), rs.getString("channel_id"),
                    rs.getString("asset_id"), rs.getLong("grant_id"), rs.getInt("priority"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"),
                    rs.getDate("business_day").toLocalDate(), rs.getString("status"),
                    rs.getString("cancel_request_id"),
                    (Long) rs.getObject("cancelled_at_ms"), rs.getLong("created_at_ms"));

    // ---------- 素材 ----------

    public void insertAsset(String id, long durationMs, long createdAtMs) {
        jdbc.update("INSERT INTO playout_asset (id, duration_ms, created_at_ms) VALUES (?, ?, ?)",
                id, durationMs, createdAtMs);
    }

    public Optional<AssetRow> findAsset(String id) {
        return jdbc.query("SELECT id, duration_ms FROM playout_asset WHERE id = ?",
                ASSET_MAPPER, id).stream().findFirst();
    }

    // ---------- 频道 ----------

    public void insertChannel(String id, String fallbackAssetId, long createdAtMs) {
        jdbc.update("INSERT INTO playout_channel (id, fallback_asset_id, created_at_ms) VALUES (?, ?, ?)",
                id, fallbackAssetId, createdAtMs);
    }

    public Optional<ChannelRow> findChannel(String id) {
        return jdbc.query("SELECT id, fallback_asset_id FROM playout_channel WHERE id = ?",
                CHANNEL_MAPPER, id).stream().findFirst();
    }

    // ---------- 授权 ----------

    public long insertGrant(String channelId, String assetId, long validFromMs, long validToMs,
                            long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_grant (channel_id, asset_id, valid_from_ms, valid_to_ms, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setString(2, assetId);
            ps.setLong(3, validFromMs);
            ps.setLong(4, validToMs);
            ps.setLong(5, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<GrantRow> findGrant(long id) {
        return jdbc.query("SELECT id, channel_id, asset_id, valid_from_ms, valid_to_ms, revoked"
                + " FROM playout_grant WHERE id = ?", GRANT_MAPPER, id).stream().findFirst();
    }

    /** 按 ID 查询授权并加行锁，用于紧急插播创建与授权撤销按提交顺序串行化。 */
    public Optional<GrantRow> findGrantForUpdate(long id) {
        return jdbc.query("SELECT id, channel_id, asset_id, valid_from_ms, valid_to_ms, revoked"
                + " FROM playout_grant WHERE id = ? FOR UPDATE", GRANT_MAPPER, id)
                .stream().findFirst();
    }

    /** 撤销授权；返回受影响行数，0 表示不存在或已撤销。 */
    public int revokeGrant(long id, String revokeRequestId, long revokedAtMs) {
        return jdbc.update("UPDATE playout_grant SET revoked = 1, revoke_request_id = ?, revoked_at_ms = ?"
                + " WHERE id = ? AND revoked = 0", revokeRequestId, revokedAtMs, id);
    }

    /** 查找完整覆盖 [startMs, endMs) 的未撤销授权（区间左闭右开）。 */
    public List<GrantRow> findCoveringGrants(String channelId, String assetId,
                                             long startMs, long endMs) {
        return jdbc.query("SELECT id, channel_id, asset_id, valid_from_ms, valid_to_ms, revoked"
                        + " FROM playout_grant"
                        + " WHERE channel_id = ? AND asset_id = ? AND revoked = 0"
                        + " AND valid_from_ms <= ? AND valid_to_ms >= ?",
                GRANT_MAPPER, channelId, assetId, startMs, endMs);
    }

    /** 同上，但加行锁（FOR UPDATE），用于发布时与撤销串行化。 */
    public List<GrantRow> findCoveringGrantsForUpdate(String channelId, String assetId,
                                                      long startMs, long endMs) {
        return jdbc.query("SELECT id, channel_id, asset_id, valid_from_ms, valid_to_ms, revoked"
                        + " FROM playout_grant"
                        + " WHERE channel_id = ? AND asset_id = ?"
                        + " AND valid_from_ms <= ? AND valid_to_ms >= ?"
                        + " ORDER BY id FOR UPDATE",
                GRANT_MAPPER, channelId, assetId, startMs, endMs);
    }

    // ---------- 草稿 ----------

    public Optional<DraftRow> findDraft(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT channel_id, business_day, version FROM playout_draft"
                        + " WHERE channel_id = ? AND business_day = ?",
                DRAFT_MAPPER, channelId, Date.valueOf(businessDay)).stream().findFirst();
    }

    public void insertDraft(String channelId, LocalDate businessDay, long updatedAtMs) {
        jdbc.update("INSERT INTO playout_draft (channel_id, business_day, version, updated_at_ms)"
                        + " VALUES (?, ?, 1, ?)",
                channelId, Date.valueOf(businessDay), updatedAtMs);
    }

    /** 乐观锁递增草稿版本；返回受影响行数，0 表示版本冲突或不存在。 */
    public int bumpDraftVersion(String channelId, LocalDate businessDay,
                                long expectedVersion, long updatedAtMs) {
        return jdbc.update("UPDATE playout_draft SET version = version + 1, updated_at_ms = ?"
                        + " WHERE channel_id = ? AND business_day = ? AND version = ?",
                updatedAtMs, channelId, Date.valueOf(businessDay), expectedVersion);
    }

    public void deleteDraftSegments(String channelId, LocalDate businessDay) {
        jdbc.update("DELETE FROM playout_draft_segment WHERE channel_id = ? AND business_day = ?",
                channelId, Date.valueOf(businessDay));
    }

    public void insertDraftSegment(String id, String channelId, LocalDate businessDay,
                                   String assetId, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_draft_segment"
                        + " (id, channel_id, business_day, asset_id, start_ms, end_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                id, channelId, Date.valueOf(businessDay), assetId, startMs, endMs);
    }

    public List<SegmentRow> findDraftSegments(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT id, asset_id, start_ms, end_ms FROM playout_draft_segment"
                        + " WHERE channel_id = ? AND business_day = ? ORDER BY start_ms, id",
                SEGMENT_MAPPER, channelId, Date.valueOf(businessDay));
    }

    // ---------- 发布快照 ----------

    /** 当前发布版本，无发布记录时为 0。 */
    public long currentPublishedVersion(String channelId, LocalDate businessDay) {
        Long version = jdbc.queryForObject(
                "SELECT MAX(published_version) FROM playout_publication"
                        + " WHERE channel_id = ? AND business_day = ?",
                Long.class, channelId, Date.valueOf(businessDay));
        return version == null ? 0L : version;
    }

    public long insertPublication(String channelId, LocalDate businessDay, long publishedVersion,
                                  long draftVersion, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_publication"
                            + " (channel_id, business_day, published_version, draft_version, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setDate(2, Date.valueOf(businessDay));
            ps.setLong(3, publishedVersion);
            ps.setLong(4, draftVersion);
            ps.setLong(5, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertPublicationSegment(long publicationId, String segmentId, String assetId,
                                         long grantId, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_publication_segment"
                        + " (publication_id, segment_id, asset_id, grant_id, start_ms, end_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                publicationId, segmentId, assetId, grantId, startMs, endMs);
    }

    /** 最新一次发布快照。 */
    public Optional<PublicationRow> findLatestPublication(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version"
                        + " FROM playout_publication"
                        + " WHERE channel_id = ? AND business_day = ?"
                        + " ORDER BY published_version DESC LIMIT 1",
                PUBLICATION_MAPPER, channelId, Date.valueOf(businessDay)).stream().findFirst();
    }

    /** 快照中覆盖指定时刻的片段（start <= at < end）。 */
    public Optional<PublicationSegmentRow> findPublicationSegmentAt(long publicationId, long atMs) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, start_ms, end_ms"
                        + " FROM playout_publication_segment"
                        + " WHERE publication_id = ? AND start_ms <= ? AND end_ms > ?"
                        + " ORDER BY start_ms LIMIT 1",
                PUBLICATION_SEGMENT_MAPPER, publicationId, atMs, atMs).stream().findFirst();
    }

    /** 快照的全部片段，按播出开始时间与分段 ID 排序（租约快照顺序来源）。 */
    public List<PublicationSegmentRow> findPublicationSegments(long publicationId) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, start_ms, end_ms"
                        + " FROM playout_publication_segment"
                        + " WHERE publication_id = ? ORDER BY start_ms, segment_id",
                PUBLICATION_SEGMENT_MAPPER, publicationId);
    }

    // ---------- 幂等请求记录 ----------

    /** 按请求 ID 查询并加行锁，用于去重判定。 */
    public Optional<RequestRow> findRequestForUpdate(String requestId) {
        return jdbc.query("SELECT request_id, operation, params_hash, response_body"
                        + " FROM playout_request WHERE request_id = ? FOR UPDATE",
                REQUEST_MAPPER, requestId).stream().findFirst();
    }

    public void insertRequest(String requestId, String operation, String paramsHash, long createdAtMs) {
        jdbc.update("INSERT INTO playout_request (request_id, operation, params_hash, created_at_ms)"
                + " VALUES (?, ?, ?, ?)", requestId, operation, paramsHash, createdAtMs);
    }

    public void completeRequest(String requestId, String responseBody) {
        jdbc.update("UPDATE playout_request SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }

    /** 判断是否为唯一键冲突（草稿首建、发布版本、请求 ID 等并发场景）。 */
    public static boolean isDuplicateKey(RuntimeException ex) {
        return ex instanceof DuplicateKeyException;
    }

    // ---------- 紧急插播 ----------

    public void insertOverride(String overrideKey, String channelId, String assetId, long grantId,
                               int priority, long startMs, long endMs, LocalDate businessDay,
                               long createdAtMs) {
        jdbc.update("INSERT INTO playout_emergency_override"
                        + " (override_key, channel_id, asset_id, grant_id, priority, start_ms, end_ms,"
                        + "  business_day, status, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                overrideKey, channelId, assetId, grantId, priority, startMs, endMs,
                Date.valueOf(businessDay), createdAtMs);
    }

    public Optional<OverrideRow> findOverride(String overrideKey) {
        return jdbc.query("SELECT override_key, channel_id, asset_id, grant_id, priority, start_ms,"
                        + " end_ms, business_day, status, cancel_request_id, cancelled_at_ms, created_at_ms"
                        + " FROM playout_emergency_override WHERE override_key = ?",
                OVERRIDE_MAPPER, overrideKey).stream().findFirst();
    }

    /** 按全局键查询并加行锁。 */
    public Optional<OverrideRow> findOverrideForUpdate(String overrideKey) {
        return jdbc.query("SELECT override_key, channel_id, asset_id, grant_id, priority, start_ms,"
                        + " end_ms, business_day, status, cancel_request_id, cancelled_at_ms, created_at_ms"
                        + " FROM playout_emergency_override WHERE override_key = ? FOR UPDATE",
                OVERRIDE_MAPPER, overrideKey).stream().findFirst();
    }

    /** 锁定频道行，串行化同频道的插播创建/取消，避免并发区间漏判（H2 无间隙锁，MySQL 下同样安全）。 */
    public void lockChannelForUpdate(String channelId) {
        jdbc.queryForList("SELECT id FROM playout_channel WHERE id = ? FOR UPDATE", channelId);
    }

    /**
     * 同频道、同优先级、状态 ACTIVE 且与 [startMs, endMs) 相交的插播（区间左闭右开：
     * 相邻 start == end 不算相交）。须在持频道锁后调用。
     */
    public List<OverrideRow> findActiveOverlappingForUpdate(String channelId, int priority,
                                                            long startMs, long endMs) {
        return jdbc.query("SELECT override_key, channel_id, asset_id, grant_id, priority, start_ms,"
                        + " end_ms, business_day, status, cancel_request_id, cancelled_at_ms, created_at_ms"
                        + " FROM playout_emergency_override"
                        + " WHERE channel_id = ? AND status = 'ACTIVE' AND priority = ?"
                        + " AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY start_ms, override_key FOR UPDATE",
                OVERRIDE_MAPPER, channelId, priority, endMs, startMs);
    }

    /** 命中某时刻的 ACTIVE 插播，按优先级从高到低排序（同级区间不重叠，排序结果确定）。 */
    public List<OverrideRow> findActiveOverridesAt(String channelId, long atMs) {
        return jdbc.query("SELECT override_key, channel_id, asset_id, grant_id, priority, start_ms,"
                        + " end_ms, business_day, status, cancel_request_id, cancelled_at_ms, created_at_ms"
                        + " FROM playout_emergency_override"
                        + " WHERE channel_id = ? AND status = 'ACTIVE'"
                        + " AND start_ms <= ? AND end_ms > ?"
                        + " ORDER BY priority DESC, start_ms ASC, override_key ASC",
                OVERRIDE_MAPPER, channelId, atMs, atMs);
    }

    /** 取消插播；返回受影响行数，0 表示不存在或已取消。 */
    public int cancelOverride(String overrideKey, String cancelRequestId, long cancelledAtMs) {
        return jdbc.update("UPDATE playout_emergency_override"
                        + " SET status = 'CANCELLED', cancel_request_id = ?, cancelled_at_ms = ?"
                        + " WHERE override_key = ? AND status = 'ACTIVE'",
                cancelRequestId, cancelledAtMs, overrideKey);
    }

    // ---------- 播出端版本租约 ----------

    /** 租约行；status 为 ACTIVE / COMPLETED / EXPIRED，过期为惰性标记。 */
    public record LeaseRow(long id, String clientKey, String channelId, LocalDate businessDay,
                           long publicationId, long publishedVersion, long leaseEpoch,
                           String status, long ttlMs, long expiresAtMs,
                           long createdAtMs, long updatedAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 租约分段快照行；ackKey/playedAtMs 未确认为 NULL。 */
    public record LeaseSegmentRow(long id, long leaseId, int seq, String segmentId, String assetId,
                                  long grantId, boolean grantRevoked, long startMs, long endMs,
                                  boolean acked, String ackKey, Long playedAtMs) {
    }

    /** 租约插播快照行。 */
    public record LeaseOverrideRow(long id, long leaseId, String overrideKey, String assetId,
                                   long grantId, boolean grantRevoked, int priority,
                                   long startMs, long endMs) {
    }

    /** 分段确认记录行；ackedCount/leaseStatus 为本次确认完成后的快照，用于重放首次结果。 */
    public record LeaseAckRow(long id, long leaseId, String ackKey, String segmentId,
                              long leaseEpoch, long playedAtMs, int ackedCount,
                              String leaseStatus, long createdAtMs) {
    }

    private static final RowMapper<LeaseRow> LEASE_MAPPER = (rs, n) ->
            new LeaseRow(rs.getLong("id"), rs.getString("client_key"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(), rs.getLong("publication_id"),
                    rs.getLong("published_version"), rs.getLong("lease_epoch"),
                    rs.getString("status"), rs.getLong("ttl_ms"), rs.getLong("expires_at_ms"),
                    rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));

    private static final RowMapper<LeaseSegmentRow> LEASE_SEGMENT_MAPPER = (rs, n) ->
            new LeaseSegmentRow(rs.getLong("id"), rs.getLong("lease_id"), rs.getInt("seq"),
                    rs.getString("segment_id"), rs.getString("asset_id"), rs.getLong("grant_id"),
                    rs.getBoolean("grant_revoked"), rs.getLong("start_ms"), rs.getLong("end_ms"),
                    rs.getBoolean("acked"), rs.getString("ack_key"),
                    (Long) rs.getObject("played_at_ms"));

    private static final RowMapper<LeaseOverrideRow> LEASE_OVERRIDE_MAPPER = (rs, n) ->
            new LeaseOverrideRow(rs.getLong("id"), rs.getLong("lease_id"),
                    rs.getString("override_key"), rs.getString("asset_id"), rs.getLong("grant_id"),
                    rs.getBoolean("grant_revoked"), rs.getInt("priority"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"));

    private static final RowMapper<LeaseAckRow> LEASE_ACK_MAPPER = (rs, n) ->
            new LeaseAckRow(rs.getLong("id"), rs.getLong("lease_id"), rs.getString("ack_key"),
                    rs.getString("segment_id"), rs.getLong("lease_epoch"),
                    rs.getLong("played_at_ms"), rs.getInt("acked_count"),
                    rs.getString("lease_status"), rs.getLong("created_at_ms"));

    private static final String LEASE_COLUMNS = "id, client_key, channel_id, business_day,"
            + " publication_id, published_version, lease_epoch, status, ttl_ms, expires_at_ms,"
            + " created_at_ms, updated_at_ms";

    private static final String LEASE_SEGMENT_COLUMNS = "id, lease_id, seq, segment_id, asset_id,"
            + " grant_id, grant_revoked, start_ms, end_ms, acked, ack_key, played_at_ms";

    private static final String LEASE_OVERRIDE_COLUMNS = "id, lease_id, override_key, asset_id,"
            + " grant_id, grant_revoked, priority, start_ms, end_ms";

    private static final String LEASE_ACK_COLUMNS = "id, lease_id, ack_key, segment_id,"
            + " lease_epoch, played_at_ms, acked_count, lease_status, created_at_ms";

    /** 创建租约，active_unique 固定为 1；唯一索引冲突时抛 DuplicateKeyException。 */
    public long insertLease(String clientKey, String channelId, LocalDate businessDay,
                            long publicationId, long publishedVersion, long leaseEpoch,
                            long ttlMs, long expiresAtMs, long nowMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_lease"
                            + " (client_key, channel_id, business_day, publication_id,"
                            + "  published_version, lease_epoch, status, active_unique, ttl_ms,"
                            + "  expires_at_ms, created_at_ms, updated_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, clientKey);
            ps.setString(2, channelId);
            ps.setDate(3, Date.valueOf(businessDay));
            ps.setLong(4, publicationId);
            ps.setLong(5, publishedVersion);
            ps.setLong(6, leaseEpoch);
            ps.setLong(7, ttlMs);
            ps.setLong(8, expiresAtMs);
            ps.setLong(9, nowMs);
            ps.setLong(10, nowMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按 ID 查询租约。 */
    public Optional<LeaseRow> findLease(long leaseId) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_lease WHERE id = ?",
                LEASE_MAPPER, leaseId).stream().findFirst();
    }

    /** 按 ID 查询租约并加行锁，用于续租与确认串行化。 */
    public Optional<LeaseRow> findLeaseForUpdate(long leaseId) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_lease WHERE id = ? FOR UPDATE",
                LEASE_MAPPER, leaseId).stream().findFirst();
    }

    /** 查询客户端+频道+业务日的 ACTIVE 租约并加行锁；过期租约在拉取流程中惰性转为 EXPIRED。 */
    public Optional<LeaseRow> findActiveLeaseForUpdate(String clientKey, String channelId,
                                                       LocalDate businessDay) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_lease"
                        + " WHERE client_key = ? AND channel_id = ? AND business_day = ?"
                        + " AND active_unique = 1 FOR UPDATE",
                LEASE_MAPPER, clientKey, channelId, Date.valueOf(businessDay)).stream().findFirst();
    }

    /** 客户端+频道+业务日历史最大租约纪元，无历史时为 0。 */
    public long maxLeaseEpoch(String clientKey, String channelId, LocalDate businessDay) {
        Long epoch = jdbc.queryForObject(
                "SELECT MAX(lease_epoch) FROM playout_lease"
                        + " WHERE client_key = ? AND channel_id = ? AND business_day = ?",
                Long.class, clientKey, channelId, Date.valueOf(businessDay));
        return epoch == null ? 0L : epoch;
    }

    /** 惰性将 ACTIVE 租约标记为 EXPIRED 并释放 ACTIVE 唯一槽位；返回受影响行数。 */
    public int markLeaseExpired(long leaseId, long updatedAtMs) {
        return jdbc.update("UPDATE playout_lease"
                        + " SET status = 'EXPIRED', active_unique = NULL, updated_at_ms = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                updatedAtMs, leaseId);
    }

    /** 续租：推进纪元并延长到期时刻，保持发布版本不变；仅 ACTIVE 生效。 */
    public int renewLease(long leaseId, long newEpoch, long expiresAtMs, long updatedAtMs) {
        return jdbc.update("UPDATE playout_lease"
                        + " SET lease_epoch = ?, expires_at_ms = ?, updated_at_ms = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                newEpoch, expiresAtMs, updatedAtMs, leaseId);
    }

    /** 全部确认后完成租约并释放 ACTIVE 唯一槽位。 */
    public int completeLease(long leaseId, long updatedAtMs) {
        return jdbc.update("UPDATE playout_lease"
                        + " SET status = 'COMPLETED', active_unique = NULL, updated_at_ms = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                updatedAtMs, leaseId);
    }

    public void insertLeaseSegment(long leaseId, int seq, String segmentId, String assetId,
                                   long grantId, boolean grantRevoked, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_lease_segment"
                        + " (lease_id, seq, segment_id, asset_id, grant_id, grant_revoked,"
                        + "  start_ms, end_ms, acked)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                leaseId, seq, segmentId, assetId, grantId, grantRevoked, startMs, endMs);
    }

    /** 租约分段快照，按确认顺序（seq）返回。 */
    public List<LeaseSegmentRow> findLeaseSegments(long leaseId) {
        return jdbc.query("SELECT " + LEASE_SEGMENT_COLUMNS + " FROM playout_lease_segment"
                        + " WHERE lease_id = ? ORDER BY seq",
                LEASE_SEGMENT_MAPPER, leaseId);
    }

    /** 下一个未确认分段（seq 最小）。 */
    public Optional<LeaseSegmentRow> findNextUnackedSegment(long leaseId) {
        return jdbc.query("SELECT " + LEASE_SEGMENT_COLUMNS + " FROM playout_lease_segment"
                        + " WHERE lease_id = ? AND acked = 0 ORDER BY seq LIMIT 1",
                LEASE_SEGMENT_MAPPER, leaseId).stream().findFirst();
    }

    /** 租约内指定分段。 */
    public Optional<LeaseSegmentRow> findLeaseSegment(long leaseId, String segmentId) {
        return jdbc.query("SELECT " + LEASE_SEGMENT_COLUMNS + " FROM playout_lease_segment"
                        + " WHERE lease_id = ? AND segment_id = ?",
                LEASE_SEGMENT_MAPPER, leaseId, segmentId).stream().findFirst();
    }

    /** 标记分段已确认；返回受影响行数，0 表示已确认或不存在。 */
    public int markSegmentAcked(long leaseId, String segmentId, String ackKey, long playedAtMs) {
        return jdbc.update("UPDATE playout_lease_segment"
                        + " SET acked = 1, ack_key = ?, played_at_ms = ?"
                        + " WHERE lease_id = ? AND segment_id = ? AND acked = 0",
                ackKey, playedAtMs, leaseId, segmentId);
    }

    /** 未确认分段数。 */
    public int countUnackedSegments(long leaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_lease_segment WHERE lease_id = ? AND acked = 0",
                Integer.class, leaseId);
        return count == null ? 0 : count;
    }

    public void insertLeaseOverride(long leaseId, String overrideKey, String assetId, long grantId,
                                    boolean grantRevoked, int priority, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_lease_override"
                        + " (lease_id, override_key, asset_id, grant_id, grant_revoked, priority,"
                        + "  start_ms, end_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                leaseId, overrideKey, assetId, grantId, grantRevoked, priority, startMs, endMs);
    }

    /** 租约插播快照，按优先级降序、开始时间升序返回。 */
    public List<LeaseOverrideRow> findLeaseOverrides(long leaseId) {
        return jdbc.query("SELECT " + LEASE_OVERRIDE_COLUMNS + " FROM playout_lease_override"
                        + " WHERE lease_id = ? ORDER BY priority DESC, start_ms, override_key",
                LEASE_OVERRIDE_MAPPER, leaseId);
    }

    /** 记录分段确认；唯一索引 (lease_id, ack_key) 冲突时抛 DuplicateKeyException。 */
    public void insertLeaseAck(long leaseId, String ackKey, String segmentId, long leaseEpoch,
                               long playedAtMs, int ackedCount, String leaseStatus, long createdAtMs) {
        jdbc.update("INSERT INTO playout_lease_ack"
                        + " (lease_id, ack_key, segment_id, lease_epoch, played_at_ms,"
                        + "  acked_count, lease_status, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                leaseId, ackKey, segmentId, leaseEpoch, playedAtMs, ackedCount, leaseStatus,
                createdAtMs);
    }

    /** 按幂等键查询确认记录。 */
    public Optional<LeaseAckRow> findLeaseAck(long leaseId, String ackKey) {
        return jdbc.query("SELECT " + LEASE_ACK_COLUMNS + " FROM playout_lease_ack"
                        + " WHERE lease_id = ? AND ack_key = ?",
                LEASE_ACK_MAPPER, leaseId, ackKey).stream().findFirst();
    }

    /** 租约全部确认记录，按受理顺序返回。 */
    public List<LeaseAckRow> findLeaseAcks(long leaseId) {
        return jdbc.query("SELECT " + LEASE_ACK_COLUMNS + " FROM playout_lease_ack"
                        + " WHERE lease_id = ? ORDER BY id",
                LEASE_ACK_MAPPER, leaseId);
    }

    /** 引用指定发布快照的未过期 ACTIVE 租约数（nowMs 为判定时刻，UTC 纪元毫秒）。 */
    public long countUnexpiredActiveLeasesForPublication(long publicationId, long nowMs) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_lease"
                        + " WHERE publication_id = ? AND status = 'ACTIVE' AND expires_at_ms > ?",
                Long.class, publicationId, nowMs);
        return count == null ? 0L : count;
    }

    /** 按 ID 查询发布快照。 */
    public Optional<PublicationRow> findPublication(long publicationId) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version"
                        + " FROM playout_publication WHERE id = ?",
                PUBLICATION_MAPPER, publicationId).stream().findFirst();
    }

    /** 某业务日状态为 ACTIVE 的紧急插播，按优先级降序、开始时间升序返回（拉取快照用）。 */
    public List<OverrideRow> findActiveOverridesForDay(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT override_key, channel_id, asset_id, grant_id, priority, start_ms,"
                        + " end_ms, business_day, status, cancel_request_id, cancelled_at_ms, created_at_ms"
                        + " FROM playout_emergency_override"
                        + " WHERE channel_id = ? AND business_day = ? AND status = 'ACTIVE'"
                        + " ORDER BY priority DESC, start_ms, override_key",
                OVERRIDE_MAPPER, channelId, Date.valueOf(businessDay));
    }
}
