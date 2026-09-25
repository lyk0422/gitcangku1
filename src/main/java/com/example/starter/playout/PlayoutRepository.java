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

    /** 素材行；rating 为 NULL 表示历史素材未声明分级，校验时按 MATURE 处理。 */
    public record AssetRow(String id, long durationMs, String rating) {
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

    /** 管控时段行；起止为自运营日 00:00 起的分钟数，左闭右开。 */
    public record RatingWindowRow(long id, String channelId, int startMinute, int endMinute,
                                  String maxRating, long version, boolean revoked) {
    }

    /** 历史发布分级校验记录行。 */
    public record RatingCheckRow(long id, long publicationId, String channelId,
                                 LocalDate businessDay, long publishedVersion,
                                 String segmentId, String assetId, String assetRating,
                                 long startMs, long endMs,
                                 Long windowId, Integer windowStartMinute, Integer windowEndMinute,
                                 String allowedRating) {
    }

    /** 紧急插播行。 */
    public record InterruptionRow(long id, String channelId, String assetId, long atMs,
                                  String assetRating, Long windowId) {
    }

    private static final RowMapper<AssetRow> ASSET_MAPPER = (rs, n) ->
            new AssetRow(rs.getString("id"), rs.getLong("duration_ms"), rs.getString("rating"));

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
                    rs.getInt("start_minute"), rs.getInt("end_minute"),
                    rs.getString("max_rating"), rs.getLong("version"), rs.getBoolean("revoked"));

    private static final RowMapper<RatingCheckRow> RATING_CHECK_MAPPER = (rs, n) ->
            new RatingCheckRow(rs.getLong("id"), rs.getLong("publication_id"),
                    rs.getString("channel_id"), rs.getDate("business_day").toLocalDate(),
                    rs.getLong("published_version"), rs.getString("segment_id"),
                    rs.getString("asset_id"), rs.getString("asset_rating"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"),
                    (Long) rs.getObject("window_id"),
                    (Integer) rs.getObject("window_start_minute"),
                    (Integer) rs.getObject("window_end_minute"),
                    rs.getString("allowed_rating"));

    private static final RowMapper<InterruptionRow> INTERRUPTION_MAPPER = (rs, n) ->
            new InterruptionRow(rs.getLong("id"), rs.getString("channel_id"),
                    rs.getString("asset_id"), rs.getLong("at_ms"),
                    rs.getString("asset_rating"), (Long) rs.getObject("window_id"));

    // ---------- 素材 ----------

    public void insertAsset(String id, long durationMs, String rating, long createdAtMs) {
        jdbc.update("INSERT INTO playout_asset (id, duration_ms, rating, created_at_ms) VALUES (?, ?, ?, ?)",
                id, durationMs, rating, createdAtMs);
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

    // ---------- 频道行锁 ----------

    /** 锁定频道行，使同频道的时段变更、发布与插播事务按提交顺序串行裁决。 */
    public void lockChannel(String channelId) {
        jdbc.queryForObject("SELECT id FROM playout_channel WHERE id = ? FOR UPDATE",
                String.class, channelId);
    }

    // ---------- 管控时段 ----------

    public long insertRatingWindow(String channelId, int startMinute, int endMinute,
                                   String maxRating, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_rating_window"
                            + " (channel_id, start_minute, end_minute, max_rating, version,"
                            + " revoked, created_at_ms, updated_at_ms)"
                            + " VALUES (?, ?, ?, ?, 1, 0, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setInt(2, startMinute);
            ps.setInt(3, endMinute);
            ps.setString(4, maxRating);
            ps.setLong(5, createdAtMs);
            ps.setLong(6, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RatingWindowRow> findRatingWindow(long id) {
        return jdbc.query("SELECT id, channel_id, start_minute, end_minute, max_rating, version, revoked"
                + " FROM playout_rating_window WHERE id = ?", RATING_WINDOW_MAPPER, id)
                .stream().findFirst();
    }

    /** 频道全部生效中（未删除）的管控时段，按起点排序。 */
    public List<RatingWindowRow> findActiveRatingWindows(String channelId) {
        return jdbc.query("SELECT id, channel_id, start_minute, end_minute, max_rating, version, revoked"
                + " FROM playout_rating_window WHERE channel_id = ? AND revoked = 0"
                + " ORDER BY start_minute, id", RATING_WINDOW_MAPPER, channelId);
    }

    /** 同 {@link #findActiveRatingWindows}，但对时段行加锁，配合频道行锁在变更时做重叠校验。 */
    public List<RatingWindowRow> findActiveRatingWindowsForUpdate(String channelId) {
        return jdbc.query("SELECT id, channel_id, start_minute, end_minute, max_rating, version, revoked"
                + " FROM playout_rating_window WHERE channel_id = ? AND revoked = 0"
                + " ORDER BY start_minute, id FOR UPDATE", RATING_WINDOW_MAPPER, channelId);
    }

    /** 乐观锁修改时段；返回受影响行数，0 表示时段不存在、已删除或版本不符。 */
    public int updateRatingWindow(long id, int startMinute, int endMinute, String maxRating,
                                  long expectedVersion, long updatedAtMs) {
        return jdbc.update("UPDATE playout_rating_window"
                        + " SET start_minute = ?, end_minute = ?, max_rating = ?,"
                        + " version = version + 1, updated_at_ms = ?"
                        + " WHERE id = ? AND version = ? AND revoked = 0",
                startMinute, endMinute, maxRating, updatedAtMs, id, expectedVersion);
    }

    // ---------- 发布分级校验记录 ----------

    public void insertPublicationRatingCheck(long publicationId, String channelId,
                                             LocalDate businessDay, long publishedVersion,
                                             String segmentId, String assetId, String assetRating,
                                             long startMs, long endMs,
                                             Long windowId, Integer windowStartMinute,
                                             Integer windowEndMinute, String allowedRating,
                                             long createdAtMs) {
        jdbc.update("INSERT INTO playout_publication_rating_check"
                        + " (publication_id, channel_id, business_day, published_version,"
                        + " segment_id, asset_id, asset_rating, start_ms, end_ms,"
                        + " window_id, window_start_minute, window_end_minute, allowed_rating, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicationId, channelId, Date.valueOf(businessDay), publishedVersion,
                segmentId, assetId, assetRating, startMs, endMs,
                windowId, windowStartMinute, windowEndMinute, allowedRating, createdAtMs);
    }

    /** 历史发布分级校验记录，按发布版本与片段起点排序。 */
    public List<RatingCheckRow> findRatingChecks(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT id, publication_id, channel_id, business_day, published_version,"
                        + " segment_id, asset_id, asset_rating, start_ms, end_ms,"
                        + " window_id, window_start_minute, window_end_minute, allowed_rating"
                        + " FROM playout_publication_rating_check"
                        + " WHERE channel_id = ? AND business_day = ?"
                        + " ORDER BY published_version, start_ms, id",
                RATING_CHECK_MAPPER, channelId, Date.valueOf(businessDay));
    }

    // ---------- 紧急插播 ----------

    public long insertInterruption(String channelId, String assetId, long atMs,
                                   String assetRating, Long windowId, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_interruption"
                            + " (channel_id, asset_id, at_ms, asset_rating, window_id, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setString(2, assetId);
            ps.setLong(3, atMs);
            ps.setString(4, assetRating);
            if (windowId == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, windowId);
            }
            ps.setLong(6, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 判断是否为唯一键冲突（草稿首建、发布版本、请求 ID 等并发场景）。 */
    public static boolean isDuplicateKey(RuntimeException ex) {
        return ex instanceof DuplicateKeyException;
    }
}
