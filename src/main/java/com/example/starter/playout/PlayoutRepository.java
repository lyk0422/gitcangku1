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

    /** 黑屏窗口行；regionsText 为规范化区域集合（排序后逗号拼接）。 */
    public record BlackoutRow(long id, String channelId, long startMs, long endMs,
                              String regionsText, String requestId, long createdAtMs) {
    }

    /** 字幕文本版本行；status 为 PENDING / APPROVED。 */
    public record SubtitleTextRow(String textKey, int version, String content, String status,
                                  String approveRequestId, long createdAtMs, Long approvedAtMs) {
        public boolean approved() {
            return "APPROVED".equals(status);
        }
    }

    /** 紧急字幕行；status 为 ACTIVE / REVOKED，regionsText 为规范化区域集合。 */
    public record SubtitleRow(String subtitleKey, String channelId, int priority,
                              String textKey, int textVersion, long startMs, long endMs,
                              String regionsText, String status, String revokeRequestId,
                              Long revokedAtMs, long createdAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 发布固化的字幕子片行。 */
    public record PublicationSubtitleRow(long id, long publicationId, String region,
                                         String segmentId, long startMs, long endMs,
                                         String subtitleKey, String textKey, int textVersion,
                                         String textContent, int priority, String reason) {
    }

    /** 播放回执行；字幕字段为 NULL 表示该时刻未覆盖字幕。 */
    public record ReceiptRow(String crawlKey, String channelId, LocalDate businessDay,
                             long publicationId, long publishedVersion, String region, long atMs,
                             String assetId, String segmentId, String subtitleKey, String textKey,
                             Integer textVersion, Integer priority, Long overlayStartMs,
                             Long overlayEndMs, String fingerprint, long createdAtMs) {
    }

    /** 发布阻断审计行。 */
    public record PublishBlockRow(long id, String channelId, LocalDate businessDay,
                                  String requestId, String code, String detailJson, long createdAtMs) {
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

    /** 按 ID 查询发布快照。 */
    public Optional<PublicationRow> findPublicationById(long publicationId) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version"
                        + " FROM playout_publication WHERE id = ?",
                PUBLICATION_MAPPER, publicationId).stream().findFirst();
    }

    /** 快照全部节目片段，按起点、片段 ID 稳定排序。 */
    public List<PublicationSegmentRow> findPublicationSegments(long publicationId) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, start_ms, end_ms"
                        + " FROM playout_publication_segment"
                        + " WHERE publication_id = ? ORDER BY start_ms, segment_id, id",
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

    // ---------- 黑屏窗口 ----------

    private static final RowMapper<BlackoutRow> BLACKOUT_MAPPER = (rs, n) ->
            new BlackoutRow(rs.getLong("id"), rs.getString("channel_id"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("regions_text"),
                    rs.getString("request_id"), rs.getLong("created_at_ms"));

    public long insertBlackout(String channelId, long startMs, long endMs, String regionsText,
                               String requestId, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_blackout"
                            + " (channel_id, start_ms, end_ms, regions_text, request_id, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, startMs);
            ps.setLong(3, endMs);
            ps.setString(4, regionsText);
            ps.setString(5, requestId);
            ps.setLong(6, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertBlackoutRegion(long blackoutId, String region) {
        jdbc.update("INSERT INTO playout_blackout_region (blackout_id, region) VALUES (?, ?)",
                blackoutId, region);
    }

    /** 查询频道内与 [startMs, endMs) 正重叠（端点相接不算）的黑屏窗口。 */
    public List<BlackoutRow> findBlackoutsOverlapping(String channelId, long startMs, long endMs) {
        return jdbc.query("SELECT id, channel_id, start_ms, end_ms, regions_text, request_id, created_at_ms"
                        + " FROM playout_blackout"
                        + " WHERE channel_id = ? AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY start_ms, id",
                BLACKOUT_MAPPER, channelId, endMs, startMs);
    }

    public List<String> findBlackoutRegions(long blackoutId) {
        return jdbc.queryForList("SELECT region FROM playout_blackout_region"
                + " WHERE blackout_id = ? ORDER BY region", String.class, blackoutId);
    }

    // ---------- 字幕文本与审核 ----------

    private static final RowMapper<SubtitleTextRow> SUBTITLE_TEXT_MAPPER = (rs, n) ->
            new SubtitleTextRow(rs.getString("text_key"), rs.getInt("version"),
                    rs.getString("content"), rs.getString("status"),
                    rs.getString("approve_request_id"), rs.getLong("created_at_ms"),
                    (Long) rs.getObject("approved_at_ms"));

    public void insertSubtitleText(String textKey, int version, String content, long createdAtMs) {
        jdbc.update("INSERT INTO playout_subtitle_text"
                        + " (text_key, version, content, status, created_at_ms)"
                        + " VALUES (?, ?, ?, 'PENDING', ?)",
                textKey, version, content, createdAtMs);
    }

    public Optional<SubtitleTextRow> findSubtitleText(String textKey, int version) {
        return jdbc.query("SELECT text_key, version, content, status, approve_request_id,"
                        + " created_at_ms, approved_at_ms"
                        + " FROM playout_subtitle_text WHERE text_key = ? AND version = ?",
                SUBTITLE_TEXT_MAPPER, textKey, version).stream().findFirst();
    }

    /** 审核通过文本版本；返回受影响行数，0 表示不存在或已审核。 */
    public int approveSubtitleText(String textKey, int version, String requestId, long approvedAtMs) {
        return jdbc.update("UPDATE playout_subtitle_text"
                        + " SET status = 'APPROVED', approve_request_id = ?, approved_at_ms = ?"
                        + " WHERE text_key = ? AND version = ? AND status = 'PENDING'",
                requestId, approvedAtMs, textKey, version);
    }

    /** 按主键查询文本版本并加行锁，供审核与并发创建字幕按提交顺序裁决。 */
    public Optional<SubtitleTextRow> findSubtitleTextForUpdate(String textKey, int version) {
        return jdbc.query("SELECT text_key, version, content, status, approve_request_id,"
                        + " created_at_ms, approved_at_ms"
                        + " FROM playout_subtitle_text WHERE text_key = ? AND version = ? FOR UPDATE",
                SUBTITLE_TEXT_MAPPER, textKey, version).stream().findFirst();
    }

    // ---------- 紧急字幕 ----------

    private static final RowMapper<SubtitleRow> SUBTITLE_MAPPER = (rs, n) ->
            new SubtitleRow(rs.getString("subtitle_key"), rs.getString("channel_id"),
                    rs.getInt("priority"), rs.getString("text_key"), rs.getInt("text_version"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("regions_text"),
                    rs.getString("status"), rs.getString("revoke_request_id"),
                    (Long) rs.getObject("revoked_at_ms"), rs.getLong("created_at_ms"));

    public void insertSubtitle(String subtitleKey, String channelId, int priority,
                               String textKey, int textVersion, long startMs, long endMs,
                               String regionsText, long createdAtMs) {
        jdbc.update("INSERT INTO playout_emergency_subtitle"
                        + " (subtitle_key, channel_id, priority, text_key, text_version,"
                        + " start_ms, end_ms, regions_text, status, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                subtitleKey, channelId, priority, textKey, textVersion,
                startMs, endMs, regionsText, createdAtMs);
    }

    public void insertSubtitleRegion(String subtitleKey, String region) {
        jdbc.update("INSERT INTO playout_emergency_subtitle_region (subtitle_key, region)"
                + " VALUES (?, ?)", subtitleKey, region);
    }

    public Optional<SubtitleRow> findSubtitle(String subtitleKey) {
        return jdbc.query("SELECT subtitle_key, channel_id, priority, text_key, text_version,"
                        + " start_ms, end_ms, regions_text, status, revoke_request_id,"
                        + " revoked_at_ms, created_at_ms"
                        + " FROM playout_emergency_subtitle WHERE subtitle_key = ?",
                SUBTITLE_MAPPER, subtitleKey).stream().findFirst();
    }

    /** 按全局键查询并加行锁，用于撤销与创建的同键并发串行化。 */
    public Optional<SubtitleRow> findSubtitleForUpdate(String subtitleKey) {
        return jdbc.query("SELECT subtitle_key, channel_id, priority, text_key, text_version,"
                        + " start_ms, end_ms, regions_text, status, revoke_request_id,"
                        + " revoked_at_ms, created_at_ms"
                        + " FROM playout_emergency_subtitle WHERE subtitle_key = ? FOR UPDATE",
                SUBTITLE_MAPPER, subtitleKey).stream().findFirst();
    }

    public List<String> findSubtitleRegions(String subtitleKey) {
        return jdbc.queryForList("SELECT region FROM playout_emergency_subtitle_region"
                + " WHERE subtitle_key = ? ORDER BY region", String.class, subtitleKey);
    }

    /**
     * 同频道、同优先级、状态 ACTIVE 且窗口与 [startMs, endMs) 时间重叠的字幕。
     * 区间左闭右开：相邻 start == end 不重叠。区域交集由调用方在内存判定；
     * 须在持频道锁后调用，结果行已加锁（H2/MySQL 均不允许 DISTINCT 与 FOR UPDATE 联用）。
     */
    public List<SubtitleRow> findActiveSubtitlesOverlappingForUpdate(String channelId, int priority,
                                                                     long startMs, long endMs) {
        return jdbc.query("SELECT subtitle_key, channel_id, priority, text_key, text_version,"
                        + " start_ms, end_ms, regions_text, status, revoke_request_id,"
                        + " revoked_at_ms, created_at_ms"
                        + " FROM playout_emergency_subtitle"
                        + " WHERE channel_id = ? AND status = 'ACTIVE' AND priority = ?"
                        + " AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY subtitle_key FOR UPDATE",
                SUBTITLE_MAPPER, channelId, priority, endMs, startMs);
    }

    /**
     * 命中指定频道、区域与时刻的 ACTIVE 字幕（start <= at < end），按优先级降序、
     * 窗口起点升序、字幕键升序排序；同级在同区域窗口不重叠，排序结果确定。
     * 发布固化路径改用 {@link #findActiveSubtitlesForChannelForUpdate} 对全频道候选加锁。
     */
    public List<SubtitleRow> findActiveSubtitlesAt(String channelId, String region, long atMs) {
        return jdbc.query("SELECT s.subtitle_key, s.channel_id, s.priority, s.text_key,"
                        + " s.text_version, s.start_ms, s.end_ms, s.regions_text, s.status,"
                        + " s.revoke_request_id, s.revoked_at_ms, s.created_at_ms"
                        + " FROM playout_emergency_subtitle s"
                        + " JOIN playout_emergency_subtitle_region r ON r.subtitle_key = s.subtitle_key"
                        + " WHERE s.channel_id = ? AND s.status = 'ACTIVE' AND r.region = ?"
                        + " AND s.start_ms <= ? AND s.end_ms > ?"
                        + " ORDER BY s.priority DESC, s.start_ms ASC, s.subtitle_key ASC",
                SUBTITLE_MAPPER, channelId, region, atMs, atMs);
    }

    /** 频道内全部 ACTIVE 字幕并加行锁，供发布固化时与字幕创建/撤销按提交顺序串行化。 */
    public List<SubtitleRow> findActiveSubtitlesForChannelForUpdate(String channelId) {
        return jdbc.query("SELECT subtitle_key, channel_id, priority, text_key, text_version,"
                        + " start_ms, end_ms, regions_text, status, revoke_request_id,"
                        + " revoked_at_ms, created_at_ms"
                        + " FROM playout_emergency_subtitle"
                        + " WHERE channel_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY subtitle_key FOR UPDATE",
                SUBTITLE_MAPPER, channelId);
    }

    /** 撤销字幕；返回受影响行数，0 表示不存在或已撤销。 */
    public int revokeSubtitle(String subtitleKey, String revokeRequestId, long revokedAtMs) {
        return jdbc.update("UPDATE playout_emergency_subtitle"
                        + " SET status = 'REVOKED', revoke_request_id = ?, revoked_at_ms = ?"
                        + " WHERE subtitle_key = ? AND status = 'ACTIVE'",
                revokeRequestId, revokedAtMs, subtitleKey);
    }

    // ---------- 发布固化字幕 ----------

    private static final RowMapper<PublicationSubtitleRow> PUBLICATION_SUBTITLE_MAPPER = (rs, n) ->
            new PublicationSubtitleRow(rs.getLong("id"), rs.getLong("publication_id"),
                    rs.getString("region"), rs.getString("segment_id"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"),
                    rs.getString("subtitle_key"), rs.getString("text_key"),
                    rs.getInt("text_version"), rs.getString("text_content"),
                    rs.getInt("priority"), rs.getString("reason"));

    public void insertPublicationSubtitle(long publicationId, String region, String segmentId,
                                          long startMs, long endMs, String subtitleKey,
                                          String textKey, int textVersion, String textContent,
                                          int priority, String reason) {
        jdbc.update("INSERT INTO playout_publication_subtitle"
                        + " (publication_id, region, segment_id, start_ms, end_ms, subtitle_key,"
                        + " text_key, text_version, text_content, priority, reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicationId, region, segmentId, startMs, endMs, subtitleKey,
                textKey, textVersion, textContent, priority, reason);
    }

    /** 快照内某区域覆盖指定时刻的字幕子片（start <= at < end）；结束端点恰好时不命中。 */
    public Optional<PublicationSubtitleRow> findPublicationSubtitleAt(long publicationId,
                                                                      String region, long atMs) {
        return jdbc.query("SELECT id, publication_id, region, segment_id, start_ms, end_ms,"
                        + " subtitle_key, text_key, text_version, text_content, priority, reason"
                        + " FROM playout_publication_subtitle"
                        + " WHERE publication_id = ? AND region = ? AND start_ms <= ? AND end_ms > ?"
                        + " ORDER BY start_ms LIMIT 1",
                PUBLICATION_SUBTITLE_MAPPER, publicationId, region, atMs, atMs)
                .stream().findFirst();
    }

    /** 快照全部固化字幕子片，按区域、起点、终点稳定排序。 */
    public List<PublicationSubtitleRow> findPublicationSubtitles(long publicationId) {
        return jdbc.query("SELECT id, publication_id, region, segment_id, start_ms, end_ms,"
                        + " subtitle_key, text_key, text_version, text_content, priority, reason"
                        + " FROM playout_publication_subtitle"
                        + " WHERE publication_id = ? ORDER BY region, start_ms, end_ms, id",
                PUBLICATION_SUBTITLE_MAPPER, publicationId);
    }

    // ---------- 播放回执 ----------

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, n) ->
            new ReceiptRow(rs.getString("crawl_key"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(), rs.getLong("publication_id"),
                    rs.getLong("published_version"), rs.getString("region"), rs.getLong("at_ms"),
                    rs.getString("asset_id"), rs.getString("segment_id"),
                    rs.getString("subtitle_key"), rs.getString("text_key"),
                    (Integer) rs.getObject("text_version"), (Integer) rs.getObject("priority"),
                    (Long) rs.getObject("overlay_start_ms"), (Long) rs.getObject("overlay_end_ms"),
                    rs.getString("fingerprint"), rs.getLong("created_at_ms"));

    public void insertReceipt(String crawlKey, String channelId, LocalDate businessDay,
                              long publicationId, long publishedVersion, String region, long atMs,
                              String assetId, String segmentId, String subtitleKey, String textKey,
                              Integer textVersion, Integer priority, Long overlayStartMs,
                              Long overlayEndMs, String fingerprint, long createdAtMs) {
        jdbc.update("INSERT INTO playout_receipt"
                        + " (crawl_key, channel_id, business_day, publication_id, published_version,"
                        + " region, at_ms, asset_id, segment_id, subtitle_key, text_key, text_version,"
                        + " priority, overlay_start_ms, overlay_end_ms, fingerprint, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                crawlKey, channelId, Date.valueOf(businessDay), publicationId, publishedVersion,
                region, atMs, assetId, segmentId, subtitleKey, textKey, textVersion,
                priority, overlayStartMs, overlayEndMs, fingerprint, createdAtMs);
    }

    public Optional<ReceiptRow> findReceipt(String crawlKey) {
        return jdbc.query("SELECT crawl_key, channel_id, business_day, publication_id,"
                        + " published_version, region, at_ms, asset_id, segment_id, subtitle_key,"
                        + " text_key, text_version, priority, overlay_start_ms, overlay_end_ms,"
                        + " fingerprint, created_at_ms"
                        + " FROM playout_receipt WHERE crawl_key = ?",
                RECEIPT_MAPPER, crawlKey).stream().findFirst();
    }

    /** 按回执键查询并加行锁，用于同键重放串行化。 */
    public Optional<ReceiptRow> findReceiptForUpdate(String crawlKey) {
        return jdbc.query("SELECT crawl_key, channel_id, business_day, publication_id,"
                        + " published_version, region, at_ms, asset_id, segment_id, subtitle_key,"
                        + " text_key, text_version, priority, overlay_start_ms, overlay_end_ms,"
                        + " fingerprint, created_at_ms"
                        + " FROM playout_receipt WHERE crawl_key = ? FOR UPDATE",
                RECEIPT_MAPPER, crawlKey).stream().findFirst();
    }

    // ---------- 发布阻断审计 ----------

    private static final RowMapper<PublishBlockRow> PUBLISH_BLOCK_MAPPER = (rs, n) ->
            new PublishBlockRow(rs.getLong("id"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(), rs.getString("request_id"),
                    rs.getString("code"), rs.getString("detail_json"), rs.getLong("created_at_ms"));

    /** 阻断审计在独立事务中写入，发布主事务回滚不影响本表。 */
    public void insertPublishBlock(String channelId, LocalDate businessDay, String requestId,
                                   String code, String detailJson, long createdAtMs) {
        jdbc.update("INSERT INTO playout_publish_block"
                        + " (channel_id, business_day, request_id, code, detail_json, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                channelId, Date.valueOf(businessDay), requestId, code, detailJson, createdAtMs);
    }

    /** 查询某次发布业务键下最新的阻断原因；无记录时为空。 */
    public Optional<PublishBlockRow> findLatestPublishBlock(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT id, channel_id, business_day, request_id, code, detail_json,"
                        + " created_at_ms FROM playout_publish_block"
                        + " WHERE channel_id = ? AND business_day = ?"
                        + " ORDER BY id DESC LIMIT 1",
                PUBLISH_BLOCK_MAPPER, channelId, Date.valueOf(businessDay)).stream().findFirst();
    }
}
