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

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。
     *  flowRate/supplyStartNanos/supplyEndNanos/availableHead 仅水力型申请非空。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status,
                                long createdNanos, long updatedNanos, Long flowRate, Long supplyStartNanos,
                                Long supplyEndNanos, Long availableHead) {
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

    /** 渠段阻力参数行；version 从 1 起，每次成功修改 +1。 */
    public record SegmentRow(long id, String segmentKey, long resistance, int version,
                             long createdNanos, long updatedNanos) {
    }

    /** 申请路径上的一段（按 seq 升序）。 */
    public record PathRow(int seq, long segmentId, String segmentKey) {
    }

    /** 审批冻结的时片快照行。 */
    public record SliceRow(long segmentId, String segmentKey, long sliceStartNanos, long sliceEndNanos,
                           long totalFlow, long resistance, long resistanceVersion, long loss) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"),
            rs.getObject("flow_rate") == null ? null : rs.getLong("flow_rate"),
            rs.getObject("supply_start_nanos") == null ? null : rs.getLong("supply_start_nanos"),
            rs.getObject("supply_end_nanos") == null ? null : rs.getLong("supply_end_nanos"),
            rs.getObject("available_head") == null ? null : rs.getLong("available_head"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " created_nanos, updated_nanos, flow_rate, supply_start_nanos, supply_end_nanos,"
                    + " available_head";

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

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getString("segment_key"), rs.getLong("resistance"),
            rs.getInt("version"), rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String SEGMENT_SELECT =
            "SELECT id, segment_key, resistance, version, created_nanos, updated_nanos";

    private static final RowMapper<PathRow> PATH_MAPPER = (rs, n) -> new PathRow(
            rs.getInt("seq"), rs.getLong("segment_id"), rs.getString("segment_key"));

    private static final RowMapper<SliceRow> SLICE_MAPPER = (rs, n) -> new SliceRow(
            rs.getLong("segment_id"), rs.getString("segment_key"), rs.getLong("slice_start_nanos"),
            rs.getLong("slice_end_nanos"), rs.getLong("total_flow"), rs.getLong("resistance"),
            rs.getInt("resistance_version"), rs.getLong("loss"));

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

    /** 插入申请（初始 REQUESTED）并返回主键。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            ps.setLong(6, nowNanos);
            ps.setLong(7, nowNanos);
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
     * REQUESTED 保持当前持有额度不变。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END WHERE id = ?",
                status, updatedNanos, status, status, id);
    }

    /** 转让扣减源持有额度（不得为负由事务内校验保证）并记录变更时间。 */
    public void decrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?, updated_nanos = ? WHERE id = ?",
                delta, updatedNanos, id);
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
    // 简化水力模型
    // ------------------------------------------------------------------

    /** 插入渠段（阻力版本从 1 起）并返回主键。 */
    public long insertSegment(String segmentKey, long resistance, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO channel_segment (segment_key, resistance, version, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, 1, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, segmentKey);
            ps.setLong(2, resistance);
            ps.setLong(3, nowNanos);
            ps.setLong(4, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询渠段，不存在返回 null。 */
    public SegmentRow findSegmentByKey(String segmentKey) {
        try {
            return jdbc.queryForObject(SEGMENT_SELECT + " FROM channel_segment WHERE segment_key = ?",
                    SEGMENT_MAPPER, segmentKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询渠段，不存在返回 null。 */
    public SegmentRow findSegmentById(long id) {
        try {
            return jdbc.queryForObject(SEGMENT_SELECT + " FROM channel_segment WHERE id = ?",
                    SEGMENT_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定渠段行（FOR UPDATE），用于阻力修改的版本校验，不存在返回 null。 */
    public SegmentRow lockSegmentByKey(String segmentKey) {
        try {
            return jdbc.queryForObject(SEGMENT_SELECT + " FROM channel_segment WHERE segment_key = ? FOR UPDATE",
                    SEGMENT_MAPPER, segmentKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 更新渠段阻力并递增版本。 */
    public void updateSegmentResistance(long id, long resistance, long newVersion, long updatedNanos) {
        jdbc.update("UPDATE channel_segment SET resistance = ?, version = ?, updated_nanos = ? WHERE id = ?",
                resistance, newVersion, updatedNanos, id);
    }

    /** 插入水力型申请（初始 REQUESTED，持有额度 0）并返回主键。 */
    public long insertHydraulicAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                          String requester, long flowRate, long supplyStartNanos,
                                          long supplyEndNanos, long availableHead, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester,"
                            + " status, created_nanos, updated_nanos, flow_rate, supply_start_nanos,"
                            + " supply_end_nanos, available_head)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            ps.setLong(6, nowNanos);
            ps.setLong(7, nowNanos);
            ps.setLong(8, flowRate);
            ps.setLong(9, supplyStartNanos);
            ps.setLong(10, supplyEndNanos);
            ps.setLong(11, availableHead);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 追加申请路径的一段（seq 从 1 起）。 */
    public void insertPathSegment(long allocationId, int seq, long segmentId) {
        jdbc.update("INSERT INTO allocation_path (allocation_id, seq, segment_id) VALUES (?, ?, ?)",
                allocationId, seq, segmentId);
    }

    /** 把源申请的路径整体复制给目标申请（转让继承原路径）。 */
    public void copyPath(long sourceAllocationId, long targetAllocationId) {
        jdbc.update("INSERT INTO allocation_path (allocation_id, seq, segment_id)"
                        + " SELECT ?, seq, segment_id FROM allocation_path WHERE allocation_id = ? ORDER BY seq",
                targetAllocationId, sourceAllocationId);
    }

    /** 申请路径（按 seq 升序），含渠段业务键。 */
    public List<PathRow> listPath(long allocationId) {
        return jdbc.query("SELECT p.seq, p.segment_id, s.segment_key FROM allocation_path p"
                        + " JOIN channel_segment s ON s.id = p.segment_id"
                        + " WHERE p.allocation_id = ? ORDER BY p.seq", PATH_MAPPER, allocationId);
    }

    /** 全部 APPROVED 且参与水力模型的申请（flow_rate 非空），按主键升序。 */
    public List<AllocationRow> listApprovedHydraulic() {
        return jdbc.query(ALLOCATION_SELECT
                        + " FROM allocation WHERE status = 'APPROVED' AND flow_rate IS NOT NULL ORDER BY id",
                ALLOCATION_MAPPER);
    }

    /** 更新水力型申请的当前流量（转让扣减后同步），并记录变更时间。 */
    public void updateAllocationFlow(long id, long flowRate, long updatedNanos) {
        jdbc.update("UPDATE allocation SET flow_rate = ?, updated_nanos = ? WHERE id = ?",
                flowRate, updatedNanos, id);
    }

    /** 为转让目标写入继承的水力参数（流量、时段、可用水头），并记录变更时间。 */
    public void setAllocationHydraulic(long id, long flowRate, long supplyStartNanos, long supplyEndNanos,
                                       long availableHead, long updatedNanos) {
        jdbc.update("UPDATE allocation SET flow_rate = ?, supply_start_nanos = ?, supply_end_nanos = ?,"
                        + " available_head = ?, updated_nanos = ? WHERE id = ?",
                flowRate, supplyStartNanos, supplyEndNanos, availableHead, updatedNanos, id);
    }

    /** 删除申请全部已冻结时片快照（重算前清理）。 */
    public void deleteSlices(long allocationId) {
        jdbc.update("DELETE FROM allocation_slice WHERE allocation_id = ?", allocationId);
    }

    /** 写入一条冻结时片快照。 */
    public void insertSlice(long allocationId, long segmentId, long sliceStartNanos, long sliceEndNanos,
                            long totalFlow, long resistance, long resistanceVersion, long loss,
                            long createdNanos) {
        jdbc.update("INSERT INTO allocation_slice (allocation_id, segment_id, slice_start_nanos,"
                        + " slice_end_nanos, total_flow, resistance, resistance_version, loss, created_nanos)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                allocationId, segmentId, sliceStartNanos, sliceEndNanos, totalFlow, resistance,
                resistanceVersion, loss, createdNanos);
    }

    /** 申请全部冻结时片快照（含渠段业务键），按渠段与时片起点升序。 */
    public List<SliceRow> listSlices(long allocationId) {
        return jdbc.query("SELECT s.segment_id, seg.segment_key, s.slice_start_nanos, s.slice_end_nanos,"
                        + " s.total_flow, s.resistance, s.resistance_version, s.loss"
                        + " FROM allocation_slice s JOIN channel_segment seg ON seg.id = s.segment_id"
                        + " WHERE s.allocation_id = ? ORDER BY s.segment_id, s.slice_start_nanos",
                SLICE_MAPPER, allocationId);
    }

    /** 获取水力模型全局串行锁（SELECT FOR UPDATE 单行）。 */
    public void lockHydraulic() {
        jdbc.queryForObject("SELECT id FROM hydraulic_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }
}
