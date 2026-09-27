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
 * 时间一律为 UTC 纳秒时间戳；小时点为 UTC 整点纳秒；普通水量为 DECIMAL(19,3)，下泄上限为 DECIMAL(19,2)。
 */
@Repository
public class WaterRepository {

    /** 供水窗口行。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, long createdNanos) {
    }

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，
     * approvedCurveId 为批准时刻固化的曲线版本 ID（无曲线或未批准为 null），任何后续操作不回写。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status, Long approvedCurveId,
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

    /** 下泄曲线版本行；ACTIVE 为窗口当前版本，SUPERSEDED 为历史版本（仍被预留快照引用）。 */
    public record CurveRow(long id, long windowId, int version, String status, long createdNanos) {
    }

    /** 曲线小时点行。 */
    public record CurvePointRow(long curveId, long windowId, long hourStartNanos, BigDecimal capVolume) {
    }

    /** 申请逐小时配水计划行，提交时固化，不可改写。 */
    public record PlanItemRow(long allocationId, long windowId, long hourStartNanos, BigDecimal volume) {
    }

    /** 逐小时预留快照行：曲线版本与预留水量在批准时刻固化，不可变。 */
    public record ReservationRow(long id, long allocationId, long windowId, long hourStartNanos,
                                 BigDecimal reservedVolume, long curveId, long createdNanos) {
    }

    /** 转让引起的源申请逐小时释放台账行（只追加）。 */
    public record ReservationReleaseRow(long id, long transferId, String transferKey, long allocationId,
                                        long windowId, long hourStartNanos, BigDecimal releasedVolume,
                                        long createdNanos) {
    }

    /** 核销流水行，创建后不可变；只扣减同一申请同一小时的未核销预留。 */
    public record WriteoffRow(long id, String writeoffKey, long allocationId, long windowId,
                              long hourStartNanos, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 小时聚合行：某窗口某小时当前未取消申请的预留总量与已核销总量。 */
    public record HourTotalRow(long hourStartNanos, BigDecimal reservedTotal, BigDecimal writtenOffTotal) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final String ALLOCATION_COLUMNS =
            "id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " approved_curve_id, created_nanos, updated_nanos";

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getString("requester"), rs.getString("status"),
            rs.getObject("approved_curve_id") == null ? null : rs.getLong("approved_curve_id"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT = "SELECT " + ALLOCATION_COLUMNS;

    private static final RowMapper<TransferRow> TRANSFER_MAPPER = (rs, n) -> new TransferRow(
            rs.getLong("id"), rs.getString("transfer_key"), rs.getLong("window_id"),
            rs.getString("source_allocation_key"), rs.getString("target_allocation_key"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<CurtailmentRow> CURTAILMENT_MAPPER = (rs, n) -> new CurtailmentRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getBigDecimal("volume"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<CurveRow> CURVE_MAPPER = (rs, n) -> new CurveRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getInt("version"),
            rs.getString("status"), rs.getLong("created_nanos"));

    private static final RowMapper<CurvePointRow> CURVE_POINT_MAPPER = (rs, n) -> new CurvePointRow(
            rs.getLong("curve_id"), rs.getLong("window_id"), rs.getLong("hour_start_nanos"),
            rs.getBigDecimal("cap_volume"));

    private static final RowMapper<PlanItemRow> PLAN_ITEM_MAPPER = (rs, n) -> new PlanItemRow(
            rs.getLong("allocation_id"), rs.getLong("window_id"), rs.getLong("hour_start_nanos"),
            rs.getBigDecimal("volume"));

    private static final RowMapper<ReservationRow> RESERVATION_MAPPER = (rs, n) -> new ReservationRow(
            rs.getLong("id"), rs.getLong("allocation_id"), rs.getLong("window_id"),
            rs.getLong("hour_start_nanos"), rs.getBigDecimal("reserved_volume"),
            rs.getLong("curve_id"), rs.getLong("created_nanos"));

    private static final RowMapper<ReservationReleaseRow> RELEASE_MAPPER = (rs, n) ->
            new ReservationReleaseRow(rs.getLong("id"), rs.getLong("transfer_id"),
                    rs.getString("transfer_key"), rs.getLong("allocation_id"), rs.getLong("window_id"),
                    rs.getLong("hour_start_nanos"), rs.getBigDecimal("released_volume"),
                    rs.getLong("created_nanos"));

    private static final RowMapper<WriteoffRow> WRITEOFF_MAPPER = (rs, n) -> new WriteoffRow(
            rs.getLong("id"), rs.getString("writeoff_key"), rs.getLong("allocation_id"),
            rs.getLong("window_id"), rs.getLong("hour_start_nanos"), rs.getBigDecimal("amount"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<HourTotalRow> HOUR_TOTAL_MAPPER = (rs, n) -> new HourTotalRow(
            rs.getLong("hour_start_nanos"), rs.getBigDecimal("reserved_total"),
            rs.getBigDecimal("written_off_total"));

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
                    "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, created_nanos"
                            + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、转让、核销与曲线修改。 */
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

    /** 插入申请（初始 REQUESTED，曲线快照为空）并返回主键。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester, status, approved_curve_id, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', NULL, ?, ?)", Statement.RETURN_GENERATED_KEYS);
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

    /** 按主键查询申请，不存在返回 null。 */
    public AllocationRow findAllocationById(long id) {
        try {
            return jdbc.queryForObject(ALLOCATION_SELECT + " FROM allocation WHERE id = ?",
                    ALLOCATION_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定申请行（FOR UPDATE），用于串行化转让/核销争用，不存在返回 null。 */
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
     * REQUESTED 保持当前持有额度不变。不改动已固化的 approved_curve_id。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END WHERE id = ?",
                status, updatedNanos, status, status, id);
    }

    /**
     * 批准申请：状态置 APPROVED、持有额度置为原申请水量，并固化本次批准使用的曲线版本 ID（可空）。
     * 已固化过曲线快照的申请不会被改写。
     */
    public void approveAllocation(long id, Long curveId, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = 'APPROVED', held_amount = amount,"
                + " approved_curve_id = COALESCE(approved_curve_id, ?), updated_nanos = ? WHERE id = ?",
                curveId, updatedNanos, id);
    }

    /** 转让扣减源持有额度（不得为负由事务内校验保证）并记录变更时间。 */
    public void decrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?, updated_nanos = ? WHERE id = ?",
                delta, updatedNanos, id);
    }

    /** 批量转让统一落库时把源（含链式中间申请）持有额度置为最终值，并记录变更时间。 */
    public void setHeldAmount(long id, BigDecimal heldAmount, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = ?, updated_nanos = ? WHERE id = ?",
                heldAmount, updatedNanos, id);
    }

    /** 窗口当前所有 APPROVED 申请的当前持有额度之和（BigDecimal 精确求和），无则 0。 */
    public BigDecimal sumApprovedAmount(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(held_amount), 0) FROM allocation WHERE window_id = ? AND status = 'APPROVED'",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 窗口未取消的已批准申请数量（首次配置曲线时不允许存在，因其无逐小时预留快照）。 */
    public Integer countApprovedActive(long windowId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE window_id = ? AND status = 'APPROVED'",
                Integer.class, windowId);
        return count == null ? 0 : count;
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

    // ------------------------------------------------------------------
    // 下泄曲线、逐小时计划/预留/核销
    // ------------------------------------------------------------------

    /** 窗口当前 ACTIVE 曲线版本，不存在返回 null。 */
    public CurveRow findActiveCurve(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, status, created_nanos FROM drawdown_curve"
                            + " WHERE window_id = ? AND status = 'ACTIVE'", CURVE_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口当前 ACTIVE 曲线版本并锁定版本行（FOR UPDATE），不存在返回 null。 */
    public CurveRow lockActiveCurve(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, status, created_nanos FROM drawdown_curve"
                            + " WHERE window_id = ? AND status = 'ACTIVE' FOR UPDATE", CURVE_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询曲线版本，不存在返回 null。 */
    public CurveRow findCurveById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, status, created_nanos FROM drawdown_curve WHERE id = ?",
                    CURVE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口下一个曲线版本号（无历史版本时为 1）。 */
    public int nextCurveVersion(long windowId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM drawdown_curve WHERE window_id = ?",
                Integer.class, windowId);
        return (max == null ? 0 : max) + 1;
    }

    /** 插入曲线版本并返回主键。 */
    public long insertCurve(long windowId, int version, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drawdown_curve (window_id, version, status, created_nanos)"
                            + " VALUES (?, ?, 'ACTIVE', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, windowId);
            ps.setInt(2, version);
            ps.setLong(3, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 将窗口当前 ACTIVE 版本置为 SUPERSEDED。 */
    public void supersedeActiveCurve(long windowId) {
        jdbc.update("UPDATE drawdown_curve SET status = 'SUPERSEDED' WHERE window_id = ? AND status = 'ACTIVE'",
                windowId);
    }

    /** 插入曲线小时点。 */
    public void insertCurvePoint(long curveId, long windowId, long hourStartNanos, BigDecimal capVolume) {
        jdbc.update("INSERT INTO drawdown_curve_point (curve_id, window_id, hour_start_nanos, cap_volume)"
                + " VALUES (?, ?, ?, ?)", curveId, windowId, hourStartNanos, capVolume);
    }

    /** 曲线版本全部小时点，按小时升序。 */
    public List<CurvePointRow> listCurvePoints(long curveId) {
        return jdbc.query(
                "SELECT curve_id, window_id, hour_start_nanos, cap_volume FROM drawdown_curve_point"
                        + " WHERE curve_id = ? ORDER BY hour_start_nanos", CURVE_POINT_MAPPER, curveId);
    }

    /** 插入申请配水计划点。 */
    public void insertPlanItem(long allocationId, long windowId, long hourStartNanos, BigDecimal volume) {
        jdbc.update("INSERT INTO drawdown_plan_item (allocation_id, window_id, hour_start_nanos, volume)"
                + " VALUES (?, ?, ?, ?)", allocationId, windowId, hourStartNanos, volume);
    }

    /** 申请全部逐小时计划点，按小时升序；无计划（无曲线窗口）为空列表。 */
    public List<PlanItemRow> listPlanItems(long allocationId) {
        return jdbc.query(
                "SELECT allocation_id, window_id, hour_start_nanos, volume FROM drawdown_plan_item"
                        + " WHERE allocation_id = ? ORDER BY hour_start_nanos", PLAN_ITEM_MAPPER, allocationId);
    }

    /** 插入批准时刻固化的逐小时预留快照。 */
    public void insertReservation(long allocationId, long windowId, long hourStartNanos, BigDecimal volume,
                                  long curveId, long createdNanos) {
        jdbc.update("INSERT INTO drawdown_reservation (allocation_id, window_id, hour_start_nanos,"
                + " reserved_volume, curve_id, created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                allocationId, windowId, hourStartNanos, volume, curveId, createdNanos);
    }

    /** 单条申请的全部预留快照（按小时升序）；未批准或无曲线窗口为空列表。 */
    public List<ReservationRow> listReservations(long allocationId) {
        return jdbc.query(
                "SELECT id, allocation_id, window_id, hour_start_nanos, reserved_volume, curve_id, created_nanos"
                        + " FROM drawdown_reservation WHERE allocation_id = ? ORDER BY hour_start_nanos",
                RESERVATION_MAPPER, allocationId);
    }

    /** 窗口全部申请的预留快照（按申请、小时升序），用于历史展示。 */
    public List<ReservationRow> listReservationsByWindow(long windowId) {
        return jdbc.query(
                "SELECT r.id, r.allocation_id, r.window_id, r.hour_start_nanos, r.reserved_volume,"
                        + " r.curve_id, r.created_nanos FROM drawdown_reservation r"
                        + " WHERE r.window_id = ? ORDER BY r.allocation_id, r.hour_start_nanos",
                RESERVATION_MAPPER, windowId);
    }

    /**
     * 窗口逐小时当前占用（按事务提交顺序在窗口锁内读取）。每小时：
     * <ul>
     *   <li>reservedTotal = 当前总占用 = 已核销 + 已批准未核销：
     *       未取消申请按 (快照预留 - 转让释放) 计；已取消申请其快照整单释放，只保留其已核销部分；</li>
     *   <li>writtenOffTotal = 该小时全部核销之和（含申请后来被取消的核销，既成事实仍占上限）。</li>
     * </ul>
     * 按小时升序，仅返回出现过预留或核销的小时。
     */
    public List<HourTotalRow> listHourTotals(long windowId) {
        return jdbc.query(
                "SELECT h.hour_start_nanos AS hour_start_nanos,"
                        + " (COALESCE(rs.reserved_total, 0) - COALESCE(rl.released_total, 0)"
                        + "  + COALESCE(wc.cancelled_writeoff_total, 0)) AS reserved_total,"
                        + " COALESCE(wa.all_writeoff_total, 0) AS written_off_total"
                        + " FROM ("
                        + " SELECT hour_start_nanos FROM drawdown_reservation WHERE window_id = ?"
                        + " UNION"
                        + " SELECT hour_start_nanos FROM drawdown_writeoff WHERE window_id = ?"
                        + " ) h"
                        + " LEFT JOIN ("
                        + " SELECT r.hour_start_nanos AS hour_start_nanos,"
                        + " COALESCE(SUM(r.reserved_volume), 0) AS reserved_total"
                        + " FROM drawdown_reservation r"
                        + " JOIN allocation a ON a.id = r.allocation_id"
                        + " WHERE r.window_id = ? AND a.status <> 'CANCELLED' GROUP BY r.hour_start_nanos"
                        + " ) rs ON rs.hour_start_nanos = h.hour_start_nanos"
                        + " LEFT JOIN ("
                        + " SELECT rel.hour_start_nanos AS hour_start_nanos,"
                        + " COALESCE(SUM(rel.released_volume), 0) AS released_total"
                        + " FROM drawdown_reservation_release rel"
                        + " JOIN allocation a ON a.id = rel.allocation_id"
                        + " WHERE rel.window_id = ? AND a.status <> 'CANCELLED' GROUP BY rel.hour_start_nanos"
                        + " ) rl ON rl.hour_start_nanos = h.hour_start_nanos"
                        + " LEFT JOIN ("
                        + " SELECT w.hour_start_nanos AS hour_start_nanos,"
                        + " COALESCE(SUM(w.amount), 0) AS cancelled_writeoff_total"
                        + " FROM drawdown_writeoff w"
                        + " JOIN allocation a ON a.id = w.allocation_id"
                        + " WHERE w.window_id = ? AND a.status = 'CANCELLED' GROUP BY w.hour_start_nanos"
                        + " ) wc ON wc.hour_start_nanos = h.hour_start_nanos"
                        + " LEFT JOIN ("
                        + " SELECT hour_start_nanos, COALESCE(SUM(amount), 0) AS all_writeoff_total"
                        + " FROM drawdown_writeoff WHERE window_id = ? GROUP BY hour_start_nanos"
                        + " ) wa ON wa.hour_start_nanos = h.hour_start_nanos"
                        + " ORDER BY h.hour_start_nanos",
                HOUR_TOTAL_MAPPER, windowId, windowId, windowId, windowId, windowId, windowId);
    }

    /** 插入一条转让释放台账行。 */
    public void insertReservationRelease(long transferId, String transferKey, long allocationId, long windowId,
                                         long hourStartNanos, BigDecimal releasedVolume, long createdNanos) {
        jdbc.update("INSERT INTO drawdown_reservation_release (transfer_id, transfer_key, allocation_id,"
                + " window_id, hour_start_nanos, released_volume, created_nanos)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                transferId, transferKey, allocationId, windowId, hourStartNanos, releasedVolume, createdNanos);
    }

    /** 单条申请在指定小时因转让已释放的总量，无则 0。 */
    public BigDecimal sumReleased(long allocationId, long hourStartNanos) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(released_volume), 0) FROM drawdown_reservation_release"
                        + " WHERE allocation_id = ? AND hour_start_nanos = ?",
                BigDecimal.class, allocationId, hourStartNanos);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 单条申请某小时当前仍占有的未核销预留 = 快照预留 - 转让释放 - 已核销；
     * 申请已取消时快照仍在但状态过滤后返回 0。
     */
    public BigDecimal outstandingReservation(long allocationId, long hourStartNanos) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT CASE WHEN a.status = 'CANCELLED' THEN 0 ELSE COALESCE("
                        + " (SELECT COALESCE(SUM(reserved_volume), 0) FROM drawdown_reservation"
                        + "  WHERE allocation_id = ? AND hour_start_nanos = ?)"
                        + " - (SELECT COALESCE(SUM(released_volume), 0) FROM drawdown_reservation_release"
                        + "  WHERE allocation_id = ? AND hour_start_nanos = ?)"
                        + " - (SELECT COALESCE(SUM(amount), 0) FROM drawdown_writeoff"
                        + "  WHERE allocation_id = ? AND hour_start_nanos = ?), 0) END"
                        + " FROM allocation a WHERE a.id = ?",
                BigDecimal.class, allocationId, hourStartNanos, allocationId, hourStartNanos,
                allocationId, hourStartNanos, allocationId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 单条申请在指定小时的预留总量，无则 0。 */
    public BigDecimal sumReservation(long allocationId, long hourStartNanos) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(reserved_volume), 0) FROM drawdown_reservation"
                        + " WHERE allocation_id = ? AND hour_start_nanos = ?",
                BigDecimal.class, allocationId, hourStartNanos);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 插入核销流水并返回主键。 */
    public long insertWriteoff(String writeoffKey, long allocationId, long windowId, long hourStartNanos,
                               BigDecimal amount, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drawdown_writeoff (writeoff_key, allocation_id, window_id,"
                            + " hour_start_nanos, amount, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setLong(2, allocationId);
            ps.setLong(3, windowId);
            ps.setLong(4, hourStartNanos);
            ps.setBigDecimal(5, amount);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public WriteoffRow findWriteoffByKey(String writeoffKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, writeoff_key, allocation_id, window_id, hour_start_nanos, amount, actor, created_nanos"
                            + " FROM drawdown_writeoff WHERE writeoff_key = ?", WRITEOFF_MAPPER, writeoffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 单条申请在指定小时的已核销总量，无则 0。 */
    public BigDecimal sumWriteoff(long allocationId, long hourStartNanos) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM drawdown_writeoff"
                        + " WHERE allocation_id = ? AND hour_start_nanos = ?",
                BigDecimal.class, allocationId, hourStartNanos);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 窗口全部核销流水（不可变），按主键升序。 */
    public List<WriteoffRow> listWriteoffs(long windowId) {
        return jdbc.query(
                "SELECT id, writeoff_key, allocation_id, window_id, hour_start_nanos, amount, actor, created_nanos"
                        + " FROM drawdown_writeoff WHERE window_id = ? ORDER BY id", WRITEOFF_MAPPER, windowId);
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
}
