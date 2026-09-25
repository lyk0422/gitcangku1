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

    // ---------- 联播锁定 ----------

    /** 联播组行；status 为 ACTIVE / REVOKED，创建即 ACTIVE，只能整组撤销。 */
    public record SimulcastGroupRow(String simulcastKey, LocalDate businessDay, String assetId,
                                    long atMs, int channelCount, String status,
                                    String revokeRequestId, Long revokedAtMs, long createdAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 联播频道占位行；grantId 为创建锁定时固化的每频道授权版本。 */
    public record SimulcastMemberRow(String simulcastKey, String channelId, LocalDate businessDay,
                                     String placeholderSegmentId, long grantId, String assetId,
                                     long atMs) {
    }

    /** 联播撤销历史行，只追加不改写。 */
    public record SimulcastRevocationRow(long id, String simulcastKey, String revokeRequestId,
                                         int channelCount, long revokedAtMs) {
    }

    private static final RowMapper<SimulcastGroupRow> SIMULCAST_GROUP_MAPPER = (rs, n) ->
            new SimulcastGroupRow(rs.getString("simulcast_key"),
                    rs.getDate("business_day").toLocalDate(), rs.getString("asset_id"),
                    rs.getLong("at_ms"), rs.getInt("channel_count"), rs.getString("status"),
                    rs.getString("revoke_request_id"),
                    (Long) rs.getObject("revoked_at_ms"), rs.getLong("created_at_ms"));

    private static final RowMapper<SimulcastMemberRow> SIMULCAST_MEMBER_MAPPER = (rs, n) ->
            new SimulcastMemberRow(rs.getString("simulcast_key"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(),
                    rs.getString("placeholder_segment_id"), rs.getLong("grant_id"),
                    rs.getString("asset_id"), rs.getLong("at_ms"));

    private static final RowMapper<SimulcastRevocationRow> SIMULCAST_REVOCATION_MAPPER = (rs, n) ->
            new SimulcastRevocationRow(rs.getLong("id"), rs.getString("simulcast_key"),
                    rs.getString("revoke_request_id"), rs.getInt("channel_count"),
                    rs.getLong("revoked_at_ms"));

    private static final String GROUP_COLUMNS = "simulcast_key, business_day, asset_id, at_ms,"
            + " channel_count, status, revoke_request_id, revoked_at_ms, created_at_ms";

    private static final String MEMBER_COLUMNS = "simulcast_key, channel_id, business_day,"
            + " placeholder_segment_id, grant_id, asset_id, at_ms";

    public void insertSimulcastGroup(String simulcastKey, LocalDate businessDay, String assetId,
                                     long atMs, int channelCount, long createdAtMs) {
        jdbc.update("INSERT INTO playout_simulcast_group"
                        + " (simulcast_key, business_day, asset_id, at_ms, channel_count, status, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                simulcastKey, Date.valueOf(businessDay), assetId, atMs, channelCount, createdAtMs);
    }

    public Optional<SimulcastGroupRow> findSimulcastGroup(String simulcastKey) {
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM playout_simulcast_group"
                        + " WHERE simulcast_key = ?",
                SIMULCAST_GROUP_MAPPER, simulcastKey).stream().findFirst();
    }

    /** 按全局键查询并加行锁，用于撤销与并发创建按提交顺序串行化。 */
    public Optional<SimulcastGroupRow> findSimulcastGroupForUpdate(String simulcastKey) {
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM playout_simulcast_group"
                        + " WHERE simulcast_key = ? FOR UPDATE",
                SIMULCAST_GROUP_MAPPER, simulcastKey).stream().findFirst();
    }

    /** 撤销联播组；返回受影响行数，0 表示不存在或已撤销。 */
    public int revokeSimulcastGroup(String simulcastKey, String revokeRequestId, long revokedAtMs) {
        return jdbc.update("UPDATE playout_simulcast_group"
                        + " SET status = 'REVOKED', revoke_request_id = ?, revoked_at_ms = ?"
                        + " WHERE simulcast_key = ? AND status = 'ACTIVE'",
                revokeRequestId, revokedAtMs, simulcastKey);
    }

    public void insertSimulcastMember(String simulcastKey, String channelId, LocalDate businessDay,
                                      String placeholderSegmentId, long grantId, String assetId,
                                      long atMs, long createdAtMs) {
        jdbc.update("INSERT INTO playout_simulcast_member"
                        + " (simulcast_key, channel_id, business_day, placeholder_segment_id,"
                        + "  grant_id, asset_id, at_ms, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                simulcastKey, channelId, Date.valueOf(businessDay), placeholderSegmentId,
                grantId, assetId, atMs, createdAtMs);
    }

    /** 联播组全部频道占位（无论组状态；撤销后占位已删除，返回空）。 */
    public List<SimulcastMemberRow> findSimulcastMembers(String simulcastKey) {
        return jdbc.query("SELECT " + MEMBER_COLUMNS + " FROM playout_simulcast_member"
                        + " WHERE simulcast_key = ? ORDER BY channel_id",
                SIMULCAST_MEMBER_MAPPER, simulcastKey);
    }

    /** 频道+业务日下属于 ACTIVE 联播组的全部占位，按时刻排序。 */
    public List<SimulcastMemberRow> findActiveMembersForChannelDay(String channelId,
                                                                   LocalDate businessDay) {
        return jdbc.query("SELECT m." + MEMBER_COLUMNS.replace(", ", ", m.")
                        + " FROM playout_simulcast_member m"
                        + " JOIN playout_simulcast_group g ON g.simulcast_key = m.simulcast_key"
                        + " WHERE m.channel_id = ? AND m.business_day = ? AND g.status = 'ACTIVE'"
                        + " ORDER BY m.at_ms, m.simulcast_key",
                SIMULCAST_MEMBER_MAPPER, channelId, Date.valueOf(businessDay));
    }

    /** 频道占位查询：businessDay 为 NULL 时返回该频道全部 ACTIVE 组占位。 */
    public List<SimulcastMemberRow> findChannelPlaceholders(String channelId, LocalDate businessDay) {
        if (businessDay == null) {
            return jdbc.query("SELECT m." + MEMBER_COLUMNS.replace(", ", ", m.")
                            + " FROM playout_simulcast_member m"
                            + " JOIN playout_simulcast_group g ON g.simulcast_key = m.simulcast_key"
                            + " WHERE m.channel_id = ? AND g.status = 'ACTIVE'"
                            + " ORDER BY m.business_day, m.at_ms, m.simulcast_key",
                    SIMULCAST_MEMBER_MAPPER, channelId);
        }
        return findActiveMembersForChannelDay(channelId, businessDay);
    }

    /** 频道某业务日某时刻是否已被 ACTIVE 联播组占位占用。 */
    public boolean existsActiveMemberAt(String channelId, LocalDate businessDay, long atMs) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_simulcast_member m"
                        + " JOIN playout_simulcast_group g ON g.simulcast_key = m.simulcast_key"
                        + " WHERE m.channel_id = ? AND m.business_day = ? AND m.at_ms = ?"
                        + " AND g.status = 'ACTIVE'",
                Integer.class, channelId, Date.valueOf(businessDay), atMs);
        return count != null && count > 0;
    }

    /** 频道上计划时刻落在 [startMs, endMs) 内的 ACTIVE 联播组占位（用于插播占用裁决）。 */
    public List<SimulcastMemberRow> findActiveMembersInRange(String channelId,
                                                             long startMs, long endMs) {
        return jdbc.query("SELECT m." + MEMBER_COLUMNS.replace(", ", ", m.")
                        + " FROM playout_simulcast_member m"
                        + " JOIN playout_simulcast_group g ON g.simulcast_key = m.simulcast_key"
                        + " WHERE m.channel_id = ? AND g.status = 'ACTIVE'"
                        + " AND m.at_ms >= ? AND m.at_ms < ?"
                        + " ORDER BY m.at_ms, m.simulcast_key",
                SIMULCAST_MEMBER_MAPPER, channelId, startMs, endMs);
    }

    /** 删除联播组全部频道占位（撤销时同事务释放）；返回释放条数。 */
    public int deleteSimulcastMembers(String simulcastKey) {
        return jdbc.update("DELETE FROM playout_simulcast_member WHERE simulcast_key = ?",
                simulcastKey);
    }

    public void insertSimulcastRevocation(String simulcastKey, String revokeRequestId,
                                          int channelCount, long revokedAtMs) {
        jdbc.update("INSERT INTO playout_simulcast_revocation"
                        + " (simulcast_key, revoke_request_id, channel_count, revoked_at_ms)"
                        + " VALUES (?, ?, ?, ?)",
                simulcastKey, revokeRequestId, channelCount, revokedAtMs);
    }

    /** 撤销历史查询：simulcastKey 为 NULL 时返回全部，按撤销时间升序。 */
    public List<SimulcastRevocationRow> findSimulcastRevocations(String simulcastKey) {
        if (simulcastKey == null) {
            return jdbc.query("SELECT id, simulcast_key, revoke_request_id, channel_count, revoked_at_ms"
                            + " FROM playout_simulcast_revocation ORDER BY revoked_at_ms, id",
                    SIMULCAST_REVOCATION_MAPPER);
        }
        return jdbc.query("SELECT id, simulcast_key, revoke_request_id, channel_count, revoked_at_ms"
                        + " FROM playout_simulcast_revocation WHERE simulcast_key = ?"
                        + " ORDER BY revoked_at_ms, id",
                SIMULCAST_REVOCATION_MAPPER, simulcastKey);
    }
}
