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

    /** 按自增 ID 查询发布快照。 */
    public Optional<PublicationRow> findPublicationById(long publicationId) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version"
                        + " FROM playout_publication WHERE id = ?",
                PUBLICATION_MAPPER, publicationId).stream().findFirst();
    }

    /** 快照中覆盖指定时刻的片段（start <= at < end）。 */
    public Optional<PublicationSegmentRow> findPublicationSegmentAt(long publicationId, long atMs) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, start_ms, end_ms"
                        + " FROM playout_publication_segment"
                        + " WHERE publication_id = ? AND start_ms <= ? AND end_ms > ?"
                        + " ORDER BY start_ms LIMIT 1",
                PUBLICATION_SEGMENT_MAPPER, publicationId, atMs, atMs).stream().findFirst();
    }

    /** 同上，但加行锁，用于播放回执确认时与（理论上的）快照写入串行。 */
    public Optional<PublicationSegmentRow> findPublicationSegmentAtForUpdate(long publicationId,
                                                                             long atMs) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, start_ms, end_ms"
                        + " FROM playout_publication_segment"
                        + " WHERE publication_id = ? AND start_ms <= ? AND end_ms > ?"
                        + " ORDER BY start_ms LIMIT 1 FOR UPDATE",
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

    // ---------- 字幕文本版本 ----------

    /** 字幕文本版本行；reviewStatus 为 PENDING / APPROVED / REJECTED。 */
    public record CaptionTextRow(String versionId, String content, String reviewStatus,
                                 Long reviewedAtMs, long createdAtMs) {
    }

    private static final RowMapper<CaptionTextRow> CAPTION_TEXT_MAPPER = (rs, n) ->
            new CaptionTextRow(rs.getString("version_id"), rs.getString("content"),
                    rs.getString("review_status"), (Long) rs.getObject("reviewed_at_ms"),
                    rs.getLong("created_at_ms"));

    public void insertCaptionText(String versionId, String content, long createdAtMs) {
        jdbc.update("INSERT INTO playout_caption_text_version"
                        + " (version_id, content, review_status, created_at_ms)"
                        + " VALUES (?, ?, 'PENDING', ?)",
                versionId, content, createdAtMs);
    }

    public Optional<CaptionTextRow> findCaptionText(String versionId) {
        return jdbc.query("SELECT version_id, content, review_status, reviewed_at_ms, created_at_ms"
                        + " FROM playout_caption_text_version WHERE version_id = ?",
                CAPTION_TEXT_MAPPER, versionId).stream().findFirst();
    }

    public Optional<CaptionTextRow> findCaptionTextForUpdate(String versionId) {
        return jdbc.query("SELECT version_id, content, review_status, reviewed_at_ms, created_at_ms"
                        + " FROM playout_caption_text_version WHERE version_id = ? FOR UPDATE",
                CAPTION_TEXT_MAPPER, versionId).stream().findFirst();
    }

    /** 审核文本版本（PENDING 唯一一次流转）；返回受影响行数，0 表示不存在或已审核。 */
    public int reviewCaptionText(String versionId, String decision, long reviewedAtMs) {
        return jdbc.update("UPDATE playout_caption_text_version"
                        + " SET review_status = ?, reviewed_at_ms = ?"
                        + " WHERE version_id = ? AND review_status = 'PENDING'",
                decision, reviewedAtMs, versionId);
    }

    // ---------- 紧急字幕 ----------

    /** 紧急字幕行；status 为 ACTIVE / REVOKED，regions 为规范化区域集合。 */
    public record CaptionRow(long id, String captionKey, String channelId, int priority,
                            String textVersionId, long startMs, long endMs, List<String> regions,
                            String status, String revokeCrawlKey, Long revokedAtMs,
                            long createdAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private static final RowMapper<CaptionRow> CAPTION_MAPPER = (rs, n) ->
            new CaptionRow(rs.getLong("id"), rs.getString("caption_key"), rs.getString("channel_id"),
                    rs.getInt("priority"), rs.getString("text_version_id"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"),
                    splitRegions(rs.getString("regions_csv")),
                    rs.getString("status"), rs.getString("revoke_request_id"),
                    (Long) rs.getObject("revoked_at_ms"), rs.getLong("created_at_ms"));

    static List<String> splitRegions(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return List.of(csv.split(","));
    }

    public long insertCaption(String captionKey, String channelId, int priority, String textVersionId,
                              long startMs, long endMs, List<String> regions, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_emergency_caption"
                            + " (caption_key, channel_id, priority, text_version_id, start_ms, end_ms,"
                            + "  regions_csv, status, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, captionKey);
            ps.setString(2, channelId);
            ps.setInt(3, priority);
            ps.setString(4, textVersionId);
            ps.setLong(5, startMs);
            ps.setLong(6, endMs);
            ps.setString(7, String.join(",", regions));
            ps.setLong(8, createdAtMs);
            return ps;
        }, keyHolder);
        long captionId = keyHolder.getKey().longValue();
        for (String region : regions) {
            jdbc.update("INSERT INTO playout_emergency_caption_region (caption_id, region) VALUES (?, ?)",
                    captionId, region);
        }
        return captionId;
    }

    public Optional<CaptionRow> findCaption(String captionKey) {
        return jdbc.query(captionSelect() + " WHERE c.caption_key = ?",
                CAPTION_MAPPER, captionKey).stream().findFirst();
    }

    public Optional<CaptionRow> findCaptionForUpdate(String captionKey) {
        return jdbc.query(captionSelect() + " WHERE c.caption_key = ? FOR UPDATE",
                CAPTION_MAPPER, captionKey).stream().findFirst();
    }

    private static String captionSelect() {
        return "SELECT c.id, c.caption_key, c.channel_id, c.priority, c.text_version_id,"
                + " c.start_ms, c.end_ms, c.regions_csv, c.status, c.revoke_request_id,"
                + " c.revoked_at_ms, c.created_at_ms"
                + " FROM playout_emergency_caption c";
    }

    /**
     * 同频道、指定区域、同优先级、状态 ACTIVE 且与 [startMs, endMs) 相交的字幕
     * （左闭右开：start == end 端点相接不算相交）。须在持频道锁后调用。
     */
    public List<CaptionRow> findActiveCaptionsOverlappingForUpdate(String channelId, String region,
                                                                   int priority,
                                                                   long startMs, long endMs) {
        return jdbc.query(captionSelect()
                        + " JOIN playout_emergency_caption_region r ON r.caption_id = c.id"
                        + " WHERE c.channel_id = ? AND r.region = ? AND c.status = 'ACTIVE'"
                        + " AND c.priority = ? AND c.start_ms < ? AND c.end_ms > ?"
                        + " ORDER BY c.start_ms, c.caption_key FOR UPDATE",
                CAPTION_MAPPER, channelId, region, priority, endMs, startMs);
    }

    /**
     * 命中指定频道与区域、在某时刻仍有效（start <= at < end）的 ACTIVE 字幕候选，
     * 按优先级降序排列；供区域字幕实时决策查询使用。
     */
    public List<CaptionRow> findActiveCaptionsAt(String channelId, String region, long atMs) {
        return jdbc.query(captionSelect()
                        + " JOIN playout_emergency_caption_region r ON r.caption_id = c.id"
                        + " WHERE c.channel_id = ? AND r.region = ? AND c.status = 'ACTIVE'"
                        + " AND c.start_ms <= ? AND c.end_ms > ?"
                        + " ORDER BY c.priority DESC, c.start_ms ASC, c.caption_key ASC",
                CAPTION_MAPPER, channelId, region, atMs, atMs);
    }

    /** 撤销字幕；返回受影响行数，0 表示不存在或已撤销。 */
    public int revokeCaption(String captionKey, String crawlKey, long revokedAtMs) {
        return jdbc.update("UPDATE playout_emergency_caption"
                        + " SET status = 'REVOKED', revoke_request_id = ?, revoked_at_ms = ?"
                        + " WHERE caption_key = ? AND status = 'ACTIVE'",
                crawlKey, revokedAtMs, captionKey);
    }

    /**
     * 锁定指定频道与 UTC 日窗口相交的全部字幕行（FOR UPDATE），发布规划时与
     * 撤销/新建字幕事务按提交顺序串行。相交为左闭右开语义：start_ms < dayEnd 且 end_ms > dayStart。
     */
    public List<CaptionRow> findChannelCaptionsIntersectingForUpdate(String channelId,
                                                                     long dayStartMs, long dayEndMs) {
        return jdbc.query(captionSelect()
                        + " WHERE c.channel_id = ? AND c.start_ms < ? AND c.end_ms > ?"
                        + " ORDER BY c.id FOR UPDATE",
                CAPTION_MAPPER, channelId, dayEndMs, dayStartMs);
    }

    // ---------- 黑屏窗口 ----------

    /** 黑屏窗口行。 */
    public record BlackoutRow(long id, String blackoutKey, String channelId, String region,
                              long startMs, long endMs, long createdAtMs) {
    }

    private static final RowMapper<BlackoutRow> BLACKOUT_MAPPER = (rs, n) ->
            new BlackoutRow(rs.getLong("id"), rs.getString("blackout_key"),
                    rs.getString("channel_id"), rs.getString("region"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getLong("created_at_ms"));

    public long insertBlackout(String blackoutKey, String channelId, String region,
                               long startMs, long endMs, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_blackout_window"
                            + " (blackout_key, channel_id, region, start_ms, end_ms, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, blackoutKey);
            ps.setString(2, channelId);
            ps.setString(3, region);
            ps.setLong(4, startMs);
            ps.setLong(5, endMs);
            ps.setLong(6, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<BlackoutRow> findBlackout(String blackoutKey) {
        return jdbc.query("SELECT id, blackout_key, channel_id, region, start_ms, end_ms, created_at_ms"
                        + " FROM playout_blackout_window WHERE blackout_key = ?",
                BLACKOUT_MAPPER, blackoutKey).stream().findFirst();
    }

    /** 指定频道+区域与 [startMs, endMs) 相交的黑屏窗口（左闭右开，端点相接不冲突）。 */
    public List<BlackoutRow> findBlackoutsOverlapping(String channelId, String region,
                                                      long startMs, long endMs) {
        return jdbc.query("SELECT id, blackout_key, channel_id, region, start_ms, end_ms, created_at_ms"
                        + " FROM playout_blackout_window"
                        + " WHERE channel_id = ? AND region = ? AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY start_ms, blackout_key",
                BLACKOUT_MAPPER, channelId, region, endMs, startMs);
    }

    // ---------- crawlKey 幂等记录 ----------

    /** crawlKey 去重记录行；responseBody 为 NULL 表示尚未成功完成。 */
    public record CrawlRecordRow(String crawlKey, String operation, String paramsHash,
                                 String responseBody) {
    }

    private static final RowMapper<CrawlRecordRow> CRAWL_MAPPER = (rs, n) ->
            new CrawlRecordRow(rs.getString("crawl_key"), rs.getString("operation"),
                    rs.getString("params_hash"), rs.getString("response_body"));

    public Optional<CrawlRecordRow> findCrawlRecordForUpdate(String crawlKey) {
        return jdbc.query("SELECT crawl_key, operation, params_hash, response_body"
                        + " FROM playout_crawl_record WHERE crawl_key = ? FOR UPDATE",
                CRAWL_MAPPER, crawlKey).stream().findFirst();
    }

    public void insertCrawlRecord(String crawlKey, String operation, String paramsHash,
                                  long createdAtMs) {
        jdbc.update("INSERT INTO playout_crawl_record"
                        + " (crawl_key, operation, params_hash, created_at_ms)"
                        + " VALUES (?, ?, ?, ?)",
                crawlKey, operation, paramsHash, createdAtMs);
    }

    public void completeCrawlRecord(String crawlKey, String responseBody) {
        jdbc.update("UPDATE playout_crawl_record SET response_body = ? WHERE crawl_key = ?",
                responseBody, crawlKey);
    }

    // ---------- 发布快照字幕决策 ----------

    /** 固化的快照字幕决策行。 */
    public record PublicationCaptionRow(long id, long publicationId, String region,
                                        String segmentId, long startMs, long endMs,
                                        long captionId, String captionKey, int priority,
                                        String textVersionId, String textContent, String reason) {
    }

    private static final RowMapper<PublicationCaptionRow> PUBLICATION_CAPTION_MAPPER = (rs, n) ->
            new PublicationCaptionRow(rs.getLong("id"), rs.getLong("publication_id"),
                    rs.getString("region"), rs.getString("segment_id"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"),
                    rs.getLong("caption_id"), rs.getString("caption_key"), rs.getInt("priority"),
                    rs.getString("text_version_id"), rs.getString("text_content"),
                    rs.getString("reason"));

    public void insertPublicationCaption(long publicationId, String region, String segmentId,
                                         long startMs, long endMs, long captionId, String captionKey,
                                         int priority, String textVersionId, String textContent,
                                         String reason) {
        jdbc.update("INSERT INTO playout_publication_caption"
                        + " (publication_id, region, segment_id, start_ms, end_ms, caption_id,"
                        + "  caption_key, priority, text_version_id, text_content, reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicationId, region, segmentId, startMs, endMs, captionId, captionKey, priority,
                textVersionId, textContent, reason);
    }

    public List<PublicationCaptionRow> findPublicationCaptions(long publicationId) {
        return jdbc.query("SELECT id, publication_id, region, segment_id, start_ms, end_ms,"
                        + " caption_id, caption_key, priority, text_version_id, text_content, reason"
                        + " FROM playout_publication_caption WHERE publication_id = ?"
                        + " ORDER BY region, start_ms",
                PUBLICATION_CAPTION_MAPPER, publicationId);
    }

    /** 快照中某区域某时刻命中的字幕决策（start <= at < end）；结束端点恰好不再命中。 */
    public Optional<PublicationCaptionRow> findPublicationCaptionAt(long publicationId,
                                                                    String region, long atMs) {
        return jdbc.query("SELECT id, publication_id, region, segment_id, start_ms, end_ms,"
                        + " caption_id, caption_key, priority, text_version_id, text_content, reason"
                        + " FROM playout_publication_caption"
                        + " WHERE publication_id = ? AND region = ? AND start_ms <= ? AND end_ms > ?"
                        + " ORDER BY start_ms LIMIT 1",
                PUBLICATION_CAPTION_MAPPER, publicationId, region, atMs, atMs).stream().findFirst();
    }

    /** 快照中某区域恰在 at 时刻结束的字幕决策（start <= at 且 end == at），用于回执端点判定。 */
    public boolean existsPublicationCaptionEndingAt(long publicationId, String region, long atMs) {
        Long count = jdbc.queryForObject("SELECT COUNT(1) FROM playout_publication_caption"
                        + " WHERE publication_id = ? AND region = ? AND start_ms <= ? AND end_ms = ?",
                Long.class, publicationId, region, atMs, atMs);
        return count != null && count > 0;
    }

    // ---------- 播放回执 ----------

    /** 播放回执行；captionKey 为 NULL 表示该时刻无字幕覆盖。 */
    public record PlayoutReceiptRow(long id, String crawlKey, String channelId, long publicationId,
                                    long publishedVersion, String region, long atMs, String assetId,
                                    String segmentId, Long captionRecordId, String captionKey,
                                    Integer priority, String textVersionId, String captionText,
                                    Long captionStartMs, Long captionEndMs, String confirmReason,
                                    long createdAtMs) {
    }

    private static final RowMapper<PlayoutReceiptRow> RECEIPT_MAPPER = (rs, n) ->
            new PlayoutReceiptRow(rs.getLong("id"), rs.getString("crawl_key"),
                    rs.getString("channel_id"), rs.getLong("publication_id"),
                    rs.getLong("published_version"), rs.getString("region"), rs.getLong("at_ms"),
                    rs.getString("asset_id"), rs.getString("segment_id"),
                    (Long) rs.getObject("caption_record_id"), rs.getString("caption_key"),
                    (Integer) rs.getObject("priority"), rs.getString("text_version_id"),
                    rs.getString("caption_text"),
                    (Long) rs.getObject("caption_start_ms"), (Long) rs.getObject("caption_end_ms"),
                    rs.getString("confirm_reason"), rs.getLong("created_at_ms"));

    public long insertReceipt(String crawlKey, String channelId, long publicationId,
                              long publishedVersion, String region, long atMs, String assetId,
                              String segmentId, Long captionRecordId, String captionKey,
                              Integer priority, String textVersionId, String captionText,
                              Long captionStartMs, Long captionEndMs, String confirmReason,
                              long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_playout_receipt"
                            + " (crawl_key, channel_id, publication_id, published_version, region,"
                            + "  at_ms, asset_id, segment_id, caption_record_id, caption_key, priority,"
                            + "  text_version_id, caption_text, caption_start_ms, caption_end_ms,"
                            + "  confirm_reason, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, crawlKey);
            ps.setString(2, channelId);
            ps.setLong(3, publicationId);
            ps.setLong(4, publishedVersion);
            ps.setString(5, region);
            ps.setLong(6, atMs);
            ps.setString(7, assetId);
            ps.setString(8, segmentId);
            if (captionRecordId == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, captionRecordId);
            }
            ps.setString(10, captionKey);
            if (priority == null) {
                ps.setNull(11, java.sql.Types.INTEGER);
            } else {
                ps.setInt(11, priority);
            }
            ps.setString(12, textVersionId);
            ps.setString(13, captionText);
            if (captionStartMs == null) {
                ps.setNull(14, java.sql.Types.BIGINT);
            } else {
                ps.setLong(14, captionStartMs);
            }
            if (captionEndMs == null) {
                ps.setNull(15, java.sql.Types.BIGINT);
            } else {
                ps.setLong(15, captionEndMs);
            }
            ps.setString(16, confirmReason);
            ps.setLong(17, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<PlayoutReceiptRow> findReceiptByCrawlKey(String crawlKey) {
        return jdbc.query("SELECT id, crawl_key, channel_id, publication_id, published_version,"
                        + " region, at_ms, asset_id, segment_id, caption_record_id, caption_key,"
                        + " priority, text_version_id, caption_text, caption_start_ms, caption_end_ms,"
                        + " confirm_reason, created_at_ms"
                        + " FROM playout_playout_receipt WHERE crawl_key = ?",
                RECEIPT_MAPPER, crawlKey).stream().findFirst();
    }
}
