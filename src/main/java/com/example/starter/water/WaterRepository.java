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
 * 时间一律为 UTC 纳秒时间戳；配额水量为 DECIMAL(19,3)，下泄曲线/逐小时预留/核销水量为 DECIMAL(19,2)。
 */
@Repository
public class WaterRepository {

    /** 供水窗口行。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, long createdNanos) {
    }

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。
     * usageStartNanos/usageEndNanos 为申请覆盖的下泄小时区间（左闭右开），无曲线窗口为 null；
     * approvalCurveId 为批准时固化的曲线版本 ID，未批准或无曲线窗口批准为 null。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status,
                                long createdNanos, long updatedNanos,
                                Long usageStartNanos, Long usageEndNanos, Long approvalCurveId) {
    }

    /** 转让流水行，创建后不可变。 */
    public record TransferRow(long id, String transferKey, long windowId, String sourceAllocationKey,
                              String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 限供行。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 下泄曲线版本行；ACTIVE 为当前版本，SUPERSEDED 为历史版本。 */
    public record DrawdownCurveRow(long id, long windowId, int version, String status, long createdNanos) {
    }

    /** 下泄曲线小时点行，版本创建后不可变。 */
    public record DrawdownPointRow(long id, long curveId, long windowId, long hourStartNanos,
                                   BigDecimal capVolume) {
    }

    /** 逐小时预留快照旧；reservedVolume/curveId 不可改写，released/writtenOff 为累计计数。 */
    public record HourReservationRow(long id, long allocationId, long windowId, long curveId,
                                     long hourStartNanos, BigDecimal reservedVolume,
                                     BigDecimal releasedVolume, BigDecimal writtenOffVolume, long updatedNanos) {
    }

    /** 核销流水行，创建后不可变。 */
    public record WriteoffRow(long id, String writeoffKey, long allocationId, String allocationKey,
                              long windowId, long hourStartNanos, BigDecimal volume, String actor,
                              long createdNanos) {
    }

    /** 窗口单小时聚合诊断行。 */
    public record HourUsageRow(long hourStartNanos, BigDecimal reservedTotal, BigDecimal releasedTotal,
                               BigDecimal writtenOffTotal) {
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
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"),
            rs.getObject("usage_start_nanos") == null ? null : rs.getLong("usage_start_nanos"),
            rs.getObject("usage_end_nanos") == null ? null : rs.getLong("usage_end_nanos"),
            rs.getObject("approval_curve_id") == null ? null : rs.getLong("approval_curve_id"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " created_nanos, updated_nanos, usage_start_nanos, usage_end_nanos, approval_curve_id";

    private static final RowMapper<TransferRow> TRANSFER_MAPPER = (rs, n) -> new TransferRow(
            rs.getLong("id"), rs.getString("transfer_key"), rs.getLong("window_id"),
            rs.getString("source_allocation_key"), rs.getString("target_allocation_key"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<CurtailmentRow> CURTAILMENT_MAPPER = (rs, n) -> new CurtailmentRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getBigDecimal("volume"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<DrawdownCurveRow> CURVE_MAPPER = (rs, n) -> new DrawdownCurveRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getInt("version"), rs.getString("status"),
            rs.getLong("created_nanos"));

    private static final RowMapper<DrawdownPointRow> POINT_MAPPER = (rs, n) -> new DrawdownPointRow(
            rs.getLong("id"), rs.getLong("curve_id"), rs.getLong("window_id"),
            rs.getLong("hour_start_nanos"), rs.getBigDecimal("cap_volume"));

    private static final RowMapper<HourReservationRow> RESERVATION_MAPPER = (rs, n) -> new HourReservationRow(
            rs.getLong("id"), rs.getLong("allocation_id"), rs.getLong("window_id"),
            rs.getLong("curve_id"), rs.getLong("hour_start_nanos"),
            rs.getBigDecimal("reserved_volume"), rs.getBigDecimal("released_volume"),
            rs.getBigDecimal("written_off_volume"), rs.getLong("updated_nanos"));

    private static final RowMapper<WriteoffRow> WRITEOFF_MAPPER = (rs, n) -> new WriteoffRow(
            rs.getLong("id"), rs.getString("writeoff_key"), rs.getLong("allocation_id"),
            rs.getString("allocation_key"), rs.getLong("window_id"), rs.getLong("hour_start_nanos"),
            rs.getBigDecimal("volume"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<HourUsageRow> HOUR_USAGE_MAPPER = (rs, n) -> new HourUsageRow(
            rs.getLong("hour_start_nanos"), rs.getBigDecimal("reserved_total"),
            rs.getBigDecimal("released_total"), rs.getBigDecimal("written_off_total"));

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

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、转让、核销、限供与曲线变更。 */
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

    /**
     * 插入申请（初始 REQUESTED）并返回主键。
     * usageStartNanos/usageEndNanos 在曲线窗口必填（整点区间），无曲线窗口为 null。
     */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, long nowNanos, Long usageStartNanos, Long usageEndNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester,"
                            + " status, created_nanos, updated_nanos, usage_start_nanos, usage_end_nanos,"
                            + " approval_curve_id)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', ?, ?, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            // held_amount 固定 0 非占位；第 5 个占位起为 requester、created/updated、usage 起止
            ps.setString(5, requester);
            ps.setLong(6, nowNanos);
            ps.setLong(7, nowNanos);
            if (usageStartNanos == null) {
                ps.setObject(8, null);
            } else {
                ps.setLong(8, usageStartNanos);
            }
            if (usageEndNanos == null) {
                ps.setObject(9, null);
            } else {
                ps.setLong(9, usageEndNanos);
            }
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

    /** 按主键锁定申请行（FOR UPDATE），不存在返回 null。 */
    public AllocationRow lockAllocationById(long id) {
        try {
            return jdbc.queryForObject(ALLOCATION_SELECT + " FROM allocation WHERE id = ? FOR UPDATE",
                    ALLOCATION_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 更新申请状态与变更时间，并同步持有额度：批准时持有额度等于原申请水量，取消时归零，
     * REQUESTED 保持当前持有额度不变。approval_curve_id 不被本方法改写（无曲线批准路径保持 NULL）。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END WHERE id = ?",
                status, updatedNanos, status, status, id);
    }

    /** 批准并固化曲线版本（curveId 可为 null 表示无曲线窗口），持有额度同步置为原申请水量。 */
    public void approveAllocation(long id, Long curveId, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = 'APPROVED', held_amount = amount, approval_curve_id = ?,"
                + " updated_nanos = ? WHERE id = ?", curveId, updatedNanos, id);
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

    /** 窗口内申请数量。 */
    public int countAllocations(long windowId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM allocation WHERE window_id = ?",
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

    // ------------------------------------------------------------------
    // 下泄曲线
    // ------------------------------------------------------------------

    /** 插入新曲线版本行并返回主键；版本号由服务层按窗口内最大值 + 1 分配。 */
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

    /** 将窗口当前 ACTIVE 曲线全部置为 SUPERSEDED。 */
    public void supersedeActiveCurves(long windowId) {
        jdbc.update("UPDATE drawdown_curve SET status = 'SUPERSEDED' WHERE window_id = ? AND status = 'ACTIVE'",
                windowId);
    }

    /** 查询窗口当前 ACTIVE 曲线版本，无则 null。 */
    public DrawdownCurveRow findActiveCurve(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, status, created_nanos FROM drawdown_curve"
                            + " WHERE window_id = ? AND status = 'ACTIVE'", CURVE_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口锁内查询并锁定当前 ACTIVE 曲线版本，无则 null。 */
    public DrawdownCurveRow lockActiveCurve(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, status, created_nanos FROM drawdown_curve"
                            + " WHERE window_id = ? AND status = 'ACTIVE' FOR UPDATE", CURVE_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询曲线版本，不存在返回 null。 */
    public DrawdownCurveRow findCurveById(long curveId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, status, created_nanos FROM drawdown_curve WHERE id = ?",
                    CURVE_MAPPER, curveId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口内下一可用版本号（无任何版本时为 1）。 */
    public int nextCurveVersion(long windowId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM drawdown_curve WHERE window_id = ?",
                Integer.class, windowId);
        return (max == null ? 0 : max) + 1;
    }

    /** 窗口全部曲线版本（含历史），按版本号升序。 */
    public List<DrawdownCurveRow> listCurves(long windowId) {
        return jdbc.query(
                "SELECT id, window_id, version, status, created_nanos FROM drawdown_curve"
                        + " WHERE window_id = ? ORDER BY version", CURVE_MAPPER, windowId);
    }

    /** 批量插入曲线小时点。 */
    public void insertPoints(long curveId, long windowId, List<DrawdownPointInput> points) {
        jdbc.batchUpdate("INSERT INTO drawdown_point (curve_id, window_id, hour_start_nanos, cap_volume)"
                + " VALUES (?, ?, ?, ?)", points, points.size(), (ps, point) -> {
            ps.setLong(1, curveId);
            ps.setLong(2, windowId);
            ps.setLong(3, point.hourStartNanos());
            ps.setBigDecimal(4, point.capVolume());
        });
    }

    /** 曲线全部小时点，按小时起点升序。 */
    public List<DrawdownPointRow> listPoints(long curveId) {
        return jdbc.query(
                "SELECT id, curve_id, window_id, hour_start_nanos, cap_volume FROM drawdown_point"
                        + " WHERE curve_id = ? ORDER BY hour_start_nanos", POINT_MAPPER, curveId);
    }

    /** 批量插入批准时固化的逐小时预留快照。 */
    public void insertReservations(long allocationId, long windowId, long curveId,
                                   List<ReservationInput> reservations, long nowNanos) {
        jdbc.batchUpdate("INSERT INTO allocation_hour_reservation (allocation_id, window_id, curve_id,"
                + " hour_start_nanos, reserved_volume, released_volume, written_off_volume, updated_nanos)"
                + " VALUES (?, ?, ?, ?, ?, 0, 0, ?)", reservations, reservations.size(), (ps, r) -> {
            ps.setLong(1, allocationId);
            ps.setLong(2, windowId);
            ps.setLong(3, curveId);
            ps.setLong(4, r.hourStartNanos());
            ps.setBigDecimal(5, r.reservedVolume());
            ps.setLong(6, nowNanos);
        });
    }

    /** 单笔申请的全部逐小时预留，按小时起点升序。 */
    public List<HourReservationRow> listReservationsByAllocation(long allocationId) {
        return jdbc.query(reservationSelect() + " WHERE allocation_id = ? ORDER BY hour_start_nanos",
                RESERVATION_MAPPER, allocationId);
    }

    /** 窗口全部逐小时预留，按申请主键、小时起点升序。 */
    public List<HourReservationRow> listReservationsByWindow(long windowId) {
        return jdbc.query(reservationSelect() + " WHERE window_id = ? ORDER BY allocation_id, hour_start_nanos",
                RESERVATION_MAPPER, windowId);
    }

    /**
     * 窗口逐小时净预留聚合：reservedTotal 为固化预留之和，releasedTotal 为取消释放之和，
     * writtenOffTotal 为核销之和；已批准未核销净预留 = reserved - released - writtenOff。
     * 仅在窗口行 FOR UPDATE 后调用，与全部同窗口写事务串行。
     */
    public List<HourUsageRow> aggregateHourUsage(long windowId) {
        return jdbc.query(
                "SELECT hour_start_nanos, COALESCE(SUM(reserved_volume), 0) AS reserved_total,"
                        + " COALESCE(SUM(released_volume), 0) AS released_total,"
                        + " COALESCE(SUM(written_off_volume), 0) AS written_off_total"
                        + " FROM allocation_hour_reservation WHERE window_id = ? GROUP BY hour_start_nanos"
                        + " ORDER BY hour_start_nanos",
                HOUR_USAGE_MAPPER, windowId);
    }

    /** 锁定某申请某小时的预留行（FOR UPDATE），不存在返回 null。 */
    public HourReservationRow lockReservation(long allocationId, long hourStartNanos) {
        try {
            return jdbc.queryForObject(
                    reservationSelect() + " WHERE allocation_id = ? AND hour_start_nanos = ? FOR UPDATE",
                    RESERVATION_MAPPER, allocationId, hourStartNanos);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 核销计数累加（不超过剩余由事务内校验与 CHECK 约束共同保证）。 */
    public void addWrittenOff(long reservationId, BigDecimal volume, long updatedNanos) {
        jdbc.update("UPDATE allocation_hour_reservation SET written_off_volume = written_off_volume + ?,"
                + " updated_nanos = ? WHERE id = ?", volume, updatedNanos, reservationId);
    }

    /** 取消申请：释放每个小时尚未核销的剩余预留（released = reserved - writtenOff）。 */
    public void releaseRemainingReservations(long allocationId, long updatedNanos) {
        jdbc.update("UPDATE allocation_hour_reservation SET released_volume = reserved_volume - written_off_volume,"
                + " updated_nanos = ? WHERE allocation_id = ?", updatedNanos, allocationId);
    }

    /** 插入不可变核销流水并返回主键。 */
    public long insertWriteoff(String writeoffKey, long allocationId, String allocationKey, long windowId,
                               long hourStartNanos, BigDecimal volume, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO writeoff (writeoff_key, allocation_id, allocation_key, window_id,"
                            + " hour_start_nanos, volume, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setLong(2, allocationId);
            ps.setString(3, allocationKey);
            ps.setLong(4, windowId);
            ps.setLong(5, hourStartNanos);
            ps.setBigDecimal(6, volume);
            ps.setString(7, actor);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public WriteoffRow findWriteoffByKey(String writeoffKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, writeoff_key, allocation_id, allocation_key, window_id, hour_start_nanos,"
                            + " volume, actor, created_nanos FROM writeoff WHERE writeoff_key = ?",
                    WRITEOFF_MAPPER, writeoffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部核销流水（不可变），按主键升序。 */
    public List<WriteoffRow> listWriteoffs(long windowId) {
        return jdbc.query(
                "SELECT id, writeoff_key, allocation_id, allocation_key, window_id, hour_start_nanos,"
                        + " volume, actor, created_nanos FROM writeoff WHERE window_id = ? ORDER BY id",
                WRITEOFF_MAPPER, windowId);
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

    private static String reservationSelect() {
        return "SELECT id, allocation_id, window_id, curve_id, hour_start_nanos, reserved_volume,"
                + " released_volume, written_off_volume, updated_nanos FROM allocation_hour_reservation";
    }

    /** 曲线点写入输入。 */
    public record DrawdownPointInput(long hourStartNanos, BigDecimal capVolume) {
    }

    /** 逐小时预留写入输入。 */
    public record ReservationInput(long hourStartNanos, BigDecimal reservedVolume) {
    }
}
