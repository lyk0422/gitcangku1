package com.example.starter.playout.lease;

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
 * 播出端版本租约数据访问层。所有时间列均为 UTC 纪元毫秒（BIGINT），业务日为 Asia/Shanghai 日历日。
 * 租约分段/插播快照与分段确认写入后只读不回写。
 */
@Repository
public class LeaseRepository {

    private final JdbcTemplate jdbc;

    public LeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 租约行；status 为 ACTIVE / COMPLETED / EXPIRED。 */
    public record LeaseRow(long id, String clientKey, String channelId, LocalDate businessDay,
                           long publicationId, long publishedVersion, long leaseEpoch,
                           String status, long expiresAtMs, long createdAtMs,
                           Long renewedAtMs, Long completedAtMs) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** 租约分段快照行；grantRevoked 为拉取时刻的授权判定。 */
    public record LeaseSegmentRow(long id, long leaseId, int seq, String segmentId, String assetId,
                                  long grantId, boolean grantRevoked, long startMs, long endMs) {
    }

    /** 租约插播快照行。 */
    public record LeaseOverrideRow(long id, long leaseId, String overrideKey, String assetId,
                                   long grantId, int priority, long startMs, long endMs) {
    }

    /** 分段确认行。 */
    public record LeaseAckRow(long id, String ackKey, long leaseId, long leaseEpoch, int seq,
                              String segmentId, long playedAtMs, long createdAtMs) {
    }

    /** 发布版本引用统计行；activeLeaseCount 为引用该版本的未过期 ACTIVE 租约数。 */
    public record PublicationReferenceRow(long publicationId, long publishedVersion,
                                          long activeLeaseCount) {
    }

    private static final RowMapper<LeaseRow> LEASE_MAPPER = (rs, n) ->
            new LeaseRow(rs.getLong("id"), rs.getString("client_key"), rs.getString("channel_id"),
                    rs.getDate("business_day").toLocalDate(), rs.getLong("publication_id"),
                    rs.getLong("published_version"), rs.getLong("lease_epoch"),
                    rs.getString("status"), rs.getLong("expires_at_ms"), rs.getLong("created_at_ms"),
                    (Long) rs.getObject("renewed_at_ms"), (Long) rs.getObject("completed_at_ms"));

    private static final RowMapper<LeaseSegmentRow> LEASE_SEGMENT_MAPPER = (rs, n) ->
            new LeaseSegmentRow(rs.getLong("id"), rs.getLong("lease_id"), rs.getInt("seq"),
                    rs.getString("segment_id"), rs.getString("asset_id"), rs.getLong("grant_id"),
                    rs.getBoolean("grant_revoked"), rs.getLong("start_ms"), rs.getLong("end_ms"));

    private static final RowMapper<LeaseOverrideRow> LEASE_OVERRIDE_MAPPER = (rs, n) ->
            new LeaseOverrideRow(rs.getLong("id"), rs.getLong("lease_id"),
                    rs.getString("override_key"), rs.getString("asset_id"), rs.getLong("grant_id"),
                    rs.getInt("priority"), rs.getLong("start_ms"), rs.getLong("end_ms"));

    private static final RowMapper<LeaseAckRow> LEASE_ACK_MAPPER = (rs, n) ->
            new LeaseAckRow(rs.getLong("id"), rs.getString("ack_key"), rs.getLong("lease_id"),
                    rs.getLong("lease_epoch"), rs.getInt("seq"), rs.getString("segment_id"),
                    rs.getLong("played_at_ms"), rs.getLong("created_at_ms"));

    private static final RowMapper<PublicationReferenceRow> PUBLICATION_REFERENCE_MAPPER = (rs, n) ->
            new PublicationReferenceRow(rs.getLong("id"), rs.getLong("published_version"),
                    rs.getLong("active_lease_count"));

    private static final String LEASE_COLUMNS = "id, client_key, channel_id, business_day,"
            + " publication_id, published_version, lease_epoch, status, expires_at_ms,"
            + " created_at_ms, renewed_at_ms, completed_at_ms";

    // ---------- 客户端 ----------

    /** 登记客户端（已存在则忽略冲突，由调用方捕获）。 */
    public void insertClient(String clientKey, long createdAtMs) {
        jdbc.update("INSERT INTO playout_edge_client (client_key, created_at_ms) VALUES (?, ?)",
                clientKey, createdAtMs);
    }

    /** 锁定客户端行，串行化同客户端的租约拉取与续租。 */
    public void lockClientForUpdate(String clientKey) {
        jdbc.queryForList("SELECT client_key FROM playout_edge_client WHERE client_key = ? FOR UPDATE",
                clientKey);
    }

    // ---------- 租约 ----------

    /** 查询客户端某业务日的 ACTIVE 租约并加行锁；须在持客户端锁后调用。 */
    public Optional<LeaseRow> findActiveLeaseForUpdate(String clientKey, LocalDate businessDay) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_lease"
                        + " WHERE client_key = ? AND business_day = ? AND status = 'ACTIVE' FOR UPDATE",
                LEASE_MAPPER, clientKey, Date.valueOf(businessDay)).stream().findFirst();
    }

    /** 客户端某业务日已使用的最大租约纪元，无租约时为 0。 */
    public long maxLeaseEpoch(String clientKey, LocalDate businessDay) {
        Long epoch = jdbc.queryForObject(
                "SELECT MAX(lease_epoch) FROM playout_lease WHERE client_key = ? AND business_day = ?",
                Long.class, clientKey, Date.valueOf(businessDay));
        return epoch == null ? 0L : epoch;
    }

    public long insertLease(String clientKey, String channelId, LocalDate businessDay,
                            long publicationId, long publishedVersion, long leaseEpoch,
                            long expiresAtMs, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_lease"
                            + " (client_key, channel_id, business_day, publication_id, published_version,"
                            + "  lease_epoch, status, expires_at_ms, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, clientKey);
            ps.setString(2, channelId);
            ps.setDate(3, Date.valueOf(businessDay));
            ps.setLong(4, publicationId);
            ps.setLong(5, publishedVersion);
            ps.setLong(6, leaseEpoch);
            ps.setLong(7, expiresAtMs);
            ps.setLong(8, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<LeaseRow> findLease(long leaseId) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_lease WHERE id = ?",
                LEASE_MAPPER, leaseId).stream().findFirst();
    }

    /** 按 ID 查询租约并加行锁，用于确认与续租的状态判定。 */
    public Optional<LeaseRow> findLeaseForUpdate(long leaseId) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM playout_lease WHERE id = ? FOR UPDATE",
                LEASE_MAPPER, leaseId).stream().findFirst();
    }

    /** 将 ACTIVE 租约标记为已过期；返回受影响行数。 */
    public int markLeaseExpired(long leaseId) {
        return jdbc.update("UPDATE playout_lease SET status = 'EXPIRED'"
                + " WHERE id = ? AND status = 'ACTIVE'", leaseId);
    }

    /** 续租：推进纪元并延长到期时间，绑定发布版本不变。 */
    public void renewLease(long leaseId, long newEpoch, long newExpiresAtMs, long renewedAtMs) {
        jdbc.update("UPDATE playout_lease SET lease_epoch = ?, expires_at_ms = ?, renewed_at_ms = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                newEpoch, newExpiresAtMs, renewedAtMs, leaseId);
    }

    /** 全部分段确认后标记完成；返回受影响行数。 */
    public int completeLease(long leaseId, long completedAtMs) {
        return jdbc.update("UPDATE playout_lease SET status = 'COMPLETED', completed_at_ms = ?"
                + " WHERE id = ? AND status = 'ACTIVE'", completedAtMs, leaseId);
    }

    // ---------- 租约快照 ----------

    public void insertLeaseSegment(long leaseId, int seq, String segmentId, String assetId,
                                   long grantId, boolean grantRevoked, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_lease_segment"
                        + " (lease_id, seq, segment_id, asset_id, grant_id, grant_revoked, start_ms, end_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                leaseId, seq, segmentId, assetId, grantId, grantRevoked, startMs, endMs);
    }

    public void insertLeaseOverride(long leaseId, String overrideKey, String assetId, long grantId,
                                    int priority, long startMs, long endMs) {
        jdbc.update("INSERT INTO playout_lease_override"
                        + " (lease_id, override_key, asset_id, grant_id, priority, start_ms, end_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                leaseId, overrideKey, assetId, grantId, priority, startMs, endMs);
    }

    /** 租约分段快照，按分段顺序升序。 */
    public List<LeaseSegmentRow> findLeaseSegments(long leaseId) {
        return jdbc.query("SELECT id, lease_id, seq, segment_id, asset_id, grant_id, grant_revoked,"
                        + " start_ms, end_ms FROM playout_lease_segment"
                        + " WHERE lease_id = ? ORDER BY seq",
                LEASE_SEGMENT_MAPPER, leaseId);
    }

    /** 租约插播快照，按开始时间与键排序。 */
    public List<LeaseOverrideRow> findLeaseOverrides(long leaseId) {
        return jdbc.query("SELECT id, lease_id, override_key, asset_id, grant_id, priority,"
                        + " start_ms, end_ms FROM playout_lease_override"
                        + " WHERE lease_id = ? ORDER BY start_ms, override_key",
                LEASE_OVERRIDE_MAPPER, leaseId);
    }

    // ---------- 分段确认 ----------

    /** 按确认键查询并加行锁，用于幂等判定。 */
    public Optional<LeaseAckRow> findAckForUpdate(String ackKey) {
        return jdbc.query("SELECT id, ack_key, lease_id, lease_epoch, seq, segment_id, played_at_ms,"
                        + " created_at_ms FROM playout_lease_ack WHERE ack_key = ? FOR UPDATE",
                LEASE_ACK_MAPPER, ackKey).stream().findFirst();
    }

    /** 租约的确认列表，按分段顺序升序。 */
    public List<LeaseAckRow> findAcks(long leaseId) {
        return jdbc.query("SELECT id, ack_key, lease_id, lease_epoch, seq, segment_id, played_at_ms,"
                        + " created_at_ms FROM playout_lease_ack WHERE lease_id = ? ORDER BY seq",
                LEASE_ACK_MAPPER, leaseId);
    }

    /** 租约已确认分段数。 */
    public int countAcks(long leaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_lease_ack WHERE lease_id = ?", Integer.class, leaseId);
        return count == null ? 0 : count;
    }

    public void insertAck(String ackKey, long leaseId, long leaseEpoch, int seq, String segmentId,
                          long playedAtMs, long createdAtMs) {
        jdbc.update("INSERT INTO playout_lease_ack"
                        + " (ack_key, lease_id, lease_epoch, seq, segment_id, played_at_ms, created_at_ms)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                ackKey, leaseId, leaseEpoch, seq, segmentId, playedAtMs, createdAtMs);
    }

    // ---------- 发布版本引用 ----------

    /** 频道某业务日全部发布版本及引用它的未过期 ACTIVE 租约数，按发布版本升序。 */
    public List<PublicationReferenceRow> findPublicationReferences(String channelId,
                                                                   LocalDate businessDay,
                                                                   long nowMs) {
        return jdbc.query("SELECT p.id, p.published_version,"
                        + " (SELECT COUNT(*) FROM playout_lease l WHERE l.publication_id = p.id"
                        + "   AND l.status = 'ACTIVE' AND l.expires_at_ms > ?) AS active_lease_count"
                        + " FROM playout_publication p"
                        + " WHERE p.channel_id = ? AND p.business_day = ?"
                        + " ORDER BY p.published_version",
                PUBLICATION_REFERENCE_MAPPER, nowMs, channelId, Date.valueOf(businessDay));
    }
}
