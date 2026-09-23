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

    /** 供水窗口行；status 为 OPEN / CLOSED。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, String status, long createdNanos) {
    }

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status, String sourceId,
                                long version, long createdNanos, long updatedNanos) {
    }

    /** 窗口水源配置行。 */
    public record WindowSourceRow(long id, long windowId, String sourceId, BigDecimal supplyCap,
                                  long createdNanos) {
    }

    /** 额度水源分片行（关联查询带出 allocationKey 与 windowId，便于构建矩阵快照）。 */
    public record SliceRow(long id, long allocationId, String allocationKey, long windowId, String sourceId,
                           BigDecimal amount, BigDecimal consumed) {
    }

    /** 重平衡单行，激活后不可变。 */
    public record RebalanceRow(long id, String rebalanceKey, long windowId, String requestId,
                               String snapshotJson, long createdNanos) {
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

    private static final String WINDOW_COLUMNS =
            "id, window_key, channel_id, start_nanos, end_nanos, planned_volume, status, created_nanos";

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getString("status"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getString("requester"), rs.getString("status"), rs.getString("source_id"),
            rs.getLong("version"), rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " source_id, version, created_nanos, updated_nanos";

    private static final RowMapper<WindowSourceRow> WINDOW_SOURCE_MAPPER = (rs, n) -> new WindowSourceRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getString("source_id"),
            rs.getBigDecimal("supply_cap"), rs.getLong("created_nanos"));

    private static final RowMapper<SliceRow> SLICE_MAPPER = (rs, n) -> new SliceRow(
            rs.getLong("id"), rs.getLong("allocation_id"), rs.getString("allocation_key"),
            rs.getLong("window_id"), rs.getString("source_id"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("consumed_amount"));

    private static final String SLICE_SELECT =
            "SELECT s.id, s.allocation_id, a.allocation_key, a.window_id, s.source_id,"
                    + " s.amount, s.consumed_amount FROM allocation_slice s"
                    + " JOIN allocation a ON a.id = s.allocation_id";

    private static final RowMapper<RebalanceRow> REBALANCE_MAPPER = (rs, n) -> new RebalanceRow(
            rs.getLong("id"), rs.getString("rebalance_key"), rs.getLong("window_id"),
            rs.getString("request_id"), rs.getString("snapshot_json"), rs.getLong("created_nanos"));

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
                    "SELECT " + WINDOW_COLUMNS + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准与限供。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + WINDOW_COLUMNS + " FROM supply_window WHERE id = ? FOR UPDATE",
                    WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 关闭窗口：置为 CLOSED，关闭后不可重平衡。 */
    public void closeWindow(long id) {
        jdbc.update("UPDATE supply_window SET status = 'CLOSED' WHERE id = ?", id);
    }

    /** 判断同渠道是否存在与 [startNanos, endNanos) 重叠的窗口（相邻合法）。 */
    public boolean existsOverlappingWindow(String channelId, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supply_window WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelId, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 插入申请（初始 REQUESTED，未绑定水源）并返回主键。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, long nowNanos) {
        return insertAllocation(allocationKey, windowId, userId, amount, requester, null, nowNanos);
    }

    /** 插入申请（初始 REQUESTED，绑定水源 sourceId，可为 null）并返回主键。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, String sourceId, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester, status, source_id, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            ps.setString(6, sourceId);
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
     * 更新申请状态与变更时间，递增乐观锁版本，并同步持有额度：批准时持有额度等于原申请水量，
     * 取消时归零，REQUESTED 保持当前持有额度不变。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?, version = version + 1,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END WHERE id = ?",
                status, updatedNanos, status, status, id);
    }

    /** 转让扣减源持有额度（不得为负由事务内校验保证），递增版本并记录变更时间。 */
    public void decrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?, version = version + 1,"
                + " updated_nanos = ? WHERE id = ?",
                delta, updatedNanos, id);
    }

    /** 递增申请乐观锁版本并记录变更时间（核销/重平衡改变分片时调用）。 */
    public void bumpVersion(long id, long updatedNanos) {
        jdbc.update("UPDATE allocation SET version = version + 1, updated_nanos = ? WHERE id = ?",
                updatedNanos, id);
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
    // 窗口水源配置
    // ------------------------------------------------------------------

    /** 整体删除窗口水源配置（替换重配时在窗口锁内先删后插）。 */
    public void deleteWindowSources(long windowId) {
        jdbc.update("DELETE FROM window_source WHERE window_id = ?", windowId);
    }

    /** 插入一条窗口水源配置。 */
    public void insertWindowSource(long windowId, String sourceId, BigDecimal supplyCap, long createdNanos) {
        jdbc.update("INSERT INTO window_source (window_id, source_id, supply_cap, created_nanos)"
                + " VALUES (?, ?, ?, ?)", windowId, sourceId, supplyCap, createdNanos);
    }

    /** 窗口全部水源配置，按 sourceId 升序。 */
    public List<WindowSourceRow> listWindowSources(long windowId) {
        return jdbc.query(
                "SELECT id, window_id, source_id, supply_cap, created_nanos FROM window_source"
                        + " WHERE window_id = ? ORDER BY source_id", WINDOW_SOURCE_MAPPER, windowId);
    }

    // ------------------------------------------------------------------
    // 额度水源分片
    // ------------------------------------------------------------------

    /** 插入分片（初始额度与核销量）并返回主键。 */
    public long insertSlice(long allocationId, String sourceId, BigDecimal amount, BigDecimal consumed) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation_slice (allocation_id, source_id, amount, consumed_amount)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, allocationId);
            ps.setString(2, sourceId);
            ps.setBigDecimal(3, amount);
            ps.setBigDecimal(4, consumed);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按申请与水源查询分片，不存在返回 null。 */
    public SliceRow findSlice(long allocationId, String sourceId) {
        try {
            return jdbc.queryForObject(SLICE_SELECT + " WHERE s.allocation_id = ? AND s.source_id = ?",
                    SLICE_MAPPER, allocationId, sourceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 更新分片额度与累计核销量（调用方在窗口锁内保证 chk 约束成立）。 */
    public void updateSlice(long sliceId, BigDecimal amount, BigDecimal consumed) {
        jdbc.update("UPDATE allocation_slice SET amount = ?, consumed_amount = ? WHERE id = ?",
                amount, consumed, sliceId);
    }

    /** 申请的全部分片，按 sourceId 升序。 */
    public List<SliceRow> listSlicesByAllocation(long allocationId) {
        return jdbc.query(SLICE_SELECT + " WHERE s.allocation_id = ? ORDER BY s.source_id",
                SLICE_MAPPER, allocationId);
    }

    /** 窗口全部申请的全部分片（完整矩阵），按 sourceId、allocationKey 稳定排序。 */
    public List<SliceRow> listSlicesByWindow(long windowId) {
        return jdbc.query(SLICE_SELECT + " WHERE a.window_id = ? ORDER BY s.source_id, a.allocation_key",
                SLICE_MAPPER, windowId);
    }

    // ------------------------------------------------------------------
    // 重平衡单
    // ------------------------------------------------------------------

    /** 插入重平衡单（冻结快照 JSON）并返回主键。 */
    public long insertRebalance(String rebalanceKey, long windowId, String requestId, String snapshotJson,
                                long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rebalance (rebalance_key, window_id, request_id, snapshot_json, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rebalanceKey);
            ps.setLong(2, windowId);
            ps.setString(3, requestId);
            ps.setString(4, snapshotJson);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询重平衡单，不存在返回 null。 */
    public RebalanceRow findRebalanceByKey(String rebalanceKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, rebalance_key, window_id, request_id, snapshot_json, created_nanos"
                            + " FROM rebalance WHERE rebalance_key = ?", REBALANCE_MAPPER, rebalanceKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部重平衡单，按主键升序（激活顺序）。 */
    public List<RebalanceRow> listRebalances(long windowId) {
        return jdbc.query(
                "SELECT id, rebalance_key, window_id, request_id, snapshot_json, created_nanos"
                        + " FROM rebalance WHERE window_id = ? ORDER BY id", REBALANCE_MAPPER, windowId);
    }
}
