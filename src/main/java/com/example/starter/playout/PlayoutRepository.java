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

    /** 素材行；sponsorKey 为 null 表示无赞助素材。 */
    public record AssetRow(String id, String sponsorKey, long durationMs) {
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

    /** 发布快照行；status 为 ACTIVE / REVOKED，撤销信息未撤销时为 null。 */
    public record PublicationRow(long id, String channelId, LocalDate businessDay,
                                 long publishedVersion, long draftVersion, String status,
                                 String revokeRequestId, Long revokedAtMs, long createdAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 发布快照片段行，grantId 为发布时选定的覆盖授权，sponsorKey 为发布时固化的素材赞助商快照。 */
    public record PublicationSegmentRow(long id, long publicationId, String segmentId,
                                        String assetId, long grantId, String sponsorKey,
                                        long startMs, long endMs) {
    }

    /** 赞助排他窗行；status 为 ACTIVE / SUPERSEDED / DELETED，仅 ACTIVE 参与校验。 */
    public record SponsorWindowRow(long id, String windowKey, long version, String sponsorKey,
                                   LocalDate businessDay, long startMs, long endMs,
                                   String status, long createdAtMs) {
    }

    /** 发布命中排他窗历史行；windowVersion 为发布时固化的窗口版本。 */
    public record WindowHitRow(long id, long publicationId, String windowKey, long windowVersion,
                               long windowId, String sponsorKey, String channelId,
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
            new AssetRow(rs.getString("id"), rs.getString("sponsor_key"), rs.getLong("duration_ms"));

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
                    rs.getLong("published_version"), rs.getLong("draft_version"),
                    rs.getString("status"), rs.getString("revoke_request_id"),
                    (Long) rs.getObject("revoked_at_ms"), rs.getLong("created_at_ms"));

    private static final RowMapper<PublicationSegmentRow> PUBLICATION_SEGMENT_MAPPER = (rs, n) ->
            new PublicationSegmentRow(rs.getLong("id"), rs.getLong("publication_id"),
                    rs.getString("segment_id"), rs.getString("asset_id"), rs.getLong("grant_id"),
                    rs.getString("sponsor_key"), rs.getLong("start_ms"), rs.getLong("end_ms"));

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

    public void insertAsset(String id, String sponsorKey, long durationMs, long createdAtMs) {
        jdbc.update("INSERT INTO playout_asset (id, sponsor_key, duration_ms, created_at_ms)"
                        + " VALUES (?, ?, ?, ?)",
                id, sponsorKey, durationMs, createdAtMs);
    }

    public Optional<AssetRow> findAsset(String id) {
        return jdbc.query("SELECT id, sponsor_key, duration_ms FROM playout_asset WHERE id = ?",
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
                                         long grantId, String sponsorKey, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_publication_segment"
                        + " (publication_id, segment_id, asset_id, grant_id, sponsor_key, start_ms, end_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                publicationId, segmentId, assetId, grantId, sponsorKey, startMs, endMs);
    }

    /** 最新一次 ACTIVE 发布快照；已撤销发布不参与播出决定。 */
    public Optional<PublicationRow> findLatestPublication(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version,"
                        + " status, revoke_request_id, revoked_at_ms, created_at_ms"
                        + " FROM playout_publication"
                        + " WHERE channel_id = ? AND business_day = ? AND status = 'ACTIVE'"
                        + " ORDER BY published_version DESC LIMIT 1",
                PUBLICATION_MAPPER, channelId, Date.valueOf(businessDay)).stream().findFirst();
    }

    /** 按 ID 查询发布快照（含已撤销），用于明细查询与撤销。 */
    public Optional<PublicationRow> findPublication(long id) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version,"
                        + " status, revoke_request_id, revoked_at_ms, created_at_ms"
                        + " FROM playout_publication WHERE id = ?",
                PUBLICATION_MAPPER, id).stream().findFirst();
    }

    /** 按 ID 查询发布快照并加行锁，用于撤销与并发发布按提交顺序串行。 */
    public Optional<PublicationRow> findPublicationForUpdate(long id) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version,"
                        + " status, revoke_request_id, revoked_at_ms, created_at_ms"
                        + " FROM playout_publication WHERE id = ? FOR UPDATE",
                PUBLICATION_MAPPER, id).stream().findFirst();
    }

    /** 撤销发布；返回受影响行数，0 表示不存在或已撤销。 */
    public int revokePublication(long id, String revokeRequestId, long revokedAtMs) {
        return jdbc.update("UPDATE playout_publication"
                        + " SET status = 'REVOKED', revoke_request_id = ?, revoked_at_ms = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                revokeRequestId, revokedAtMs, id);
    }

    /** 快照全部片段，按开始时间与片段 ID 排序（稳定顺序）。 */
    public List<PublicationSegmentRow> findPublicationSegments(long publicationId) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, sponsor_key,"
                        + " start_ms, end_ms FROM playout_publication_segment"
                        + " WHERE publication_id = ? ORDER BY start_ms, segment_id",
                PUBLICATION_SEGMENT_MAPPER, publicationId);
    }

    /** 快照中覆盖指定时刻的片段（start <= at < end）。 */
    public Optional<PublicationSegmentRow> findPublicationSegmentAt(long publicationId, long atMs) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, sponsor_key,"
                        + " start_ms, end_ms"
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

    // ---------- 赞助排他窗 ----------

    private static final RowMapper<SponsorWindowRow> WINDOW_MAPPER = (rs, n) ->
            new SponsorWindowRow(rs.getLong("id"), rs.getString("window_key"),
                    rs.getLong("version"), rs.getString("sponsor_key"),
                    rs.getDate("business_day").toLocalDate(),
                    rs.getLong("start_ms"), rs.getLong("end_ms"),
                    rs.getString("status"), rs.getLong("created_at_ms"));

    private static final RowMapper<WindowHitRow> WINDOW_HIT_MAPPER = (rs, n) ->
            new WindowHitRow(rs.getLong("id"), rs.getLong("publication_id"),
                    rs.getString("window_key"), rs.getLong("window_version"),
                    rs.getLong("window_id"), rs.getString("sponsor_key"),
                    rs.getString("channel_id"), rs.getLong("start_ms"), rs.getLong("end_ms"));

    private static final String WINDOW_COLUMNS =
            "id, window_key, version, sponsor_key, business_day, start_ms, end_ms, status, created_at_ms";

    /** 插入排他窗新版本，返回自增 ID。 */
    public long insertWindow(String windowKey, long version, String sponsorKey,
                             LocalDate businessDay, long startMs, long endMs, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_sponsor_window"
                            + " (window_key, version, sponsor_key, business_day, start_ms, end_ms,"
                            + "  status, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, windowKey);
            ps.setLong(2, version);
            ps.setString(3, sponsorKey);
            ps.setDate(4, Date.valueOf(businessDay));
            ps.setLong(5, startMs);
            ps.setLong(6, endMs);
            ps.setLong(7, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertWindowChannel(long windowId, String channelId) {
        jdbc.update("INSERT INTO playout_sponsor_window_channel (window_id, channel_id)"
                + " VALUES (?, ?)", windowId, channelId);
    }

    /** 窗口频道范围，按字典序排序（稳定顺序）。 */
    public List<String> findWindowChannels(long windowId) {
        return jdbc.queryForList("SELECT channel_id FROM playout_sponsor_window_channel"
                + " WHERE window_id = ? ORDER BY channel_id", String.class, windowId);
    }

    /** 窗口最新版本（任意状态）。 */
    public Optional<SponsorWindowRow> findLatestWindow(String windowKey) {
        return jdbc.query("SELECT " + WINDOW_COLUMNS + " FROM playout_sponsor_window"
                        + " WHERE window_key = ? ORDER BY version DESC LIMIT 1",
                WINDOW_MAPPER, windowKey).stream().findFirst();
    }

    /** 窗口最新版本并加行锁，用于修订/删除串行化。 */
    public Optional<SponsorWindowRow> findLatestWindowForUpdate(String windowKey) {
        return jdbc.query("SELECT " + WINDOW_COLUMNS + " FROM playout_sponsor_window"
                        + " WHERE window_key = ? ORDER BY version DESC LIMIT 1 FOR UPDATE",
                WINDOW_MAPPER, windowKey).stream().findFirst();
    }

    /** 将窗口旧版本标记为 SUPERSEDED（修订时调用）。 */
    public void supersedeWindow(long windowId) {
        jdbc.update("UPDATE playout_sponsor_window SET status = 'SUPERSEDED' WHERE id = ?", windowId);
    }

    /** 将窗口最新版本标记为 DELETED（删除时调用）。 */
    public void deleteWindow(long windowId) {
        jdbc.update("UPDATE playout_sponsor_window SET status = 'DELETED' WHERE id = ?", windowId);
    }

    /**
     * 同赞助商、同频道、与 [startMs, endMs) 相交的 ACTIVE 窗口（左闭右开，相邻不算相交）。
     * excludeWindowKey 用于修订时排除自身。须在持频道锁后调用。
     */
    public List<SponsorWindowRow> findActiveOverlappingWindows(String sponsorKey, String channelId,
                                                               long startMs, long endMs,
                                                               String excludeWindowKey) {
        return jdbc.query("SELECT " + WINDOW_COLUMNS + " FROM playout_sponsor_window w"
                        + " JOIN playout_sponsor_window_channel wc ON wc.window_id = w.id"
                        + " WHERE w.status = 'ACTIVE' AND w.sponsor_key = ? AND wc.channel_id = ?"
                        + " AND w.start_ms < ? AND w.end_ms > ? AND w.window_key <> ?"
                        + " ORDER BY w.start_ms, w.window_key",
                WINDOW_MAPPER, sponsorKey, channelId, endMs, startMs,
                excludeWindowKey == null ? "" : excludeWindowKey);
    }

    /** 频道上与 [startMs, endMs) 相交的全部 ACTIVE 窗口（不限赞助商），用于发布与插播校验。 */
    public List<SponsorWindowRow> findActiveWindowsAt(String channelId, long startMs, long endMs) {
        return jdbc.query("SELECT " + WINDOW_COLUMNS + " FROM playout_sponsor_window w"
                        + " JOIN playout_sponsor_window_channel wc ON wc.window_id = w.id"
                        + " WHERE w.status = 'ACTIVE' AND wc.channel_id = ?"
                        + " AND w.start_ms < ? AND w.end_ms > ?"
                        + " ORDER BY w.start_ms, w.window_key",
                WINDOW_MAPPER, channelId, endMs, startMs);
    }

    /** 频道某业务日的 ACTIVE 窗口列表，按开始时间与窗口键排序（稳定顺序，只读诊断）。 */
    public List<SponsorWindowRow> findActiveWindowsForDay(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT " + WINDOW_COLUMNS + " FROM playout_sponsor_window w"
                        + " JOIN playout_sponsor_window_channel wc ON wc.window_id = w.id"
                        + " WHERE w.status = 'ACTIVE' AND wc.channel_id = ? AND w.business_day = ?"
                        + " ORDER BY w.start_ms, w.window_key",
                WINDOW_MAPPER, channelId, Date.valueOf(businessDay));
    }

    // ---------- 发布命中排他窗历史 ----------

    public void insertWindowHit(long publicationId, SponsorWindowRow window, String channelId,
                                long createdAtMs) {
        jdbc.update("INSERT INTO playout_publication_window_hit"
                        + " (publication_id, window_key, window_version, window_id, sponsor_key,"
                        + "  channel_id, start_ms, end_ms, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicationId, window.windowKey(), window.version(), window.id(),
                window.sponsorKey(), channelId, window.startMs(), window.endMs(), createdAtMs);
    }

    /** 发布的命中窗口历史，按窗口键与版本排序（稳定顺序）；撤销发布后保留可查。 */
    public List<WindowHitRow> findWindowHits(long publicationId) {
        return jdbc.query("SELECT id, publication_id, window_key, window_version, window_id,"
                        + " sponsor_key, channel_id, start_ms, end_ms"
                        + " FROM playout_publication_window_hit"
                        + " WHERE publication_id = ? ORDER BY window_key, window_version, channel_id",
                WINDOW_HIT_MAPPER, publicationId);
    }
}
