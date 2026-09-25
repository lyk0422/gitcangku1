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

    /** 素材行；rating 为登记时声明的分级，NULL 表示未声明（校验按 MATURE 处理）。 */
    public record AssetRow(String id, long durationMs, Rating rating) {
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

    /** 管控时段行；[startMs, endMs) 左闭右开，落在 businessDay 运营日内。 */
    public record RatingWindowRow(long id, String channelId, LocalDate businessDay,
                                  long startMs, long endMs, Rating maxRating) {
    }

    /** 发布分级校验记录行；publicationId 为 NULL 表示校验未通过、发布被拦截。 */
    public record RatingCheckRow(long id, Long publicationId, String channelId,
                                 LocalDate businessDay, String segmentId, String assetId,
                                 Rating rating, Long windowId, Rating windowMaxRating,
                                 String verdict, long createdAtMs) {
    }

    private static final RowMapper<AssetRow> ASSET_MAPPER = (rs, n) ->
            new AssetRow(rs.getString("id"), rs.getLong("duration_ms"),
                    ratingOf(rs.getString("rating")));

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

    private static final RowMapper<RatingWindowRow> RATING_WINDOW_MAPPER = (rs, n) ->
            new RatingWindowRow(rs.getLong("id"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(),
                    rs.getLong("start_ms"), rs.getLong("end_ms"),
                    Rating.valueOf(rs.getString("max_rating")));

    private static final RowMapper<RatingCheckRow> RATING_CHECK_MAPPER = (rs, n) ->
            new RatingCheckRow(rs.getLong("id"), rs.getObject("publication_id", Long.class),
                    rs.getString("channel_id"), rs.getDate("business_day").toLocalDate(),
                    rs.getString("segment_id"), rs.getString("asset_id"),
                    Rating.valueOf(rs.getString("rating")),
                    rs.getObject("window_id", Long.class),
                    ratingOf(rs.getString("window_max_rating")),
                    rs.getString("verdict"), rs.getLong("created_at_ms"));

    private static Rating ratingOf(String value) {
        return value == null ? null : Rating.valueOf(value);
    }

    // ---------- 素材 ----------

    public void insertAsset(String id, long durationMs, Rating rating, long createdAtMs) {
        jdbc.update("INSERT INTO playout_asset (id, duration_ms, rating, created_at_ms)"
                        + " VALUES (?, ?, ?, ?)",
                id, durationMs, rating == null ? null : rating.name(), createdAtMs);
    }

    public Optional<AssetRow> findAsset(String id) {
        return jdbc.query("SELECT id, duration_ms, rating FROM playout_asset WHERE id = ?",
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

    /** 查询频道并加行锁（FOR UPDATE），用于串行化管控时段配置变更与发布/插播的分级判定。 */
    public Optional<ChannelRow> findChannelForUpdate(String id) {
        return jdbc.query("SELECT id, fallback_asset_id FROM playout_channel WHERE id = ? FOR UPDATE",
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

    // ---------- 管控时段 ----------

    public long insertRatingWindow(String channelId, LocalDate businessDay, long startMs,
                                   long endMs, Rating maxRating, long nowMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_rating_window"
                            + " (channel_id, business_day, start_ms, end_ms, max_rating,"
                            + " created_at_ms, updated_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setDate(2, Date.valueOf(businessDay));
            ps.setLong(3, startMs);
            ps.setLong(4, endMs);
            ps.setString(5, maxRating.name());
            ps.setLong(6, nowMs);
            ps.setLong(7, nowMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 更新管控时段；返回受影响行数，0 表示时段不存在。 */
    public int updateRatingWindow(long id, long startMs, long endMs, Rating maxRating,
                                  long updatedAtMs) {
        return jdbc.update("UPDATE playout_rating_window"
                        + " SET start_ms = ?, end_ms = ?, max_rating = ?, updated_at_ms = ?"
                        + " WHERE id = ?",
                startMs, endMs, maxRating.name(), updatedAtMs, id);
    }

    public Optional<RatingWindowRow> findRatingWindow(long id) {
        return jdbc.query("SELECT id, channel_id, business_day, start_ms, end_ms, max_rating"
                        + " FROM playout_rating_window WHERE id = ?",
                RATING_WINDOW_MAPPER, id).stream().findFirst();
    }

    /** 频道某运营日的全部管控时段，按开始时刻升序。 */
    public List<RatingWindowRow> findRatingWindows(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT id, channel_id, business_day, start_ms, end_ms, max_rating"
                        + " FROM playout_rating_window"
                        + " WHERE channel_id = ? AND business_day = ? ORDER BY start_ms, id",
                RATING_WINDOW_MAPPER, channelId, Date.valueOf(businessDay));
    }

    /** 覆盖指定时刻的管控时段（start <= at < end）；时段互不重叠，至多一条。 */
    public Optional<RatingWindowRow> findRatingWindowAt(String channelId, LocalDate businessDay,
                                                        long atMs) {
        return jdbc.query("SELECT id, channel_id, business_day, start_ms, end_ms, max_rating"
                        + " FROM playout_rating_window"
                        + " WHERE channel_id = ? AND business_day = ?"
                        + " AND start_ms <= ? AND end_ms > ?"
                        + " ORDER BY start_ms LIMIT 1",
                RATING_WINDOW_MAPPER, channelId, Date.valueOf(businessDay), atMs, atMs)
                .stream().findFirst();
    }

    /** 与 [startMs, endMs) 重叠（不含端点相接）的管控时段。 */
    public List<RatingWindowRow> findOverlappingWindows(String channelId, LocalDate businessDay,
                                                        long startMs, long endMs) {
        return jdbc.query("SELECT id, channel_id, business_day, start_ms, end_ms, max_rating"
                        + " FROM playout_rating_window"
                        + " WHERE channel_id = ? AND business_day = ?"
                        + " AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY start_ms, id",
                RATING_WINDOW_MAPPER, channelId, Date.valueOf(businessDay), endMs, startMs);
    }

    /** 同上，但排除指定时段自身（用于修改时段时的重叠校验）。 */
    public List<RatingWindowRow> findOverlappingWindowsExcluding(String channelId,
                                                                 LocalDate businessDay,
                                                                 long startMs, long endMs,
                                                                 long excludeId) {
        return jdbc.query("SELECT id, channel_id, business_day, start_ms, end_ms, max_rating"
                        + " FROM playout_rating_window"
                        + " WHERE channel_id = ? AND business_day = ?"
                        + " AND start_ms < ? AND end_ms > ? AND id <> ?"
                        + " ORDER BY start_ms, id",
                RATING_WINDOW_MAPPER, channelId, Date.valueOf(businessDay), endMs, startMs,
                excludeId);
    }

    // ---------- 发布分级校验记录 ----------

    public void insertRatingCheck(Long publicationId, String channelId, LocalDate businessDay,
                                  String segmentId, String assetId, Rating rating,
                                  Long windowId, Rating windowMaxRating,
                                  String verdict, long createdAtMs) {
        jdbc.update("INSERT INTO playout_rating_check"
                        + " (publication_id, channel_id, business_day, segment_id, asset_id,"
                        + " rating, window_id, window_max_rating, verdict, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicationId, channelId, Date.valueOf(businessDay), segmentId, assetId,
                rating.name(), windowId, windowMaxRating == null ? null : windowMaxRating.name(),
                verdict, createdAtMs);
    }

    /** 频道某业务日的全部校验记录（含被拦截的失败记录），按记录 ID 升序。 */
    public List<RatingCheckRow> findRatingChecks(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT id, publication_id, channel_id, business_day, segment_id,"
                        + " asset_id, rating, window_id, window_max_rating, verdict, created_at_ms"
                        + " FROM playout_rating_check"
                        + " WHERE channel_id = ? AND business_day = ? ORDER BY id",
                RATING_CHECK_MAPPER, channelId, Date.valueOf(businessDay));
    }

    // ---------- 紧急插播 ----------

    public long insertBreakin(String channelId, String assetId, long atMs, String requestId,
                              long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_breakin (channel_id, asset_id, at_ms, request_id,"
                            + " created_at_ms) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setString(2, assetId);
            ps.setLong(3, atMs);
            ps.setString(4, requestId);
            ps.setLong(5, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 判断是否为唯一键冲突（草稿首建、发布版本、请求 ID 等并发场景）。 */
    public static boolean isDuplicateKey(RuntimeException ex) {
        return ex instanceof DuplicateKeyException;
    }
}
