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
 * 灌区配水数据访问层（JdbcTemplate，参数化 SQL）。
 * 时间一律为 UTC 纳秒时间戳；水量为 DECIMAL(19,3)。
 */
@Repository
public class WaterRepository {

    /** 供水窗口行。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, long createdNanos) {
    }

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。
     * salinityLimit 为申请声明的盐度上限（mg/L），null 表示不限制；version 为乐观版本，每次扣减自增。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, BigDecimal salinityLimit, long version,
                                String requester, String status,
                                long createdNanos, long updatedNanos) {
    }

    /** 转让流水行，创建后不可变。 */
    public record TransferRow(long id, String transferKey, long windowId, String sourceAllocationKey,
                              String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 限供行。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 水源行：可用水量与盐度（mg/L），version 为盐度修改乐观版本。 */
    public record SourceRow(long id, String sourceKey, BigDecimal availableAmount, BigDecimal salinity,
                            long version, long createdNanos, long updatedNanos) {
    }

    /** 掺配核销快照行，创建后不可变。 */
    public record BlendSnapshotRow(long id, String blendKey, String allocationKey, String actor,
                                   BigDecimal totalAmount, BigDecimal weightedSalinity,
                                   BigDecimal salinityLimit, long allocationVersion, long createdNanos) {
    }

    /** 掺配快照明细行，冻结取水量与核销时水源盐度。 */
    public record BlendItemRow(long id, long snapshotId, String sourceKey, BigDecimal amount,
                               BigDecimal salinity, int ordinal) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getBigDecimal("salinity_limit"), rs.getLong("version"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, salinity_limit, version,"
                    + " requester, status, created_nanos, updated_nanos";

    private static final RowMapper<TransferRow> TRANSFER_MAPPER = (rs, n) -> new TransferRow(
            rs.getLong("id"), rs.getString("transfer_key"), rs.getLong("window_id"),
            rs.getString("source_allocation_key"), rs.getString("target_allocation_key"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<CurtailmentRow> CURTAILMENT_MAPPER = (rs, n) -> new CurtailmentRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getBigDecimal("volume"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_key"), rs.getString("operation"), rs.getString("params"),
            rs.getString("response"), rs.getLong("created_nanos"));

    private static final String SOURCE_SELECT =
            "SELECT id, source_key, available_amount, salinity, version, created_nanos, updated_nanos";

    private static final RowMapper<SourceRow> SOURCE_MAPPER = (rs, n) -> new SourceRow(
            rs.getLong("id"), rs.getString("source_key"), rs.getBigDecimal("available_amount"),
            rs.getBigDecimal("salinity"), rs.getLong("version"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final RowMapper<BlendSnapshotRow> BLEND_SNAPSHOT_MAPPER = (rs, n) -> new BlendSnapshotRow(
            rs.getLong("id"), rs.getString("blend_key"), rs.getString("allocation_key"),
            rs.getString("actor"), rs.getBigDecimal("total_amount"), rs.getBigDecimal("weighted_salinity"),
            rs.getBigDecimal("salinity_limit"), rs.getLong("allocation_version"),
            rs.getLong("created_nanos"));

    private static final RowMapper<BlendItemRow> BLEND_ITEM_MAPPER = (rs, n) -> new BlendItemRow(
            rs.getLong("id"), rs.getLong("snapshot_id"), rs.getString("source_key"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("salinity"), rs.getInt("ordinal"));

    private final JdbcTemplate jdbc;

    public WaterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入窗口并返回自增主键。 */
    public long insertWindow(String windowKey, String channelId, long startNanos, long endNanos,
                             BigDecimal plannedVolume, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO supply_window (window_key, channel_id, start_nanos, end_nanos, planned_volume, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, windowKey);
            ps.setString(2, channelId);
            ps.setLong(3, startNanos);
            ps.setLong(4, endNanos);
            ps.setBigDecimal(5, plannedVolume);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询窗口，不存在返回 null。 */
    public WindowRow findWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, created_nanos"
                            + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准与限供。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, created_nanos"
                            + " FROM supply_window WHERE id = ? FOR UPDATE", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 判断同渠道是否存在与 [startNanos, endNanos) 重叠的窗口（相邻合法）。 */
    public boolean existsOverlappingWindow(String channelId, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supply_window WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelId, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 插入申请（初始 REQUESTED）并返回主键；salinityLimit 为 null 表示不声明盐度上限。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 BigDecimal salinityLimit, String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount,"
                            + " salinity_limit, version, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 0, ?, 'REQUESTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setBigDecimal(5, salinityLimit);
            ps.setString(6, requester);
            ps.setLong(7, nowNanos);
            ps.setLong(8, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询申请，不存在返回 null。 */
    public AllocationRow findAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(ALLOCATION_SELECT + " FROM allocation WHERE allocation_key = ?",
                    ALLOCATION_MAPPER, allocationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定申请行（FOR UPDATE），用于串行化转让的源/目标争用，不存在返回 null。 */
    public AllocationRow lockAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(
                    ALLOCATION_SELECT + " FROM allocation WHERE allocation_key = ? FOR UPDATE",
                    ALLOCATION_MAPPER, allocationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 更新申请状态与变更时间，并同步持有额度：批准时持有额度等于原申请水量，取消时归零，
     * REQUESTED 保持当前持有额度不变；每次状态变更自增乐观版本。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?, version = version + 1,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END WHERE id = ?",
                status, updatedNanos, status, status, id);
    }

    /**
     * 转让/掺配核销扣减申请持有额度并自增乐观版本（不得为负由事务内校验与 CHECK 约束保证）。
     */
    public void decrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?, version = version + 1, updated_nanos = ?"
                + " WHERE id = ?", delta, updatedNanos, id);
    }

    /** 窗口当前所有 APPROVED 申请的当前持有额度之和（BigDecimal 精确求和），无则 0。 */
    public BigDecimal sumApprovedAmount(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(held_amount), 0) FROM allocation WHERE window_id = ? AND status = 'APPROVED'",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 插入限供（ACTIVE）并返回主键。 */
    public long insertCurtailment(long windowId, BigDecimal volume, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO curtailment (window_id, volume, status, created_nanos, cancelled_nanos)"
                            + " VALUES (?, ?, 'ACTIVE', ?, NULL)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, windowId);
            ps.setBigDecimal(2, volume);
            ps.setLong(3, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 查询窗口当前生效限供，无则 null。 */
    public CurtailmentRow findActiveCurtailment(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, volume, status, created_nanos, cancelled_nanos"
                            + " FROM curtailment WHERE window_id = ? AND status = 'ACTIVE'", CURTAILMENT_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 取消限供：置为 CANCELLED 并记录取消时间。 */
    public void cancelCurtailment(long id, long cancelledNanos) {
        jdbc.update("UPDATE curtailment SET status = 'CANCELLED', cancelled_nanos = ? WHERE id = ?",
                cancelledNanos, id);
    }

    /** 窗口全部申请，按主键升序。 */
    public List<AllocationRow> listAllocations(long windowId) {
        return jdbc.query(
                ALLOCATION_SELECT + " FROM allocation WHERE window_id = ? ORDER BY id",
                ALLOCATION_MAPPER, windowId);
    }

    /** 插入不可变转让流水并返回主键。 */
    public long insertTransfer(String transferKey, long windowId, String sourceAllocationKey,
                               String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO transfer (transfer_key, window_id, source_allocation_key,"
                            + " target_allocation_key, amount, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, transferKey);
            ps.setLong(2, windowId);
            ps.setString(3, sourceAllocationKey);
            ps.setString(4, targetAllocationKey);
            ps.setBigDecimal(5, amount);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询转让流水，不存在返回 null。 */
    public TransferRow findTransferByKey(String transferKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, transfer_key, window_id, source_allocation_key, target_allocation_key,"
                            + " amount, actor, created_nanos FROM transfer WHERE transfer_key = ?",
                    TRANSFER_MAPPER, transferKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部转让流水（不可变），按主键升序。 */
    public List<TransferRow> listTransfers(long windowId) {
        return jdbc.query(
                "SELECT id, transfer_key, window_id, source_allocation_key, target_allocation_key,"
                        + " amount, actor, created_nanos FROM transfer WHERE window_id = ? ORDER BY id",
                TRANSFER_MAPPER, windowId);
    }

    /** 窗口全部限供记录（含已取消），按主键升序。 */
    public List<CurtailmentRow> listCurtailments(long windowId) {
        return jdbc.query(
                "SELECT id, window_id, volume, status, created_nanos, cancelled_nanos"
                        + " FROM curtailment WHERE window_id = ? ORDER BY id", CURTAILMENT_MAPPER, windowId);
    }

    /** 按幂等键查询命令，不存在返回 null。 */
    public CommandRow findCommand(String commandKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT command_key, operation, params, response, created_nanos FROM command_log WHERE command_key = ?",
                    COMMAND_MAPPER, commandKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 先占位插入命令（响应为空），同事务内业务成功后写回响应。 */
    public void insertCommand(String commandKey, String operation, String params, long createdNanos) {
        jdbc.update("INSERT INTO command_log (command_key, operation, params, response, created_nanos)"
                + " VALUES (?, ?, ?, NULL, ?)", commandKey, operation, params, createdNanos);
    }

    /** 写回命令首次成功响应。 */
    public void updateCommandResponse(String commandKey, String response) {
        jdbc.update("UPDATE command_log SET response = ? WHERE command_key = ?", response, commandKey);
    }

    // ------------------------------------------------------------------
    // 水源
    // ------------------------------------------------------------------

    /** 注册水源（version 初始为 0）并返回主键。 */
    public long insertSource(String sourceKey, BigDecimal availableAmount, BigDecimal salinity,
                             long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO water_source (source_key, available_amount, salinity, version,"
                            + " created_nanos, updated_nanos) VALUES (?, ?, ?, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sourceKey);
            ps.setBigDecimal(2, availableAmount);
            ps.setBigDecimal(3, salinity);
            ps.setLong(4, nowNanos);
            ps.setLong(5, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询水源，不存在返回 null。 */
    public SourceRow findSourceByKey(String sourceKey) {
        try {
            return jdbc.queryForObject(SOURCE_SELECT + " FROM water_source WHERE source_key = ?",
                    SOURCE_MAPPER, sourceKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定水源行（FOR UPDATE），用于掺配核销串行扣减，不存在返回 null。 */
    public SourceRow lockSourceByKey(String sourceKey) {
        try {
            return jdbc.queryForObject(SOURCE_SELECT + " FROM water_source WHERE source_key = ? FOR UPDATE",
                    SOURCE_MAPPER, sourceKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按规范化顺序（source_key 升序）锁定多个水源行。 */
    public List<SourceRow> lockSourcesByKeys(List<String> sourceKeys) {
        if (sourceKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(sourceKeys.size(), "?"));
        return jdbc.query(
                SOURCE_SELECT + " FROM water_source WHERE source_key IN (" + placeholders
                        + ") ORDER BY source_key ASC FOR UPDATE",
                SOURCE_MAPPER, sourceKeys.toArray());
    }

    /** 掺配核销扣减水源可用量（不得为负由事务内校验与 CHECK 约束保证）。 */
    public void decrementSourceAmount(long id, BigDecimal delta) {
        jdbc.update("UPDATE water_source SET available_amount = available_amount - ? WHERE id = ?",
                delta, id);
    }

    /**
     * 携带期望版本修改水源盐度：仅当 version 等于 expectedVersion 时更新盐度并自增版本，返回受影响行数。
     * 版本不符（并发已修改）返回 0，由上层映射为 409。
     */
    public int updateSourceSalinityIfVersion(String sourceKey, BigDecimal salinity, long expectedVersion,
                                             long updatedNanos) {
        return jdbc.update("UPDATE water_source SET salinity = ?, version = version + 1, updated_nanos = ?"
                        + " WHERE source_key = ? AND version = ?",
                salinity, updatedNanos, sourceKey, expectedVersion);
    }

    // ------------------------------------------------------------------
    // 掺配核销快照
    // ------------------------------------------------------------------

    /** 插入不可变掺配核销快照头并返回主键。 */
    public long insertBlendSnapshot(String blendKey, String allocationKey, String actor,
                                    BigDecimal totalAmount, BigDecimal weightedSalinity,
                                    BigDecimal salinityLimit, long allocationVersion, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_snapshot (blend_key, allocation_key, actor, total_amount,"
                            + " weighted_salinity, salinity_limit, allocation_version, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, blendKey);
            ps.setString(2, allocationKey);
            ps.setString(3, actor);
            ps.setBigDecimal(4, totalAmount);
            ps.setBigDecimal(5, weightedSalinity);
            ps.setBigDecimal(6, salinityLimit);
            ps.setLong(7, allocationVersion);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 插入快照明细（冻结核销时盐度），与快照头同事务写入。 */
    public void insertBlendItem(long snapshotId, String sourceKey, BigDecimal amount, BigDecimal salinity,
                                int ordinal) {
        jdbc.update("INSERT INTO blend_snapshot_item (snapshot_id, source_key, amount, salinity, ordinal)"
                + " VALUES (?, ?, ?, ?, ?)", snapshotId, sourceKey, amount, salinity, ordinal);
    }

    /** 按掺配业务键查询快照头，不存在返回 null。 */
    public BlendSnapshotRow findBlendSnapshotByKey(String blendKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, blend_key, allocation_key, actor, total_amount, weighted_salinity,"
                            + " salinity_limit, allocation_version, created_nanos"
                            + " FROM blend_snapshot WHERE blend_key = ?",
                    BLEND_SNAPSHOT_MAPPER, blendKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 查询快照全部明细，按规范化序号升序。 */
    public List<BlendItemRow> listBlendItems(long snapshotId) {
        return jdbc.query(
                "SELECT id, snapshot_id, source_key, amount, salinity, ordinal"
                        + " FROM blend_snapshot_item WHERE snapshot_id = ? ORDER BY ordinal ASC",
                BLEND_ITEM_MAPPER, snapshotId);
    }

    /** 查询申请全部掺配快照，按发生顺序（主键升序）。 */
    public List<BlendSnapshotRow> listBlendSnapshots(String allocationKey) {
        return jdbc.query(
                "SELECT id, blend_key, allocation_key, actor, total_amount, weighted_salinity,"
                        + " salinity_limit, allocation_version, created_nanos"
                        + " FROM blend_snapshot WHERE allocation_key = ? ORDER BY id ASC",
                BLEND_SNAPSHOT_MAPPER, allocationKey);
    }
}
