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

    /** 素材行；version 随版本拉取递增，withdrawn 为终态撤回标记。 */
    public record AssetRow(String id, long durationMs, long version, boolean withdrawn) {
    }

    /** 频道行。 */
    public record ChannelRow(String id, String fallbackAssetId) {
    }

    /** 授权行；regionCode 为 NULL 表示全部区域，version 初始 1、撤销时递增。 */
    public record GrantRow(long id, String channelId, String assetId, String regionCode,
                           long validFromMs, long validToMs, long version, boolean revoked) {
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
            new AssetRow(rs.getString("id"), rs.getLong("duration_ms"),
                    rs.getLong("version"), rs.getBoolean("withdrawn"));

    private static final RowMapper<ChannelRow> CHANNEL_MAPPER = (rs, n) ->
            new ChannelRow(rs.getString("id"), rs.getString("fallback_asset_id"));

    private static final RowMapper<GrantRow> GRANT_MAPPER = (rs, n) ->
            new GrantRow(rs.getLong("id"), rs.getString("channel_id"), rs.getString("asset_id"),
                    rs.getString("region_code"),
                    rs.getLong("valid_from_ms"), rs.getLong("valid_to_ms"),
                    rs.getLong("version"), rs.getBoolean("revoked"));

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
        return jdbc.query("SELECT id, duration_ms, version, withdrawn FROM playout_asset WHERE id = ?",
                ASSET_MAPPER, id).stream().findFirst();
    }

    /** 撤回素材；返回受影响行数，0 表示不存在或已撤回。撤回同时递增素材版本。 */
    public int withdrawAsset(String id) {
        return jdbc.update("UPDATE playout_asset SET withdrawn = 1, version = version + 1"
                + " WHERE id = ? AND withdrawn = 0", id);
    }

    /** 拉取素材新版本：版本递增；返回受影响行数，0 表示素材不存在。 */
    public int pullAssetVersion(String id) {
        return jdbc.update("UPDATE playout_asset SET version = version + 1 WHERE id = ?", id);
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

    public long insertGrant(String channelId, String assetId, String regionCode,
                            long validFromMs, long validToMs, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_grant (channel_id, asset_id, region_code,"
                            + " valid_from_ms, valid_to_ms, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setString(2, assetId);
            ps.setString(3, regionCode);
            ps.setLong(4, validFromMs);
            ps.setLong(5, validToMs);
            ps.setLong(6, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<GrantRow> findGrant(long id) {
        return jdbc.query("SELECT id, channel_id, asset_id, region_code, valid_from_ms, valid_to_ms, version, revoked"
                + " FROM playout_grant WHERE id = ?", GRANT_MAPPER, id).stream().findFirst();
    }

    /** 按 ID 查询授权并加行锁，用于紧急插播创建与授权撤销按提交顺序串行化。 */
    public Optional<GrantRow> findGrantForUpdate(long id) {
        return jdbc.query("SELECT id, channel_id, asset_id, region_code, valid_from_ms, valid_to_ms, version, revoked"
                + " FROM playout_grant WHERE id = ? FOR UPDATE", GRANT_MAPPER, id)
                .stream().findFirst();
    }

    /** 撤销授权并递增授权版本；返回受影响行数，0 表示不存在或已撤销。 */
    public int revokeGrant(long id, String revokeRequestId, long revokedAtMs) {
        return jdbc.update("UPDATE playout_grant SET revoked = 1, version = version + 1,"
                + " revoke_request_id = ?, revoked_at_ms = ?"
                + " WHERE id = ? AND revoked = 0", revokeRequestId, revokedAtMs, id);
    }

    /** 查找完整覆盖 [startMs, endMs) 的未撤销授权（区间左闭右开）。 */
    public List<GrantRow> findCoveringGrants(String channelId, String assetId,
                                             long startMs, long endMs) {
        return jdbc.query("SELECT id, channel_id, asset_id, region_code, valid_from_ms, valid_to_ms, version, revoked"
                        + " FROM playout_grant"
                        + " WHERE channel_id = ? AND asset_id = ? AND revoked = 0"
                        + " AND valid_from_ms <= ? AND valid_to_ms >= ?",
                GRANT_MAPPER, channelId, assetId, startMs, endMs);
    }

    /** 同上，但加行锁（FOR UPDATE），用于发布时与撤销串行化。 */
    public List<GrantRow> findCoveringGrantsForUpdate(String channelId, String assetId,
                                                      long startMs, long endMs) {
        return jdbc.query("SELECT id, channel_id, asset_id, region_code, valid_from_ms, valid_to_ms, version, revoked"
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

    // ---------- 区域插播配置 ----------

    /** 区域插播配置行；grantVersion 为配置时固化的授权版本。 */
    public record SpliceConfigRow(long id, String channelId, LocalDate businessDay, String segmentId,
                                  String regionCode, String assetId, long grantId, long grantVersion,
                                  long startMs, long endMs) {
    }

    private static final RowMapper<SpliceConfigRow> SPLICE_MAPPER = (rs, n) ->
            new SpliceConfigRow(rs.getLong("id"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(), rs.getString("segment_id"),
                    rs.getString("region_code"), rs.getString("asset_id"),
                    rs.getLong("grant_id"), rs.getLong("grant_version"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"));

    private static final String SPLICE_COLUMNS =
            "id, channel_id, business_day, segment_id, region_code, asset_id, grant_id,"
                    + " grant_version, start_ms, end_ms";

    public void insertSpliceConfig(String channelId, LocalDate businessDay, String segmentId,
                                   String regionCode, String assetId, long grantId, long grantVersion,
                                   long startMs, long endMs, long createdAtMs) {
        jdbc.update("INSERT INTO playout_splice_config"
                        + " (channel_id, business_day, segment_id, region_code, asset_id, grant_id,"
                        + "  grant_version, start_ms, end_ms, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                channelId, Date.valueOf(businessDay), segmentId, regionCode, assetId, grantId,
                grantVersion, startMs, endMs, createdAtMs);
    }

    /** 删除条目全部插播配置（整份替换前调用）。 */
    public void deleteSpliceConfigs(String channelId, LocalDate businessDay, String segmentId) {
        jdbc.update("DELETE FROM playout_splice_config"
                        + " WHERE channel_id = ? AND business_day = ? AND segment_id = ?",
                channelId, Date.valueOf(businessDay), segmentId);
    }

    /** 删除频道某业务日全部插播配置（草稿整份替换时清除）。 */
    public void deleteSpliceConfigsForDay(String channelId, LocalDate businessDay) {
        jdbc.update("DELETE FROM playout_splice_config WHERE channel_id = ? AND business_day = ?",
                channelId, Date.valueOf(businessDay));
    }

    /** 某条目的全部插播配置，按区域、窗口起点稳定排序。 */
    public List<SpliceConfigRow> findSpliceConfigs(String channelId, LocalDate businessDay,
                                                   String segmentId) {
        return jdbc.query("SELECT " + SPLICE_COLUMNS + " FROM playout_splice_config"
                        + " WHERE channel_id = ? AND business_day = ? AND segment_id = ?"
                        + " ORDER BY region_code, start_ms, id",
                SPLICE_MAPPER, channelId, Date.valueOf(businessDay), segmentId);
    }

    /** 频道某业务日全部插播配置，按条目、区域、窗口起点稳定排序。 */
    public List<SpliceConfigRow> findSpliceConfigsForDay(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT " + SPLICE_COLUMNS + " FROM playout_splice_config"
                        + " WHERE channel_id = ? AND business_day = ?"
                        + " ORDER BY segment_id, region_code, start_ms, id",
                SPLICE_MAPPER, channelId, Date.valueOf(businessDay));
    }

    /** 锁定草稿行，串行化插播配置与草稿整份替换。 */
    public Optional<DraftRow> findDraftForUpdate(String channelId, LocalDate businessDay) {
        return jdbc.query("SELECT channel_id, business_day, version FROM playout_draft"
                        + " WHERE channel_id = ? AND business_day = ? FOR UPDATE",
                DRAFT_MAPPER, channelId, Date.valueOf(businessDay)).stream().findFirst();
    }

    // ---------- 黑屏窗口 ----------

    /** 黑屏窗口行；status 为 ACTIVE / CANCELLED。 */
    public record BlackoutRow(long id, String channelId, String regionCode,
                              long startMs, long endMs, String status,
                              String cancelRequestId, Long cancelledAtMs, long createdAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private static final RowMapper<BlackoutRow> BLACKOUT_MAPPER = (rs, n) ->
            new BlackoutRow(rs.getLong("id"), rs.getString("channel_id"), rs.getString("region_code"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("status"),
                    rs.getString("cancel_request_id"),
                    (Long) rs.getObject("cancelled_at_ms"), rs.getLong("created_at_ms"));

    private static final String BLACKOUT_COLUMNS =
            "id, channel_id, region_code, start_ms, end_ms, status, cancel_request_id,"
                    + " cancelled_at_ms, created_at_ms";

    public long insertBlackout(String channelId, String regionCode, long startMs, long endMs,
                               long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_blackout (channel_id, region_code, start_ms, end_ms,"
                            + " status, created_at_ms) VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setString(2, regionCode);
            ps.setLong(3, startMs);
            ps.setLong(4, endMs);
            ps.setLong(5, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<BlackoutRow> findBlackout(long id) {
        return jdbc.query("SELECT " + BLACKOUT_COLUMNS + " FROM playout_blackout WHERE id = ?",
                BLACKOUT_MAPPER, id).stream().findFirst();
    }

    /** 按 ID 查询并加行锁，串行化取消与发布校验。 */
    public Optional<BlackoutRow> findBlackoutForUpdate(long id) {
        return jdbc.query("SELECT " + BLACKOUT_COLUMNS + " FROM playout_blackout WHERE id = ?"
                        + " FOR UPDATE",
                BLACKOUT_MAPPER, id).stream().findFirst();
    }

    /** 取消黑屏窗口；返回受影响行数，0 表示不存在或已取消。 */
    public int cancelBlackout(long id, String cancelRequestId, long cancelledAtMs) {
        return jdbc.update("UPDATE playout_blackout"
                        + " SET status = 'CANCELLED', cancel_request_id = ?, cancelled_at_ms = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                cancelRequestId, cancelledAtMs, id);
    }

    /** 与 [startMs, endMs) 相交的 ACTIVE 黑屏窗口（左闭右开：端点相接不算相交）。 */
    public List<BlackoutRow> findActiveBlackoutsOverlapping(String channelId, String regionCode,
                                                            long startMs, long endMs) {
        return jdbc.query("SELECT " + BLACKOUT_COLUMNS + " FROM playout_blackout"
                        + " WHERE channel_id = ? AND region_code = ? AND status = 'ACTIVE'"
                        + " AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY start_ms, id",
                BLACKOUT_MAPPER, channelId, regionCode, endMs, startMs);
    }

    // ---------- 发布区域快照 ----------

    /** 发布区域解析快照行，只读；spliceStartMs/spliceEndMs 为 NULL 表示 MAIN 行。 */
    public record PublicationRegionRow(long id, long publicationId, String segmentId,
                                       String regionCode, String assetId, long grantId,
                                       long grantVersion, Long spliceStartMs, Long spliceEndMs,
                                       String source, String fallbackReason) {
    }

    private static final RowMapper<PublicationRegionRow> PUB_REGION_MAPPER = (rs, n) ->
            new PublicationRegionRow(rs.getLong("id"), rs.getLong("publication_id"),
                    rs.getString("segment_id"), rs.getString("region_code"),
                    rs.getString("asset_id"), rs.getLong("grant_id"), rs.getLong("grant_version"),
                    (Long) rs.getObject("splice_start_ms"), (Long) rs.getObject("splice_end_ms"),
                    rs.getString("source"), rs.getString("fallback_reason"));

    private static final String PUB_REGION_COLUMNS =
            "id, publication_id, segment_id, region_code, asset_id, grant_id, grant_version,"
                    + " splice_start_ms, splice_end_ms, source, fallback_reason";

    public void insertPublicationRegion(long publicationId, String segmentId, String regionCode,
                                        String assetId, long grantId, long grantVersion,
                                        Long spliceStartMs, Long spliceEndMs,
                                        String source, String fallbackReason, long createdAtMs) {
        jdbc.update("INSERT INTO playout_publication_region"
                        + " (publication_id, segment_id, region_code, asset_id, grant_id,"
                        + "  grant_version, splice_start_ms, splice_end_ms, source, fallback_reason,"
                        + "  created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicationId, segmentId, regionCode, assetId, grantId, grantVersion,
                spliceStartMs, spliceEndMs, source, fallbackReason, createdAtMs);
    }

    /** 快照全部区域行，按条目、区域、窗口起点稳定排序。 */
    public List<PublicationRegionRow> findPublicationRegions(long publicationId) {
        return jdbc.query("SELECT " + PUB_REGION_COLUMNS + " FROM playout_publication_region"
                        + " WHERE publication_id = ?"
                        + " ORDER BY segment_id, region_code, splice_start_ms, id",
                PUB_REGION_MAPPER, publicationId);
    }

    /** 快照中某条目某区域的全部解析行（SPLICE 与 MAIN）。 */
    public List<PublicationRegionRow> findPublicationRegions(long publicationId, String segmentId,
                                                             String regionCode) {
        return jdbc.query("SELECT " + PUB_REGION_COLUMNS + " FROM playout_publication_region"
                        + " WHERE publication_id = ? AND segment_id = ? AND region_code = ?"
                        + " ORDER BY splice_start_ms, id",
                PUB_REGION_MAPPER, publicationId, segmentId, regionCode);
    }

    /** 按 ID 查询发布快照头。 */
    public Optional<PublicationRow> findPublicationById(long publicationId) {
        return jdbc.query("SELECT id, channel_id, business_day, published_version, draft_version"
                        + " FROM playout_publication WHERE id = ?",
                PUBLICATION_MAPPER, publicationId).stream().findFirst();
    }

    /** 快照全部条目行，按起点稳定排序。 */
    public List<PublicationSegmentRow> findPublicationSegments(long publicationId) {
        return jdbc.query("SELECT id, publication_id, segment_id, asset_id, grant_id, start_ms, end_ms"
                        + " FROM playout_publication_segment"
                        + " WHERE publication_id = ? ORDER BY start_ms, segment_id",
                PUBLICATION_SEGMENT_MAPPER, publicationId);
    }

    // ---------- 区域播出回执 ----------

    public long insertReceipt(String channelId, String regionCode, long atMs, String assetId,
                              String source, Long publicationId, String segmentId,
                              Long grantId, Long grantVersion, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_receipt"
                            + " (channel_id, region_code, at_ms, asset_id, source, publication_id,"
                            + "  segment_id, grant_id, grant_version, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setString(2, regionCode);
            ps.setLong(3, atMs);
            ps.setString(4, assetId);
            ps.setString(5, source);
            if (publicationId == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, publicationId);
            }
            ps.setString(7, segmentId);
            if (grantId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, grantId);
            }
            if (grantVersion == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, grantVersion);
            }
            ps.setLong(10, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
