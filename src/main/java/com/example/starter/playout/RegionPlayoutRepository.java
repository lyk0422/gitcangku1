package com.example.starter.playout;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 区域切换数据访问层。所有时间列均为 UTC 纪元毫秒（BIGINT）。
 * 占位判重以区域为边界、区间左闭右开；区域行锁用于串行化同区域并发提交。
 */
@Repository
public class RegionPlayoutRepository {

    private final JdbcTemplate jdbc;

    public RegionPlayoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 区域行。 */
    public record RegionRow(String id, long createdAtMs) {
    }

    /** 区域授权行；revoked 为终态撤销标记。 */
    public record RegionGrantRow(long id, String regionId, String assetId,
                                 long validFromMs, long validToMs, boolean revoked) {
    }

    /** 区域占位行；grantValidFromMs/grantValidToMs 为提交时固化的授权版本窗口。 */
    public record RegionAllocationRow(long id, String requestId, String regionId, String assetId,
                                      long grantId, long grantValidFromMs, long grantValidToMs,
                                      long startMs, long endMs, long createdAtMs) {
    }

    private static final RowMapper<RegionRow> REGION_MAPPER = (rs, n) ->
            new RegionRow(rs.getString("id"), rs.getLong("created_at_ms"));

    private static final RowMapper<RegionGrantRow> REGION_GRANT_MAPPER = (rs, n) ->
            new RegionGrantRow(rs.getLong("id"), rs.getString("region_id"), rs.getString("asset_id"),
                    rs.getLong("valid_from_ms"), rs.getLong("valid_to_ms"), rs.getBoolean("revoked"));

    private static final RowMapper<RegionAllocationRow> REGION_ALLOCATION_MAPPER = (rs, n) ->
            new RegionAllocationRow(rs.getLong("id"), rs.getString("request_id"),
                    rs.getString("region_id"), rs.getString("asset_id"), rs.getLong("grant_id"),
                    rs.getLong("grant_valid_from_ms"), rs.getLong("grant_valid_to_ms"),
                    rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getLong("created_at_ms"));

    // ---------- 区域 ----------

    public void insertRegion(String id, long createdAtMs) {
        jdbc.update("INSERT INTO playout_region (id, created_at_ms) VALUES (?, ?)", id, createdAtMs);
    }

    public Optional<RegionRow> findRegion(String id) {
        return jdbc.query("SELECT id, created_at_ms FROM playout_region WHERE id = ?",
                REGION_MAPPER, id).stream().findFirst();
    }

    /** 锁定区域行，串行化同区域的并发编排提交（H2 行锁，MySQL 下同样安全）。 */
    public void lockRegionForUpdate(String regionId) {
        jdbc.queryForList("SELECT id FROM playout_region WHERE id = ? FOR UPDATE", regionId);
    }

    // ---------- 区域授权 ----------

    public long insertRegionGrant(String regionId, String assetId, long validFromMs, long validToMs,
                                  long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_region_grant"
                            + " (region_id, asset_id, valid_from_ms, valid_to_ms, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, regionId);
            ps.setString(2, assetId);
            ps.setLong(3, validFromMs);
            ps.setLong(4, validToMs);
            ps.setLong(5, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RegionGrantRow> findRegionGrant(long id) {
        return jdbc.query("SELECT id, region_id, asset_id, valid_from_ms, valid_to_ms, revoked"
                + " FROM playout_region_grant WHERE id = ?", REGION_GRANT_MAPPER, id)
                .stream().findFirst();
    }

    /** 按 ID 查询授权并加行锁，用于编排提交与授权撤销按提交顺序串行化。 */
    public Optional<RegionGrantRow> findRegionGrantForUpdate(long id) {
        return jdbc.query("SELECT id, region_id, asset_id, valid_from_ms, valid_to_ms, revoked"
                + " FROM playout_region_grant WHERE id = ? FOR UPDATE", REGION_GRANT_MAPPER, id)
                .stream().findFirst();
    }

    /** 撤销区域授权；返回受影响行数，0 表示不存在或已撤销。 */
    public int revokeRegionGrant(long id, String revokeRequestId, long revokedAtMs) {
        return jdbc.update("UPDATE playout_region_grant SET revoked = 1, revoke_request_id = ?,"
                + " revoked_at_ms = ? WHERE id = ? AND revoked = 0",
                revokeRequestId, revokedAtMs, id);
    }

    /**
     * 查找完整覆盖 [startMs, endMs) 的区域授权（区间左闭右开），不过滤撤销状态并加行锁，
     * 由业务层优先选取未撤销授权；行锁用于编排提交与授权撤销按提交顺序串行化。
     */
    public List<RegionGrantRow> findCoveringGrantsForUpdate(String regionId, String assetId,
                                                            long startMs, long endMs) {
        return jdbc.query("SELECT id, region_id, asset_id, valid_from_ms, valid_to_ms, revoked"
                        + " FROM playout_region_grant"
                        + " WHERE region_id = ? AND asset_id = ?"
                        + " AND valid_from_ms <= ? AND valid_to_ms >= ?"
                        + " ORDER BY revoked, id FOR UPDATE",
                REGION_GRANT_MAPPER, regionId, assetId, startMs, endMs);
    }

    // ---------- 区域占位 ----------

    public long insertAllocation(String requestId, String regionId, String assetId, long grantId,
                                 long grantValidFromMs, long grantValidToMs,
                                 long startMs, long endMs, long createdAtMs) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playout_region_allocation"
                            + " (request_id, region_id, asset_id, grant_id, grant_valid_from_ms,"
                            + "  grant_valid_to_ms, start_ms, end_ms, created_at_ms)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, regionId);
            ps.setString(3, assetId);
            ps.setLong(4, grantId);
            ps.setLong(5, grantValidFromMs);
            ps.setLong(6, grantValidToMs);
            ps.setLong(7, startMs);
            ps.setLong(8, endMs);
            ps.setLong(9, createdAtMs);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 同区域与 [startMs, endMs) 相交的占位（左闭右开：start == end 的相邻端点不相交），加行锁。 */
    public List<RegionAllocationRow> findOverlappingForUpdate(String regionId, long startMs, long endMs) {
        return jdbc.query("SELECT id, request_id, region_id, asset_id, grant_id, grant_valid_from_ms,"
                        + " grant_valid_to_ms, start_ms, end_ms, created_at_ms"
                        + " FROM playout_region_allocation"
                        + " WHERE region_id = ? AND start_ms < ? AND end_ms > ?"
                        + " ORDER BY start_ms, id FOR UPDATE",
                REGION_ALLOCATION_MAPPER, regionId, endMs, startMs);
    }

    /** 编排员视角：全部区域占位，按区域与开始时间排序。 */
    public List<RegionAllocationRow> findAllAllocations() {
        return jdbc.query("SELECT id, request_id, region_id, asset_id, grant_id, grant_valid_from_ms,"
                        + " grant_valid_to_ms, start_ms, end_ms, created_at_ms"
                        + " FROM playout_region_allocation ORDER BY region_id, start_ms, id",
                REGION_ALLOCATION_MAPPER);
    }

    /** 区域播控方视角：只读本区域占位，按开始时间排序。 */
    public List<RegionAllocationRow> findAllocationsByRegion(String regionId) {
        return jdbc.query("SELECT id, request_id, region_id, asset_id, grant_id, grant_valid_from_ms,"
                        + " grant_valid_to_ms, start_ms, end_ms, created_at_ms"
                        + " FROM playout_region_allocation WHERE region_id = ? ORDER BY start_ms, id",
                REGION_ALLOCATION_MAPPER, regionId);
    }

    /** 按幂等请求 ID 查询一次提交的全部占位，按 id 排序。 */
    public List<RegionAllocationRow> findAllocationsByRequest(String requestId) {
        return jdbc.query("SELECT id, request_id, region_id, asset_id, grant_id, grant_valid_from_ms,"
                        + " grant_valid_to_ms, start_ms, end_ms, created_at_ms"
                        + " FROM playout_region_allocation WHERE request_id = ? ORDER BY id",
                REGION_ALLOCATION_MAPPER, requestId);
    }

    /** 判断是否为唯一键冲突（区域 ID、requestId 并发场景）。 */
    public static boolean isDuplicateKey(RuntimeException ex) {
        return ex instanceof DuplicateKeyException;
    }
}
