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

    /** 供水窗口行；储备余额 = reserveVolume - reserveUsed，常规可用量 = 可用总量 - reserveVolume - regularUsed。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, BigDecimal reserveVolume, BigDecimal reserveUsed,
                            BigDecimal regularUsed, int version, Long closedNanos, long createdNanos) {
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

    /** 常规核销流水行，创建后不可变。 */
    public record RegularWriteOffRow(long id, String writeOffKey, long windowId, BigDecimal amount,
                                     long createdNanos) {
    }

    /** 应急核销流水行，创建后不可变；batchKey 为 null 表示单笔核销。 */
    public record EmergencyWriteOffRow(long id, String writeOffKey, long windowId, String emergencyId,
                                       String approver, BigDecimal amount, String batchKey,
                                       long createdNanos) {
    }

    /** 储备调整历史快照行。 */
    public record ReserveHistoryRow(long id, String reserveKey, long windowId, String actor,
                                    BigDecimal oldVolume, BigDecimal newVolume, int version,
                                    long createdNanos) {
    }

    private static final String WINDOW_SELECT =
            "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, reserve_volume,"
                    + " reserve_used, regular_used, version, closed_nanos, created_nanos";

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getBigDecimal("reserve_volume"),
            rs.getBigDecimal("reserve_used"), rs.getBigDecimal("regular_used"),
            rs.getInt("version"),
            rs.getObject("closed_nanos") == null ? null : rs.getLong("closed_nanos"),
            rs.getLong("created_nanos"));

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
            return jdbc.queryForObject(WINDOW_SELECT + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、限供、储备调整、核销与窗口关闭。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(WINDOW_SELECT + " FROM supply_window WHERE id = ? FOR UPDATE",
                    WINDOW_MAPPER, id);
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
    // 应急储备与核销
    // ------------------------------------------------------------------

    private static final RowMapper<RegularWriteOffRow> REGULAR_WRITE_OFF_MAPPER =
            (rs, n) -> new RegularWriteOffRow(
                    rs.getLong("id"), rs.getString("write_off_key"), rs.getLong("window_id"),
                    rs.getBigDecimal("amount"), rs.getLong("created_nanos"));

    private static final RowMapper<EmergencyWriteOffRow> EMERGENCY_WRITE_OFF_MAPPER =
            (rs, n) -> new EmergencyWriteOffRow(
                    rs.getLong("id"), rs.getString("write_off_key"), rs.getLong("window_id"),
                    rs.getString("emergency_id"), rs.getString("approver"), rs.getBigDecimal("amount"),
                    rs.getString("batch_key"), rs.getLong("created_nanos"));

    private static final RowMapper<ReserveHistoryRow> RESERVE_HISTORY_MAPPER = (rs, n) ->
            new ReserveHistoryRow(
                    rs.getLong("id"), rs.getString("reserve_key"), rs.getLong("window_id"),
                    rs.getString("actor"), rs.getBigDecimal("old_volume"), rs.getBigDecimal("new_volume"),
                    rs.getInt("version"), rs.getLong("created_nanos"));

    private static final String EMERGENCY_WRITE_OFF_SELECT =
            "SELECT id, write_off_key, window_id, emergency_id, approver, amount, batch_key, created_nanos";

    /** 调整窗口储备量并递增版本号（调用方已在窗口行锁内校验 expectedVersion）。 */
    public void updateReserve(long windowId, BigDecimal newVolume, int newVersion) {
        jdbc.update("UPDATE supply_window SET reserve_volume = ?, version = ? WHERE id = ?",
                newVolume, newVersion, windowId);
    }

    /** 累加窗口常规核销量（不得侵占储备由事务内校验保证）。 */
    public void addRegularUsed(long windowId, BigDecimal delta) {
        jdbc.update("UPDATE supply_window SET regular_used = regular_used + ? WHERE id = ?",
                delta, windowId);
    }

    /** 累加窗口应急核销量（不超过储备量由事务内校验与 CHECK 约束保证）。 */
    public void addReserveUsed(long windowId, BigDecimal delta) {
        jdbc.update("UPDATE supply_window SET reserve_used = reserve_used + ? WHERE id = ?",
                delta, windowId);
    }

    /** 关闭窗口，记录关闭时间。 */
    public void closeWindow(long windowId, long closedNanos) {
        jdbc.update("UPDATE supply_window SET closed_nanos = ? WHERE id = ?", closedNanos, windowId);
    }

    /** 插入常规核销流水并返回主键。 */
    public long insertRegularWriteOff(String writeOffKey, long windowId, BigDecimal amount, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO regular_write_off (write_off_key, window_id, amount, created_nanos)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeOffKey);
            ps.setLong(2, windowId);
            ps.setBigDecimal(3, amount);
            ps.setLong(4, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询常规核销，不存在返回 null。 */
    public RegularWriteOffRow findRegularWriteOffByKey(String writeOffKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, write_off_key, window_id, amount, created_nanos"
                            + " FROM regular_write_off WHERE write_off_key = ?",
                    REGULAR_WRITE_OFF_MAPPER, writeOffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部常规核销流水，按主键升序。 */
    public List<RegularWriteOffRow> listRegularWriteOffs(long windowId) {
        return jdbc.query(
                "SELECT id, write_off_key, window_id, amount, created_nanos"
                        + " FROM regular_write_off WHERE window_id = ? ORDER BY id",
                REGULAR_WRITE_OFF_MAPPER, windowId);
    }

    /** 插入应急核销流水并返回主键；同窗口同 emergencyId 由唯一约束拒绝。 */
    public long insertEmergencyWriteOff(String writeOffKey, long windowId, String emergencyId, String approver,
                                        BigDecimal amount, String batchKey, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO emergency_write_off (write_off_key, window_id, emergency_id, approver,"
                            + " amount, batch_key, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeOffKey);
            ps.setLong(2, windowId);
            ps.setString(3, emergencyId);
            ps.setString(4, approver);
            ps.setBigDecimal(5, amount);
            ps.setString(6, batchKey);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询应急核销，不存在返回 null。 */
    public EmergencyWriteOffRow findEmergencyWriteOffByKey(String writeOffKey) {
        try {
            return jdbc.queryForObject(
                    EMERGENCY_WRITE_OFF_SELECT + " FROM emergency_write_off WHERE write_off_key = ?",
                    EMERGENCY_WRITE_OFF_MAPPER, writeOffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 查询窗口内指定应急编号的核销记录，不存在返回 null（同一窗口同一应急编号至多一条）。 */
    public EmergencyWriteOffRow findEmergencyWriteOffByEmergencyId(long windowId, String emergencyId) {
        try {
            return jdbc.queryForObject(
                    EMERGENCY_WRITE_OFF_SELECT
                            + " FROM emergency_write_off WHERE window_id = ? AND emergency_id = ?",
                    EMERGENCY_WRITE_OFF_MAPPER, windowId, emergencyId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部应急核销流水，按主键升序。 */
    public List<EmergencyWriteOffRow> listEmergencyWriteOffs(long windowId) {
        return jdbc.query(EMERGENCY_WRITE_OFF_SELECT + " FROM emergency_write_off WHERE window_id = ? ORDER BY id",
                EMERGENCY_WRITE_OFF_MAPPER, windowId);
    }

    /** 插入储备调整历史快照并返回主键。 */
    public long insertReserveHistory(String reserveKey, long windowId, String actor, BigDecimal oldVolume,
                                     BigDecimal newVolume, int version, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reserve_history (reserve_key, window_id, actor, old_volume, new_volume,"
                            + " version, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reserveKey);
            ps.setLong(2, windowId);
            ps.setString(3, actor);
            ps.setBigDecimal(4, oldVolume);
            ps.setBigDecimal(5, newVolume);
            ps.setInt(6, version);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 窗口全部储备调整历史快照，按主键升序；窗口结束后保留。 */
    public List<ReserveHistoryRow> listReserveHistory(long windowId) {
        return jdbc.query(
                "SELECT id, reserve_key, window_id, actor, old_volume, new_volume, version, created_nanos"
                        + " FROM reserve_history WHERE window_id = ? ORDER BY id",
                RESERVE_HISTORY_MAPPER, windowId);
    }
}
