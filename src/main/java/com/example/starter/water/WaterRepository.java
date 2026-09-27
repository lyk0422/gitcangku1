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

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status,
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

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    /** 下泄曲线版本行。 */
    public record CurveRow(long id, long windowId, int version, long createdNanos) {
    }

    /** 下泄曲线逐小时上限点行。 */
    public record CurvePointRow(long curveId, long hourStartNanos, BigDecimal limitAmount) {
    }

    /** 申请逐小时预留行；amount 为当前预留，redeemed 为已核销。 */
    public record AllocationHourRow(long allocationId, long hourStartNanos, BigDecimal amount,
                                    BigDecimal redeemed) {
    }

    /** 批准快照行；curveVersion 为 null 表示批准时窗口无曲线。 */
    public record SnapshotRow(long allocationId, Integer curveVersion, long createdNanos) {
    }

    /** 批准快照逐小时预留行。 */
    public record SnapshotHourRow(long allocationId, long hourStartNanos, BigDecimal amount) {
    }

    /** 核销流水行，创建后不可变。 */
    public record RedemptionRow(long id, long allocationId, long hourStartNanos, BigDecimal amount,
                                String actor, long createdNanos) {
    }

    /** 窗口逐小时负载行：reserved 为已批准未核销，redeemed 为已核销合计。 */
    public record HourLoadRow(long hourStartNanos, BigDecimal reserved, BigDecimal redeemed) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " created_nanos, updated_nanos";

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

    private static final RowMapper<CurveRow> CURVE_MAPPER = (rs, n) -> new CurveRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getInt("version"), rs.getLong("created_nanos"));

    private static final RowMapper<CurvePointRow> CURVE_POINT_MAPPER = (rs, n) -> new CurvePointRow(
            rs.getLong("curve_id"), rs.getLong("hour_start_nanos"), rs.getBigDecimal("limit_amount"));

    private static final RowMapper<AllocationHourRow> ALLOCATION_HOUR_MAPPER = (rs, n) -> new AllocationHourRow(
            rs.getLong("allocation_id"), rs.getLong("hour_start_nanos"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("redeemed"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("allocation_id"),
            rs.getObject("curve_version") == null ? null : rs.getInt("curve_version"),
            rs.getLong("created_nanos"));

    private static final RowMapper<SnapshotHourRow> SNAPSHOT_HOUR_MAPPER = (rs, n) -> new SnapshotHourRow(
            rs.getLong("allocation_id"), rs.getLong("hour_start_nanos"), rs.getBigDecimal("amount"));

    private static final RowMapper<RedemptionRow> REDEMPTION_MAPPER = (rs, n) -> new RedemptionRow(
            rs.getLong("id"), rs.getLong("allocation_id"), rs.getLong("hour_start_nanos"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<HourLoadRow> HOUR_LOAD_MAPPER = (rs, n) -> new HourLoadRow(
            rs.getLong("hour_start_nanos"), rs.getBigDecimal("reserved"), rs.getBigDecimal("redeemed"));

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
    // 下泄曲线
    // ------------------------------------------------------------------

    /** 插入新曲线版本并返回主键。 */
    public long insertCurve(long windowId, int version, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_curve (window_id, version, created_nanos) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, windowId);
            ps.setInt(2, version);
            ps.setLong(3, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 插入曲线逐小时上限点。 */
    public void insertCurvePoint(long curveId, long hourStartNanos, BigDecimal limitAmount) {
        jdbc.update("INSERT INTO release_curve_point (curve_id, hour_start_nanos, limit_amount)"
                + " VALUES (?, ?, ?)", curveId, hourStartNanos, limitAmount);
    }

    /** 查询窗口最新曲线版本，无则 null。 */
    public CurveRow findLatestCurve(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, version, created_nanos FROM release_curve"
                            + " WHERE window_id = ? ORDER BY version DESC LIMIT 1", CURVE_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部曲线版本，按版本号升序。 */
    public List<CurveRow> listCurves(long windowId) {
        return jdbc.query(
                "SELECT id, window_id, version, created_nanos FROM release_curve"
                        + " WHERE window_id = ? ORDER BY version", CURVE_MAPPER, windowId);
    }

    /** 曲线版本全部小时点，按小时升序。 */
    public List<CurvePointRow> listCurvePoints(long curveId) {
        return jdbc.query(
                "SELECT curve_id, hour_start_nanos, limit_amount FROM release_curve_point"
                        + " WHERE curve_id = ? ORDER BY hour_start_nanos", CURVE_POINT_MAPPER, curveId);
    }

    // ------------------------------------------------------------------
    // 逐小时预留
    // ------------------------------------------------------------------

    /** 插入申请逐小时预留声明（提交时）。 */
    public void insertAllocationHour(long allocationId, long hourStartNanos, BigDecimal amount) {
        jdbc.update("INSERT INTO allocation_hour (allocation_id, hour_start_nanos, amount, redeemed)"
                + " VALUES (?, ?, ?, 0)", allocationId, hourStartNanos, amount);
    }

    /** 申请全部逐小时预留行，按小时升序。 */
    public List<AllocationHourRow> listAllocationHours(long allocationId) {
        return jdbc.query(
                "SELECT allocation_id, hour_start_nanos, amount, redeemed FROM allocation_hour"
                        + " WHERE allocation_id = ? ORDER BY hour_start_nanos", ALLOCATION_HOUR_MAPPER,
                allocationId);
    }

    /** 申请某小时的预留行，不存在返回 null。 */
    public AllocationHourRow findAllocationHour(long allocationId, long hourStartNanos) {
        try {
            return jdbc.queryForObject(
                    "SELECT allocation_id, hour_start_nanos, amount, redeemed FROM allocation_hour"
                            + " WHERE allocation_id = ? AND hour_start_nanos = ?",
                    ALLOCATION_HOUR_MAPPER, allocationId, hourStartNanos);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 转让扣减源申请某小时预留（不得压过已核销由事务内校验与 CHECK 约束保证）。 */
    public void decrementAllocationHour(long allocationId, long hourStartNanos, BigDecimal delta) {
        jdbc.update("UPDATE allocation_hour SET amount = amount - ?"
                + " WHERE allocation_id = ? AND hour_start_nanos = ?", delta, allocationId, hourStartNanos);
    }

    /** 取消申请：逐小时预留压降为已核销量，释放未核销部分。 */
    public void releaseAllocationHours(long allocationId) {
        jdbc.update("UPDATE allocation_hour SET amount = redeemed WHERE allocation_id = ?", allocationId);
    }

    /** 核销：同一小时已核销量累加。 */
    public void addRedeemed(long allocationId, long hourStartNanos, BigDecimal delta) {
        jdbc.update("UPDATE allocation_hour SET redeemed = redeemed + ?"
                + " WHERE allocation_id = ? AND hour_start_nanos = ?", delta, allocationId, hourStartNanos);
    }

    /**
     * 窗口逐小时负载：已批准未核销（APPROVED 的 amount - redeemed）与已核销合计
     * （APPROVED 与已取消申请的 redeemed；取消时预留已压降为已核销量，其 amount 即 redeemed）。
     */
    public List<HourLoadRow> sumHourLoad(long windowId) {
        return jdbc.query(
                "SELECT ah.hour_start_nanos,"
                        + " SUM(CASE WHEN a.status = 'APPROVED' THEN ah.amount - ah.redeemed ELSE 0 END) AS reserved,"
                        + " SUM(ah.redeemed) AS redeemed"
                        + " FROM allocation_hour ah JOIN allocation a ON a.id = ah.allocation_id"
                        + " WHERE a.window_id = ? AND a.status IN ('APPROVED', 'CANCELLED')"
                        + " GROUP BY ah.hour_start_nanos ORDER BY ah.hour_start_nanos",
                HOUR_LOAD_MAPPER, windowId);
    }

    // ------------------------------------------------------------------
    // 批准快照与核销流水
    // ------------------------------------------------------------------

    /** 固化批准快照（曲线版本可为 null）。 */
    public void insertSnapshot(long allocationId, Integer curveVersion, long createdNanos) {
        jdbc.update("INSERT INTO approval_snapshot (allocation_id, curve_version, created_nanos)"
                + " VALUES (?, ?, ?)", allocationId, curveVersion, createdNanos);
    }

    /** 固化批准快照逐小时预留。 */
    public void insertSnapshotHour(long allocationId, long hourStartNanos, BigDecimal amount) {
        jdbc.update("INSERT INTO approval_snapshot_hour (allocation_id, hour_start_nanos, amount)"
                + " VALUES (?, ?, ?)", allocationId, hourStartNanos, amount);
    }

    /** 查询批准快照，未批准返回 null。 */
    public SnapshotRow findSnapshot(long allocationId) {
        try {
            return jdbc.queryForObject(
                    "SELECT allocation_id, curve_version, created_nanos FROM approval_snapshot"
                            + " WHERE allocation_id = ?", SNAPSHOT_MAPPER, allocationId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 批准快照逐小时预留，按小时升序。 */
    public List<SnapshotHourRow> listSnapshotHours(long allocationId) {
        return jdbc.query(
                "SELECT allocation_id, hour_start_nanos, amount FROM approval_snapshot_hour"
                        + " WHERE allocation_id = ? ORDER BY hour_start_nanos", SNAPSHOT_HOUR_MAPPER,
                allocationId);
    }

    /** 插入不可变核销流水并返回主键。 */
    public long insertRedemption(long allocationId, long hourStartNanos, BigDecimal amount, String actor,
                                 long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO redemption (allocation_id, hour_start_nanos, amount, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, allocationId);
            ps.setLong(2, hourStartNanos);
            ps.setBigDecimal(3, amount);
            ps.setString(4, actor);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 申请全部核销流水，按主键升序。 */
    public List<RedemptionRow> listRedemptions(long allocationId) {
        return jdbc.query(
                "SELECT id, allocation_id, hour_start_nanos, amount, actor, created_nanos"
                        + " FROM redemption WHERE allocation_id = ? ORDER BY id", REDEMPTION_MAPPER,
                allocationId);
    }
}
