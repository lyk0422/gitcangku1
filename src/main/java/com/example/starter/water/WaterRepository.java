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
                                BigDecimal heldAmount, BigDecimal creditCoefficient, String requester,
                                String status, long createdNanos, long updatedNanos) {
    }

    /** 降雨记录行；status：PENDING / EFFECTIVE / REVOKED。 */
    public record RainfallRow(long id, String rainKey, long windowId, BigDecimal rainfallMm, String status,
                              String registrar, int confirmCount, long createdNanos, Long effectiveNanos,
                              Long revokedNanos, String revokedBy) {
    }

    /** 降雨确认行：同一降雨记录每个角色至多一条。 */
    public record RainfallConfirmationRow(long id, long rainId, String roleId, String confirmer,
                                          long createdNanos) {
    }

    /** 核销流水行，创建后不可变；系数/降雨/抵扣/扣减均为核销时刻固化快照。 */
    public record WriteOffRow(long id, String writeOffKey, String batchKey, long windowId,
                              String allocationKey, BigDecimal requestedAmount, BigDecimal rainfallMm,
                              BigDecimal coefficient, BigDecimal creditAmount, BigDecimal quotaDeduction,
                              BigDecimal heldBefore, BigDecimal heldAfter, String actor, long createdNanos) {
    }

    /** 核销固化的降雨记录证据行。 */
    public record WriteOffRainRow(long id, long writeOffId, String rainKey, BigDecimal rainfallMm,
                                  long createdNanos) {
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

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getBigDecimal("credit_coefficient"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, credit_coefficient,"
                    + " requester, status, created_nanos, updated_nanos";

    private static final RowMapper<RainfallRow> RAINFALL_MAPPER = (rs, n) -> new RainfallRow(
            rs.getLong("id"), rs.getString("rain_key"), rs.getLong("window_id"),
            rs.getBigDecimal("rainfall_mm"), rs.getString("status"), rs.getString("registrar"),
            rs.getInt("confirm_count"), rs.getLong("created_nanos"),
            rs.getObject("effective_nanos") == null ? null : rs.getLong("effective_nanos"),
            rs.getObject("revoked_nanos") == null ? null : rs.getLong("revoked_nanos"),
            rs.getString("revoked_by"));

    private static final String RAINFALL_SELECT =
            "SELECT id, rain_key, window_id, rainfall_mm, status, registrar, confirm_count,"
                    + " created_nanos, effective_nanos, revoked_nanos, revoked_by";

    private static final RowMapper<RainfallConfirmationRow> RAINFALL_CONFIRMATION_MAPPER = (rs, n) ->
            new RainfallConfirmationRow(rs.getLong("id"), rs.getLong("rain_id"), rs.getString("role_id"),
                    rs.getString("confirmer"), rs.getLong("created_nanos"));

    private static final RowMapper<WriteOffRow> WRITE_OFF_MAPPER = (rs, n) -> new WriteOffRow(
            rs.getLong("id"), rs.getString("write_off_key"), rs.getString("batch_key"),
            rs.getLong("window_id"), rs.getString("allocation_key"),
            rs.getBigDecimal("requested_amount"), rs.getBigDecimal("rainfall_mm"),
            rs.getBigDecimal("coefficient"), rs.getBigDecimal("credit_amount"),
            rs.getBigDecimal("quota_deduction"), rs.getBigDecimal("held_before"),
            rs.getBigDecimal("held_after"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final String WRITE_OFF_SELECT =
            "SELECT id, write_off_key, batch_key, window_id, allocation_key, requested_amount, rainfall_mm,"
                    + " coefficient, credit_amount, quota_deduction, held_before, held_after, actor,"
                    + " created_nanos";

    private static final RowMapper<WriteOffRainRow> WRITE_OFF_RAIN_MAPPER = (rs, n) -> new WriteOffRainRow(
            rs.getLong("id"), rs.getLong("write_off_id"), rs.getString("rain_key"),
            rs.getBigDecimal("rainfall_mm"), rs.getLong("created_nanos"));

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
    // 降雨记录与确认
    // ------------------------------------------------------------------

    /** 插入降雨记录（初始 PENDING，0 人确认）并返回主键。 */
    public long insertRainfall(String rainKey, long windowId, BigDecimal rainfallMm, String registrar,
                               long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rainfall_record (rain_key, window_id, rainfall_mm, status, registrar,"
                            + " confirm_count, created_nanos, effective_nanos, revoked_nanos, revoked_by)"
                            + " VALUES (?, ?, ?, 'PENDING', ?, 0, ?, NULL, NULL, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, rainKey);
            ps.setLong(2, windowId);
            ps.setBigDecimal(3, rainfallMm);
            ps.setString(4, registrar);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询降雨记录，不存在返回 null。 */
    public RainfallRow findRainfallByKey(String rainKey) {
        try {
            return jdbc.queryForObject(RAINFALL_SELECT + " FROM rainfall_record WHERE rain_key = ?",
                    RAINFALL_MAPPER, rainKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定降雨记录行（FOR UPDATE），不存在返回 null。 */
    public RainfallRow lockRainfallByKey(String rainKey) {
        try {
            return jdbc.queryForObject(
                    RAINFALL_SELECT + " FROM rainfall_record WHERE rain_key = ? FOR UPDATE",
                    RAINFALL_MAPPER, rainKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部降雨记录（含已撤销），按主键升序。 */
    public List<RainfallRow> listRainfall(long windowId) {
        return jdbc.query(RAINFALL_SELECT + " FROM rainfall_record WHERE window_id = ? ORDER BY id",
                RAINFALL_MAPPER, windowId);
    }

    /** 锁定窗口全部生效降雨记录行（FOR UPDATE）并按主键升序返回，核销计算降雨基数时使用。 */
    public List<RainfallRow> lockEffectiveRainfall(long windowId) {
        return jdbc.query(
                RAINFALL_SELECT + " FROM rainfall_record WHERE window_id = ? AND status = 'EFFECTIVE'"
                        + " ORDER BY id FOR UPDATE", RAINFALL_MAPPER, windowId);
    }

    /** 插入角色确认，(降雨记录, 角色) 重复时由唯一约束拒绝。 */
    public void insertConfirmation(long rainId, String roleId, String confirmer, long createdNanos) {
        jdbc.update("INSERT INTO rainfall_confirmation (rain_id, role_id, confirmer, created_nanos)"
                + " VALUES (?, ?, ?, ?)", rainId, roleId, confirmer, createdNanos);
    }

    /** 降雨记录全部确认明细，按主键升序（即确认先后）。 */
    public List<RainfallConfirmationRow> listConfirmations(long rainId) {
        return jdbc.query(
                "SELECT id, rain_id, role_id, confirmer, created_nanos FROM rainfall_confirmation"
                        + " WHERE rain_id = ? ORDER BY id",
                RAINFALL_CONFIRMATION_MAPPER, rainId);
    }

    /** 统计某角色是否已确认过该降雨记录。 */
    public boolean existsConfirmation(long rainId, String roleId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rainfall_confirmation WHERE rain_id = ? AND role_id = ?",
                Integer.class, rainId, roleId);
        return count != null && count > 0;
    }

    /** 将降雨记录置为生效并固化确认人数与生效时间。 */
    public void markRainfallEffective(long rainId, int confirmCount, long effectiveNanos) {
        jdbc.update("UPDATE rainfall_record SET status = 'EFFECTIVE', confirm_count = ?,"
                + " effective_nanos = ? WHERE id = ?", confirmCount, effectiveNanos, rainId);
    }

    /** 同步 PENDING 记录的确认人数（仍未达到两名不同角色时）。 */
    public void updateRainfallConfirmCount(long rainId, int confirmCount) {
        jdbc.update("UPDATE rainfall_record SET confirm_count = ? WHERE id = ?", confirmCount, rainId);
    }

    /** 撤销降雨记录：PENDING/EFFECTIVE 可撤销，置 REVOKED 并记录操作人与时间。 */
    public void revokeRainfall(long rainId, String revokedBy, long revokedNanos) {
        jdbc.update("UPDATE rainfall_record SET status = 'REVOKED', revoked_by = ?, revoked_nanos = ?"
                + " WHERE id = ?", revokedBy, revokedNanos, rainId);
    }

    // ------------------------------------------------------------------
    // 抵扣系数与核销
    // ------------------------------------------------------------------

    /** 声明（或更新）申请的降雨抵扣系数，单位立方米/毫米。 */
    public void updateCreditCoefficient(long allocationId, BigDecimal coefficient, long updatedNanos) {
        jdbc.update("UPDATE allocation SET credit_coefficient = ?, updated_nanos = ? WHERE id = ?",
                coefficient, updatedNanos, allocationId);
    }

    /** 插入不可变核销流水并返回主键；守恒由调用方校验、CHECK 约束兜底。 */
    public long insertWriteOff(String writeOffKey, String batchKey, long windowId, String allocationKey,
                               BigDecimal requestedAmount, BigDecimal rainfallMm, BigDecimal coefficient,
                               BigDecimal creditAmount, BigDecimal quotaDeduction, BigDecimal heldBefore,
                               BigDecimal heldAfter, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO write_off (write_off_key, batch_key, window_id, allocation_key,"
                            + " requested_amount, rainfall_mm, coefficient, credit_amount, quota_deduction,"
                            + " held_before, held_after, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeOffKey);
            ps.setString(2, batchKey);
            ps.setLong(3, windowId);
            ps.setString(4, allocationKey);
            ps.setBigDecimal(5, requestedAmount);
            ps.setBigDecimal(6, rainfallMm);
            ps.setBigDecimal(7, coefficient);
            ps.setBigDecimal(8, creditAmount);
            ps.setBigDecimal(9, quotaDeduction);
            ps.setBigDecimal(10, heldBefore);
            ps.setBigDecimal(11, heldAfter);
            ps.setString(12, actor);
            ps.setLong(13, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 写入核销固化的降雨记录证据快照。 */
    public void insertWriteOffRain(long writeOffId, String rainKey, BigDecimal rainfallMm, long createdNanos) {
        jdbc.update("INSERT INTO write_off_rain (write_off_id, rain_key, rainfall_mm, created_nanos)"
                + " VALUES (?, ?, ?, ?)", writeOffId, rainKey, rainfallMm, createdNanos);
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public WriteOffRow findWriteOffByKey(String writeOffKey) {
        try {
            return jdbc.queryForObject(WRITE_OFF_SELECT + " FROM write_off WHERE write_off_key = ?",
                    WRITE_OFF_MAPPER, writeOffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按批量业务键查询任意一笔核销流水，用于批量键复用检测，不存在返回 null。 */
    public WriteOffRow findWriteOffByBatchKey(String batchKey) {
        try {
            return jdbc.queryForObject(WRITE_OFF_SELECT + " FROM write_off WHERE batch_key = ? LIMIT 1",
                    WRITE_OFF_MAPPER, batchKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部核销流水（不可变），按主键升序。 */
    public List<WriteOffRow> listWriteOffs(long windowId) {
        return jdbc.query(WRITE_OFF_SELECT + " FROM write_off WHERE window_id = ? ORDER BY id",
                WRITE_OFF_MAPPER, windowId);
    }

    /** 单笔核销固化的全部降雨记录证据，按关联主键升序。 */
    public List<WriteOffRainRow> listWriteOffRains(long writeOffId) {
        return jdbc.query(
                "SELECT id, write_off_id, rain_key, rainfall_mm, created_nanos FROM write_off_rain"
                        + " WHERE write_off_id = ? ORDER BY id", WRITE_OFF_RAIN_MAPPER, writeOffId);
    }

    /** 申请历史核销笔数。 */
    public int countWriteOffsByAllocation(String allocationKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM write_off WHERE allocation_key = ?", Integer.class, allocationKey);
        return count == null ? 0 : count;
    }

    /** 申请历史降雨抵扣合计，无则 0。 */
    public BigDecimal sumCreditByAllocation(String allocationKey) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(credit_amount), 0) FROM write_off WHERE allocation_key = ?",
                BigDecimal.class, allocationKey);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 申请历史配额扣减合计，无则 0。 */
    public BigDecimal sumQuotaDeductionByAllocation(String allocationKey) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quota_deduction), 0) FROM write_off WHERE allocation_key = ?",
                BigDecimal.class, allocationKey);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 窗口核销的某列合计（requested_amount/credit_amount/quota_deduction），无则 0。 */
    public BigDecimal sumWriteOffColumn(long windowId, String column) {
        // column 为内部固定字面量，不接受外部输入
        if (!column.equals("requested_amount") && !column.equals("credit_amount")
                && !column.equals("quota_deduction")) {
            throw new IllegalArgumentException("unsupported column: " + column);
        }
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + column + "), 0) FROM write_off WHERE window_id = ?",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
