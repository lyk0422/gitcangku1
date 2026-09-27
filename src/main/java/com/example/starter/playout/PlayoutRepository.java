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

    /** 直播覆盖令行；status 为 ACTIVE / REVOKED / SETTLED，创建后除状态推进外不可改写。 */
    public record LiveOverrideRow(String overrideKey, String liveAssetId, String operator,
                                  long startMs, long endMs, String status,
                                  String revokeRequestId, Long revokedAtMs,
                                  String settleRequestId, Long settledAtMs, long createdAtMs) {
    }

    /** 直播覆盖令频道维度行，含创建时的授权版本快照（授权区间），不可变。 */
    public record LiveOverrideChannelRow(String overrideKey, String channelId, long grantId,
                                         long grantValidFromMs, long grantValidToMs) {
    }

    /** 受影响素材记录行；disposition 为 PENDING / RESTORED / MISSED_BY_LIVE，终态不可改写。 */
    public record LiveOverrideSegmentRow(long id, String overrideKey, String channelId,
                                         long publicationId, String segmentId, String assetId,
                                         long startMs, long endMs, String disposition) {
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

    private static final RowMapper<LiveOverrideRow> LIVE_OVERRIDE_MAPPER = (rs, n) ->
            new LiveOverrideRow(rs.getString("override_key"), rs.getString("live_asset_id"),
                    rs.getString("operator"), rs.getLong("start_ms"), rs.getLong("end_ms"),
                    rs.getString("status"), rs.getString("revoke_request_id"),
                    (Long) rs.getObject("revoked_at_ms"), rs.getString("settle_request_id"),
                    (Long) rs.getObject("settled_at_ms"), rs.getLong("created_at_ms"));

    private static final RowMapper<LiveOverrideChannelRow> LIVE_CHANNEL_MAPPER = (rs, n) ->
            new LiveOverrideChannelRow(rs.getString("override_key"), rs.getString("channel_id"),
                    rs.getLong("grant_id"), rs.getLong("grant_valid_from_ms"),
                    rs.getLong("grant_valid_to_ms"));

    private static final RowMapper<LiveOverrideSegmentRow> LIVE_SEGMENT_MAPPER = (rs, n) ->
            new LiveOverrideSegmentRow(rs.getLong("id"), rs.getString("override_key"),
                    rs.getString("channel_id"), rs.getLong("publication_id"),
                    rs.getString("segment_id"), rs.getString("asset_id"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("disposition"));

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

    // ---------- 直播覆盖 ----------

    private static final String LIVE_OVERRIDE_COLUMNS = "override_key, live_asset_id, operator,"
            + " start_ms, end_ms, status, revoke_request_id, revoked_at_ms,"
            + " settle_request_id, settled_at_ms, created_at_ms";

    private static final String LIVE_CHANNEL_COLUMNS = "override_key, channel_id, grant_id,"
            + " grant_valid_from_ms, grant_valid_to_ms";

    private static final String LIVE_SEGMENT_COLUMNS = "id, override_key, channel_id,"
            + " publication_id, segment_id, asset_id, start_ms, end_ms, disposition";

    public void insertLiveOverride(String overrideKey, String liveAssetId, String operator,
                                   long startMs, long endMs, long createdAtMs) {
        jdbc.update("INSERT INTO playout_live_override"
                        + " (override_key, live_asset_id, operator, start_ms, end_ms, status, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                overrideKey, liveAssetId, operator, startMs, endMs, createdAtMs);
    }

    public void insertLiveOverrideChannel(String overrideKey, String channelId, long grantId,
                                          long grantValidFromMs, long grantValidToMs) {
        jdbc.update("INSERT INTO playout_live_override_channel"
                        + " (override_key, channel_id, grant_id, grant_valid_from_ms, grant_valid_to_ms)"
                        + " VALUES (?, ?, ?, ?, ?)",
                overrideKey, channelId, grantId, grantValidFromMs, grantValidToMs);
    }

    public void insertLiveOverrideSegment(String overrideKey, String channelId, long publicationId,
                                          String segmentId, String assetId, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_live_override_segment"
                        + " (override_key, channel_id, publication_id, segment_id, asset_id,"
                        + "  start_ms, end_ms, disposition)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                overrideKey, channelId, publicationId, segmentId, assetId, startMs, endMs);
    }

    public Optional<LiveOverrideRow> findLiveOverride(String overrideKey) {
        return jdbc.query("SELECT " + LIVE_OVERRIDE_COLUMNS
                        + " FROM playout_live_override WHERE override_key = ?",
                LIVE_OVERRIDE_MAPPER, overrideKey).stream().findFirst();
    }

    /** 按全局键查询并加行锁，用于撤销/结算与创建按提交顺序串行。 */
    public Optional<LiveOverrideRow> findLiveOverrideForUpdate(String overrideKey) {
        return jdbc.query("SELECT " + LIVE_OVERRIDE_COLUMNS
                        + " FROM playout_live_override WHERE override_key = ? FOR UPDATE",
                LIVE_OVERRIDE_MAPPER, overrideKey).stream().findFirst();
    }

    /** 覆盖令的频道维度列表，按 channel_id 稳定排序。 */
    public List<LiveOverrideChannelRow> findLiveOverrideChannels(String overrideKey) {
        return jdbc.query("SELECT " + LIVE_CHANNEL_COLUMNS
                        + " FROM playout_live_override_channel WHERE override_key = ?"
                        + " ORDER BY channel_id",
                LIVE_CHANNEL_MAPPER, overrideKey);
    }

    /** 覆盖令在指定频道的频道维度记录。 */
    public Optional<LiveOverrideChannelRow> findLiveOverrideChannel(String overrideKey,
                                                                    String channelId) {
        return jdbc.query("SELECT " + LIVE_CHANNEL_COLUMNS
                        + " FROM playout_live_override_channel"
                        + " WHERE override_key = ? AND channel_id = ?",
                LIVE_CHANNEL_MAPPER, overrideKey, channelId).stream().findFirst();
    }

    /** 覆盖令的受影响素材记录，按频道、开始时间、自增 ID 稳定排序。 */
    public List<LiveOverrideSegmentRow> findLiveOverrideSegments(String overrideKey) {
        return jdbc.query("SELECT " + LIVE_SEGMENT_COLUMNS
                        + " FROM playout_live_override_segment WHERE override_key = ?"
                        + " ORDER BY channel_id, start_ms, id",
                LIVE_SEGMENT_MAPPER, overrideKey);
    }

    /**
     * 同频道、状态 ACTIVE 且与 [startMs, endMs) 相交的直播覆盖令（区间左闭右开：相邻不算相交）。
     * 须在持频道锁后调用。
     */
    public List<LiveOverrideRow> findActiveLiveOverlappingForUpdate(String channelId,
                                                                    long startMs, long endMs) {
        return jdbc.query("SELECT o." + LIVE_OVERRIDE_COLUMNS.replace(", ", ", o.")
                        + " FROM playout_live_override o"
                        + " JOIN playout_live_override_channel c ON c.override_key = o.override_key"
                        + " WHERE c.channel_id = ? AND o.status = 'ACTIVE'"
                        + " AND o.start_ms < ? AND o.end_ms > ?"
                        + " ORDER BY o.start_ms, o.override_key FOR UPDATE",
                LIVE_OVERRIDE_MAPPER, channelId, endMs, startMs);
    }

    /** 命中某时刻的 ACTIVE 直播覆盖令（同频道同时段至多一条，排序稳定）。 */
    public Optional<LiveOverrideRow> findActiveLiveOverrideAt(String channelId, long atMs) {
        return jdbc.query("SELECT o." + LIVE_OVERRIDE_COLUMNS.replace(", ", ", o.")
                        + " FROM playout_live_override o"
                        + " JOIN playout_live_override_channel c ON c.override_key = o.override_key"
                        + " WHERE c.channel_id = ? AND o.status = 'ACTIVE'"
                        + " AND o.start_ms <= ? AND o.end_ms > ?"
                        + " ORDER BY o.start_ms, o.override_key LIMIT 1",
                LIVE_OVERRIDE_MAPPER, channelId, atMs, atMs).stream().findFirst();
    }

    /** 频道全部直播覆盖令，按开始时间、覆盖键稳定排序。 */
    public List<LiveOverrideRow> findLiveOverridesByChannel(String channelId) {
        return jdbc.query("SELECT o." + LIVE_OVERRIDE_COLUMNS.replace(", ", ", o.")
                        + " FROM playout_live_override o"
                        + " JOIN playout_live_override_channel c ON c.override_key = o.override_key"
                        + " WHERE c.channel_id = ?"
                        + " ORDER BY o.start_ms, o.override_key",
                LIVE_OVERRIDE_MAPPER, channelId);
    }

    /** 撤销覆盖令；仅 ACTIVE 可推进，返回受影响行数。 */
    public int revokeLiveOverride(String overrideKey, String revokeRequestId, long revokedAtMs) {
        return jdbc.update("UPDATE playout_live_override"
                        + " SET status = 'REVOKED', revoke_request_id = ?, revoked_at_ms = ?"
                        + " WHERE override_key = ? AND status = 'ACTIVE'",
                revokeRequestId, revokedAtMs, overrideKey);
    }

    /** 结算覆盖令；仅 ACTIVE 可推进，返回受影响行数。 */
    public int settleLiveOverride(String overrideKey, String settleRequestId, long settledAtMs) {
        return jdbc.update("UPDATE playout_live_override"
                        + " SET status = 'SETTLED', settle_request_id = ?, settled_at_ms = ?"
                        + " WHERE override_key = ? AND status = 'ACTIVE'",
                settleRequestId, settledAtMs, overrideKey);
    }

    /** 推进受影响素材恢复结果；仅改写 PENDING 行，已错过记录不可被撤销改写。 */
    public int updateLiveSegmentDisposition(String overrideKey, String toDisposition,
                                            String requestId, long atMs) {
        return jdbc.update("UPDATE playout_live_override_segment"
                        + " SET disposition = ?, disposition_request_id = ?, disposition_at_ms = ?"
                        + " WHERE override_key = ? AND disposition = 'PENDING'",
                toDisposition, requestId, atMs, overrideKey);
    }

    /** 快照中与 [startMs, endMs) 相交的片段（区间左闭右开），按开始时间、片段 ID 稳定排序。 */
    public List<PublicationSegmentRow> findPublicationSegmentsOverlapping(long publicationId,
                                                                          long startMs, long endMs) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, start_ms, end_ms"
                        + " FROM playout_publication_segment"
                        + " WHERE publication_id = ? AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY start_ms, segment_id",
                PUBLICATION_SEGMENT_MAPPER, publicationId, endMs, startMs);
    }

    /**
     * 判断指定快照片段是否被未撤销的直播覆盖令错过：覆盖已开始（start_ms <= atMs）、
     * 覆盖令未撤销且素材记录未恢复。用于播出决定返回 MISSED_BY_LIVE 且不补播。
     */
    public boolean existsMissedLiveSegment(long publicationId, String segmentId, long atMs) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_live_override_segment s"
                        + " JOIN playout_live_override o ON o.override_key = s.override_key"
                        + " WHERE s.publication_id = ? AND s.segment_id = ?"
                        + " AND o.status <> 'REVOKED' AND s.disposition <> 'RESTORED'"
                        + " AND o.start_ms <= ?",
                Integer.class, publicationId, segmentId, atMs);
        return count != null && count > 0;
    }
}
