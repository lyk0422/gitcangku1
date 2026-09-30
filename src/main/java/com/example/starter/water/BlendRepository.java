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
 * 时间一律为 UTC 纳秒时间戳；体积 DECIMAL(19,3) 单位立方米；水质指标 DECIMAL(19,6) 无量纲，可空。
 */
@Repository
public class BlendRepository {

    /** 水质来源行；qualityIndex 为 null 表示指标缺失。 */
    public record BlendSourceRow(long id, String sourceKey, String name, BigDecimal quotaVolume,
                                 BigDecimal qualityIndex, long createdNanos) {
    }

    /** 混配申请行；weightedIndex 为受理时的加权指标快照，取消后不重算。 */
    public record BlendApplicationRow(long id, String requestId, String userId, String purpose,
                                      BigDecimal qualityThreshold, BigDecimal totalVolume,
                                      BigDecimal weightedIndex, String requester, String status,
                                      long createdNanos, Long cancelledNanos) {
    }

    /** 申请来源明细行（快照，不可变）。 */
    public record BlendItemRow(long id, long applicationId, String sourceKey, BigDecimal volume,
                               BigDecimal qualityIndex, int seqNo, long createdNanos) {
    }

    private static final String SOURCE_COLUMNS =
            "id, source_key, name, quota_volume, quality_index, created_nanos";

    private static final String APPLICATION_COLUMNS =
            "id, request_id, user_id, purpose, quality_threshold, total_volume, weighted_index,"
                    + " requester, status, created_nanos, cancelled_nanos";

    private static final String ITEM_COLUMNS =
            "id, application_id, source_key, volume, quality_index, seq_no, created_nanos";

    private static final RowMapper<BlendSourceRow> SOURCE_MAPPER = (rs, n) -> new BlendSourceRow(
            rs.getLong("id"), rs.getString("source_key"), rs.getString("name"),
            rs.getBigDecimal("quota_volume"), rs.getBigDecimal("quality_index"),
            rs.getLong("created_nanos"));

    private static final RowMapper<BlendApplicationRow> APPLICATION_MAPPER = (rs, n) ->
            new BlendApplicationRow(
                    rs.getLong("id"), rs.getString("request_id"), rs.getString("user_id"),
                    rs.getString("purpose"), rs.getBigDecimal("quality_threshold"),
                    rs.getBigDecimal("total_volume"), rs.getBigDecimal("weighted_index"),
                    rs.getString("requester"), rs.getString("status"),
                    rs.getLong("created_nanos"),
                    rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<BlendItemRow> ITEM_MAPPER = (rs, n) -> new BlendItemRow(
            rs.getLong("id"), rs.getLong("application_id"), rs.getString("source_key"),
            rs.getBigDecimal("volume"), rs.getBigDecimal("quality_index"),
            rs.getInt("seq_no"), rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public BlendRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入水质来源并返回自增主键；qualityIndex 为 null 时写入 NULL。 */
    public long insertSource(String sourceKey, String name, BigDecimal quotaVolume,
                             BigDecimal qualityIndex, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_source (source_key, name, quota_volume, quality_index, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sourceKey);
            ps.setString(2, name);
            ps.setBigDecimal(3, quotaVolume);
            ps.setBigDecimal(4, qualityIndex);
            ps.setLong(5, createdNanos);
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

    /** 按业务键锁定来源行（FOR UPDATE），用于串行化多申请对同一来源配额的争用，不存在返回 null。 */
    public BlendSourceRow lockSourceByKey(String sourceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + SOURCE_COLUMNS + " FROM blend_source WHERE source_key = ? FOR UPDATE",
                    SOURCE_MAPPER, sourceKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入混配申请（初始 RESERVED）并返回主键。 */
    public long insertApplication(String requestId, String userId, String purpose,
                                  BigDecimal qualityThreshold, BigDecimal totalVolume,
                                  BigDecimal weightedIndex, String requester, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_application (request_id, user_id, purpose, quality_threshold,"
                            + " total_volume, weighted_index, requester, status, created_nanos, cancelled_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, userId);
            ps.setString(3, purpose);
            ps.setBigDecimal(4, qualityThreshold);
            ps.setBigDecimal(5, totalVolume);
            ps.setBigDecimal(6, weightedIndex);
            ps.setString(7, requester);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询混配申请，不存在返回 null。 */
    public BlendApplicationRow findApplicationById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + APPLICATION_COLUMNS + " FROM blend_application WHERE id = ?",
                    APPLICATION_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 requestId 查询混配申请，不存在返回 null。 */
    public BlendApplicationRow findApplicationByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + APPLICATION_COLUMNS + " FROM blend_application WHERE request_id = ?",
                    APPLICATION_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 requestId 锁定申请行（FOR UPDATE），用于串行化并发取消，不存在返回 null。 */
    public BlendApplicationRow lockApplicationByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + APPLICATION_COLUMNS + " FROM blend_application WHERE request_id = ? FOR UPDATE",
                    APPLICATION_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 取消申请：置为 CANCELLED 并记录取消时间；其全部明细不再计入来源预占汇总。 */
    public void cancelApplication(long id, long cancelledNanos) {
        jdbc.update("UPDATE blend_application SET status = 'CANCELLED', cancelled_nanos = ? WHERE id = ?",
                cancelledNanos, id);
    }

    /** 插入一条申请来源明细（提交时快照）并返回主键。 */
    public long insertItem(long applicationId, String sourceKey, BigDecimal volume,
                           BigDecimal qualityIndex, int seqNo, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_source_item (application_id, source_key, volume, quality_index,"
                            + " seq_no, created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, applicationId);
            ps.setString(2, sourceKey);
            ps.setBigDecimal(3, volume);
            ps.setBigDecimal(4, qualityIndex);
            ps.setInt(5, seqNo);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 申请全部来源明细，按提交顺序（seq_no 升序）。 */
    public List<BlendItemRow> listItemsByApplicationId(long applicationId) {
        return jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM blend_source_item WHERE application_id = ? ORDER BY seq_no",
                ITEM_MAPPER, applicationId);
    }

    /**
     * 来源当前预占体积之和：仅汇总未取消（RESERVED）申请的明细，无则 0。
     * 调用方须已在当前事务内锁定对应来源行，与并发提交/取消按提交顺序串行裁决。
     */
    public BigDecimal sumReservedVolumeBySource(String sourceKey) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(i.volume), 0) FROM blend_source_item i"
                        + " JOIN blend_application a ON a.id = i.application_id"
                        + " WHERE i.source_key = ? AND a.status = 'RESERVED'",
                BigDecimal.class, sourceKey);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
