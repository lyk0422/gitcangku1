package com.example.starter.water;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * 多来源水质混配数据访问层（JdbcTemplate，参数化 SQL）。
 * 体积为 DECIMAL(19,3)（立方米），水质指标与加权指标为 DECIMAL(9,4)（无量纲），
 * 时间一律为 UTC 纳秒时间戳。
 */
@Repository
public class BlendRepository {

    /** 水质来源配额账户行；reservedVolume 为未取消申请的预占体积合计。 */
    public record BlendSourceRow(long id, String sourceKey, String name, BigDecimal qualityIndex,
                                 BigDecimal totalQuota, BigDecimal reservedVolume,
                                 long createdNanos, long updatedNanos) {
    }

    /** 混配申请行；weightedIndex 为首次受理的加权指标快照，取消后不重算。 */
    public record BlendApplicationRow(long id, String requestId, String userId, String purpose,
                                      BigDecimal threshold, BigDecimal totalVolume, BigDecimal weightedIndex,
                                      String requester, String status, long createdNanos, long updatedNanos,
                                      Long cancelledNanos) {
    }

    /** 申请来源明细行；指标为受理当时快照，取消仅更新 reservedStatus。 */
    public record BlendSourceItemRow(long id, String requestId, String sourceKey, BigDecimal volume,
                                     BigDecimal qualityIndex, BigDecimal weightedIndex, String reservedStatus,
                                     long createdNanos) {
    }

    private static final RowMapper<BlendSourceRow> SOURCE_MAPPER = (rs, n) -> new BlendSourceRow(
            rs.getLong("id"), rs.getString("source_key"), rs.getString("name"),
            rs.getBigDecimal("quality_index"), rs.getBigDecimal("total_quota"),
            rs.getBigDecimal("reserved_volume"), rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String SOURCE_COLUMNS =
            "id, source_key, name, quality_index, total_quota, reserved_volume, created_nanos, updated_nanos";

    private static final RowMapper<BlendApplicationRow> APPLICATION_MAPPER = (rs, n) -> new BlendApplicationRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("user_id"), rs.getString("purpose"),
            rs.getBigDecimal("threshold"), rs.getBigDecimal("total_volume"), rs.getBigDecimal("weighted_index"),
            rs.getString("requester"), rs.getString("status"), rs.getLong("created_nanos"),
            rs.getLong("updated_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final String APPLICATION_COLUMNS =
            "id, request_id, user_id, purpose, threshold, total_volume, weighted_index, requester, status,"
                    + " created_nanos, updated_nanos, cancelled_nanos";

    private static final RowMapper<BlendSourceItemRow> ITEM_MAPPER = (rs, n) -> new BlendSourceItemRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("source_key"),
            rs.getBigDecimal("volume"), rs.getBigDecimal("quality_index"), rs.getBigDecimal("weighted_index"),
            rs.getString("reserved_status"), rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public BlendRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 注册水质来源配额账户并返回自增主键。 */
    public long insertSource(String sourceKey, String name, BigDecimal qualityIndex, BigDecimal totalQuota,
                             long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_source (source_key, name, quality_index, total_quota, reserved_volume,"
                            + " created_nanos, updated_nanos) VALUES (?, ?, ?, ?, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sourceKey);
            ps.setString(2, name);
            ps.setBigDecimal(3, qualityIndex);
            ps.setBigDecimal(4, totalQuota);
            ps.setLong(5, createdNanos);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询来源，不存在返回 null。 */
    public BlendSourceRow findSourceByKey(String sourceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + SOURCE_COLUMNS + " FROM blend_source WHERE source_key = ?",
                    SOURCE_MAPPER, sourceKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定来源行（FOR UPDATE），不存在返回 null。 */
    public BlendSourceRow lockSourceByKey(String sourceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + SOURCE_COLUMNS + " FROM blend_source WHERE source_key = ? FOR UPDATE",
                    SOURCE_MAPPER, sourceKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 在一条 SQL 内按 source_key 升序锁定多个来源行，避免多来源申请以不同顺序加锁造成死锁；
     * 返回行同样按 source_key 升序。调用前须自行去重。
     */
    public List<BlendSourceRow> lockSourcesByKeys(List<String> orderedSourceKeys) {
        String placeholders = String.join(",", java.util.Collections.nCopies(orderedSourceKeys.size(), "?"));
        return jdbc.query(
                "SELECT " + SOURCE_COLUMNS + " FROM blend_source WHERE source_key IN (" + placeholders + ")"
                        + " ORDER BY source_key FOR UPDATE",
                SOURCE_MAPPER, orderedSourceKeys.toArray());
    }

    /** 来源预占体积增加 delta（受理），并记录变更时间。 */
    public void addReservedVolume(long sourceId, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE blend_source SET reserved_volume = reserved_volume + ?, updated_nanos = ?"
                + " WHERE id = ?", delta, updatedNanos, sourceId);
    }

    /** 来源预占体积释放 delta（取消），并记录变更时间；不得为负由 CHECK 与事务保证。 */
    public void releaseReservedVolume(long sourceId, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE blend_source SET reserved_volume = reserved_volume - ?, updated_nanos = ?"
                + " WHERE id = ?", delta, updatedNanos, sourceId);
    }

    /** 插入混配申请（初始 RESERVED）并返回自增主键。 */
    public long insertApplication(String requestId, String userId, String purpose, BigDecimal threshold,
                                  BigDecimal totalVolume, BigDecimal weightedIndex, String requester,
                                  long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_application (request_id, user_id, purpose, threshold, total_volume,"
                            + " weighted_index, requester, status, created_nanos, updated_nanos, cancelled_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, userId);
            ps.setString(3, purpose);
            ps.setBigDecimal(4, threshold);
            ps.setBigDecimal(5, totalVolume);
            ps.setBigDecimal(6, weightedIndex);
            ps.setString(7, requester);
            ps.setLong(8, createdNanos);
            ps.setLong(9, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按 requestId 查询申请，不存在返回 null。 */
    public BlendApplicationRow findApplicationByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + APPLICATION_COLUMNS + " FROM blend_application WHERE request_id = ?",
                    APPLICATION_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 requestId 锁定申请行（FOR UPDATE），不存在返回 null。 */
    public BlendApplicationRow lockApplicationByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + APPLICATION_COLUMNS + " FROM blend_application WHERE request_id = ? FOR UPDATE",
                    APPLICATION_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 取消申请：状态置 CANCELLED 并记录取消与变更时间。 */
    public void markApplicationCancelled(long id, long cancelledNanos) {
        jdbc.update("UPDATE blend_application SET status = 'CANCELLED', updated_nanos = ?,"
                + " cancelled_nanos = ? WHERE id = ?", cancelledNanos, cancelledNanos, id);
    }

    /** 插入一条不可变来源明细（初始 RESERVED）。 */
    public void insertItem(String requestId, String sourceKey, BigDecimal volume, BigDecimal qualityIndex,
                           BigDecimal weightedIndex, long createdNanos) {
        jdbc.update("INSERT INTO blend_source_item (request_id, source_key, volume, quality_index,"
                + " weighted_index, reserved_status, created_nanos)"
                + " VALUES (?, ?, ?, ?, ?, 'RESERVED', ?)",
                requestId, sourceKey, volume, qualityIndex, weightedIndex, createdNanos);
    }

    /** 申请全部来源明细，按主键升序。 */
    public List<BlendSourceItemRow> listItems(String requestId) {
        return jdbc.query(
                "SELECT id, request_id, source_key, volume, quality_index, weighted_index, reserved_status,"
                        + " created_nanos FROM blend_source_item WHERE request_id = ? ORDER BY id",
                ITEM_MAPPER, requestId);
    }

    /** 将申请下仍为 RESERVED 的明细置为 RELEASED，返回受影响行数。 */
    public int releaseItems(String requestId) {
        return jdbc.update("UPDATE blend_source_item SET reserved_status = 'RELEASED'"
                + " WHERE request_id = ? AND reserved_status = 'RESERVED'", requestId);
    }
}
