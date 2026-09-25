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

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度（申请剩余额度）。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, BigDecimal maxSalinityMgL, long version,
                                String requester, String status, long createdNanos, long updatedNanos) {
    }

    /** 转让流水行，创建后不可变。 */
    public record TransferRow(long id, String transferKey, long windowId, String sourceAllocationKey,
                              String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 限供行。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    /** 水源行；version 为乐观版本号，盐度修改须携带 expectedVersion。 */
    public record SourceRow(long id, String sourceId, BigDecimal availableAmount, BigDecimal salinityMgL,
                            long version, long createdNanos, long updatedNanos) {
    }

    /** 掺配快照行，创建后不可变；saltTotal 为各水源取水量 x 盐度之和的精确值。 */
    public record BlendSnapshotRow(long id, String blendKey, String allocationKey, long allocationVersion,
                                   String operator, BigDecimal settleAmount, BigDecimal saltTotal,
                                   BigDecimal weightedSalinityMgL, long createdNanos) {
    }

    /** 掺配快照水源明细行，冻结核销时刻的取水量、盐度与水源版本，创建后不可变。 */
    public record BlendLineRow(long id, long snapshotId, String sourceId, long sourceVersion,
                               BigDecimal amount, BigDecimal salinityMgL) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getBigDecimal("max_salinity_mg_l"), rs.getLong("version"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, max_salinity_mg_l, version,"
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
            "SELECT id, source_id, available_amount, salinity_mg_l, version, created_nanos, updated_nanos";

    private static final RowMapper<SourceRow> SOURCE_MAPPER = (rs, n) -> new SourceRow(
            rs.getLong("id"), rs.getString("source_id"), rs.getBigDecimal("available_amount"),
            rs.getBigDecimal("salinity_mg_l"), rs.getLong("version"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String SNAPSHOT_SELECT =
            "SELECT id, blend_key, allocation_key, allocation_version, operator, settle_amount, salt_total,"
                    + " weighted_salinity_mg_l, created_nanos";

    private static final RowMapper<BlendSnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new BlendSnapshotRow(
            rs.getLong("id"), rs.getString("blend_key"), rs.getString("allocation_key"),
            rs.getLong("allocation_version"), rs.getString("operator"), rs.getBigDecimal("settle_amount"),
            rs.getBigDecimal("salt_total"), rs.getBigDecimal("weighted_salinity_mg_l"),
            rs.getLong("created_nanos"));

    private static final RowMapper<BlendLineRow> BLEND_LINE_MAPPER = (rs, n) -> new BlendLineRow(
            rs.getLong("id"), rs.getLong("snapshot_id"), rs.getString("source_id"),
            rs.getLong("source_version"), rs.getBigDecimal("amount"), rs.getBigDecimal("salinity_mg_l"));

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

    /** 插入申请（初始 REQUESTED）并返回主键；maxSalinityMgL 为 null 表示未声明盐度上限。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 BigDecimal maxSalinityMgL, String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount,"
                            + " max_salinity_mg_l, version, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 0, ?, 'REQUESTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setBigDecimal(5, maxSalinityMgL);
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
     * REQUESTED 保持当前持有额度不变；每次变更版本号加 1。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?, version = version + 1,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END WHERE id = ?",
                status, updatedNanos, status, status, id);
    }

    /** 转让扣减源持有额度（不得为负由事务内校验保证），记录变更时间并递增版本号。 */
    public void decrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?, updated_nanos = ?,"
                + " version = version + 1 WHERE id = ?",
                delta, updatedNanos, id);
    }

    /**
     * 掺配核销扣减申请剩余额度（条件更新，持有额度不足时影响行数为 0 由调用方回滚），
     * 记录变更时间并递增版本号。
     *
     * @return 实际更新行数，0 表示剩余额度不足
     */
    public int deductAllocationHeld(long id, BigDecimal delta, long updatedNanos) {
        return jdbc.update("UPDATE allocation SET held_amount = held_amount - ?, updated_nanos = ?,"
                + " version = version + 1 WHERE id = ? AND held_amount >= ?",
                delta, updatedNanos, id, delta);
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
    // 水源与掺配快照
    // ------------------------------------------------------------------

    /** 插入水源（版本号从 0 开始）并返回主键。 */
    public long insertSource(String sourceId, BigDecimal availableAmount, BigDecimal salinityMgL,
                             long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO water_source (source_id, available_amount, salinity_mg_l, version,"
                            + " created_nanos, updated_nanos) VALUES (?, ?, ?, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sourceId);
            ps.setBigDecimal(2, availableAmount);
            ps.setBigDecimal(3, salinityMgL);
            ps.setLong(4, nowNanos);
            ps.setLong(5, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询水源，不存在返回 null。 */
    public SourceRow findSourceById(String sourceId) {
        try {
            return jdbc.queryForObject(SOURCE_SELECT + " FROM water_source WHERE source_id = ?",
                    SOURCE_MAPPER, sourceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定水源行（FOR UPDATE），用于串行化盐度修改与掺配核销，不存在返回 null。 */
    public SourceRow lockSourceById(String sourceId) {
        try {
            return jdbc.queryForObject(SOURCE_SELECT + " FROM water_source WHERE source_id = ? FOR UPDATE",
                    SOURCE_MAPPER, sourceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 修改水源盐度并递增版本号（调用方已在行锁内校验 expectedVersion）。 */
    public void updateSourceSalinity(long id, BigDecimal salinityMgL, long updatedNanos) {
        jdbc.update("UPDATE water_source SET salinity_mg_l = ?, version = version + 1, updated_nanos = ?"
                + " WHERE id = ?", salinityMgL, updatedNanos, id);
    }

    /**
     * 掺配核销扣减水源可用量（条件更新，可用量不足时影响行数为 0 由调用方回滚整单）。
     *
     * @return 实际更新行数，0 表示可用量不足
     */
    public int deductSourceAvailable(long id, BigDecimal delta, long updatedNanos) {
        return jdbc.update("UPDATE water_source SET available_amount = available_amount - ?,"
                + " updated_nanos = ? WHERE id = ? AND available_amount >= ?",
                delta, updatedNanos, id, delta);
    }

    /** 插入不可变掺配快照并返回主键。 */
    public long insertBlendSnapshot(String blendKey, String allocationKey, long allocationVersion,
                                    String operator, BigDecimal settleAmount, BigDecimal saltTotal,
                                    BigDecimal weightedSalinityMgL, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blend_snapshot (blend_key, allocation_key, allocation_version, operator,"
                            + " settle_amount, salt_total, weighted_salinity_mg_l, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, blendKey);
            ps.setString(2, allocationKey);
            ps.setLong(3, allocationVersion);
            ps.setString(4, operator);
            ps.setBigDecimal(5, settleAmount);
            ps.setBigDecimal(6, saltTotal);
            ps.setBigDecimal(7, weightedSalinityMgL);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 插入不可变掺配快照水源明细。 */
    public void insertBlendLine(long snapshotId, String sourceId, long sourceVersion, BigDecimal amount,
                                BigDecimal salinityMgL) {
        jdbc.update("INSERT INTO blend_snapshot_line (snapshot_id, source_id, source_version, amount,"
                + " salinity_mg_l) VALUES (?, ?, ?, ?, ?)",
                snapshotId, sourceId, sourceVersion, amount, salinityMgL);
    }

    /** 按业务键查询掺配快照，不存在返回 null。 */
    public BlendSnapshotRow findSnapshotByBlendKey(String blendKey) {
        try {
            return jdbc.queryForObject(SNAPSHOT_SELECT + " FROM blend_snapshot WHERE blend_key = ?",
                    SNAPSHOT_MAPPER, blendKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 快照全部水源明细，按主键升序（即规范化水源顺序）。 */
    public List<BlendLineRow> listBlendLines(long snapshotId) {
        return jdbc.query(
                "SELECT id, snapshot_id, source_id, source_version, amount, salinity_mg_l"
                        + " FROM blend_snapshot_line WHERE snapshot_id = ? ORDER BY id",
                BLEND_LINE_MAPPER, snapshotId);
    }

    /** 申请全部掺配快照，按主键升序（即核销发生顺序）。 */
    public List<BlendSnapshotRow> listSnapshotsByAllocation(String allocationKey) {
        return jdbc.query(SNAPSHOT_SELECT + " FROM blend_snapshot WHERE allocation_key = ? ORDER BY id",
                SNAPSHOT_MAPPER, allocationKey);
    }
}
