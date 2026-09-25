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
                            BigDecimal plannedVolume, BigDecimal reserveVolume, long version, String status,
                            Long closedNanos, long createdNanos) {
    }

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，
     * regularWrittenOff 为常规核销累计量。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, BigDecimal regularWrittenOff, String requester,
                                String status, long createdNanos, long updatedNanos) {
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
    public record RegularWriteoffRow(long id, String writeoffKey, long windowId, String allocationKey,
                                     BigDecimal amount, String actor, long createdNanos) {
    }

    /** 应急核销流水行，创建后不可变；reserveSnapshot 为核销时储备量快照。 */
    public record EmergencyWriteoffRow(long id, String writeoffKey, long windowId, String emergencyId,
                                       String approver, String actor, BigDecimal amount,
                                       BigDecimal reserveSnapshot, long createdNanos) {
    }

    /** reserveKey 指纹行；response 为 null 表示尚未成功提交（失败事务回滚不占键）。 */
    public record ReserveCommandRow(String reserveKey, String operation, String fingerprint, String response,
                                    long createdNanos) {
    }

    private static final String WINDOW_COLUMNS =
            "id, window_key, channel_id, start_nanos, end_nanos, planned_volume, reserve_volume, version,"
                    + " status, closed_nanos, created_nanos";

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getBigDecimal("reserve_volume"),
            rs.getLong("version"), rs.getString("status"),
            rs.getObject("closed_nanos") == null ? null : rs.getLong("closed_nanos"),
            rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getBigDecimal("regular_written_off"), rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, regular_written_off,"
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

    private static final RowMapper<RegularWriteoffRow> REGULAR_WRITEOFF_MAPPER = (rs, n) ->
            new RegularWriteoffRow(rs.getLong("id"), rs.getString("writeoff_key"), rs.getLong("window_id"),
                    rs.getString("allocation_key"), rs.getBigDecimal("amount"), rs.getString("actor"),
                    rs.getLong("created_nanos"));

    private static final RowMapper<EmergencyWriteoffRow> EMERGENCY_WRITEOFF_MAPPER = (rs, n) ->
            new EmergencyWriteoffRow(rs.getLong("id"), rs.getString("writeoff_key"), rs.getLong("window_id"),
                    rs.getString("emergency_id"), rs.getString("approver"), rs.getString("actor"),
                    rs.getBigDecimal("amount"), rs.getBigDecimal("reserve_snapshot"),
                    rs.getLong("created_nanos"));

    private static final RowMapper<ReserveCommandRow> RESERVE_COMMAND_MAPPER = (rs, n) ->
            new ReserveCommandRow(rs.getString("reserve_key"), rs.getString("operation"),
                    rs.getString("fingerprint"), rs.getString("response"), rs.getLong("created_nanos"));

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

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、转让、限供、储备与核销裁决。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + WINDOW_COLUMNS + " FROM supply_window WHERE id = ? FOR UPDATE",
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
    // 应急储备、常规/应急核销、reserveKey 指纹
    // ------------------------------------------------------------------

    /** 调整窗口储备量（版本自增由 {@link #bumpWindowVersion} 单独完成）。 */
    public void updateReserveVolume(long windowId, BigDecimal reserveVolume) {
        jdbc.update("UPDATE supply_window SET reserve_volume = ? WHERE id = ?", reserveVolume, windowId);
    }

    /** 窗口聚合版本自增，每次储备相关写操作在窗口锁内调用。 */
    public void bumpWindowVersion(long windowId) {
        jdbc.update("UPDATE supply_window SET version = version + 1 WHERE id = ?", windowId);
    }

    /** 关闭窗口并记录关闭时刻。 */
    public void closeWindow(long windowId, long closedNanos) {
        jdbc.update("UPDATE supply_window SET status = 'CLOSED', closed_nanos = ? WHERE id = ?",
                closedNanos, windowId);
    }

    /** 窗口全部 APPROVED 申请的常规核销累计量，无则 0。 */
    public BigDecimal sumRegularWrittenOff(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(regular_written_off), 0) FROM allocation WHERE window_id = ?",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 窗口应急核销累计量，无则 0。 */
    public BigDecimal sumEmergencyWrittenOff(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM emergency_writeoff WHERE window_id = ?",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 常规核销：申请持有额度等额扣减，常规核销累计量等额累加，并记录变更时间。 */
    public void applyRegularWriteoff(long allocationId, BigDecimal amount, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?,"
                + " regular_written_off = regular_written_off + ?, updated_nanos = ? WHERE id = ?",
                amount, amount, updatedNanos, allocationId);
    }

    /** 插入常规核销流水并返回主键，业务键唯一冲突由上层捕获。 */
    public long insertRegularWriteoff(String writeoffKey, long windowId, String allocationKey,
                                      BigDecimal amount, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO regular_writeoff (writeoff_key, window_id, allocation_key, amount, actor,"
                            + " created_nanos) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setLong(2, windowId);
            ps.setString(3, allocationKey);
            ps.setBigDecimal(4, amount);
            ps.setString(5, actor);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询常规核销流水，不存在返回 null。 */
    public RegularWriteoffRow findRegularWriteoffByKey(String writeoffKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, writeoff_key, window_id, allocation_key, amount, actor, created_nanos"
                            + " FROM regular_writeoff WHERE writeoff_key = ?",
                    REGULAR_WRITEOFF_MAPPER, writeoffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入应急核销流水并返回主键；(window_id, emergency_id) 与业务键唯一冲突由上层捕获。 */
    public long insertEmergencyWriteoff(String writeoffKey, long windowId, String emergencyId, String approver,
                                        String actor, BigDecimal amount, BigDecimal reserveSnapshot,
                                        long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO emergency_writeoff (writeoff_key, window_id, emergency_id, approver, actor,"
                            + " amount, reserve_snapshot, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setLong(2, windowId);
            ps.setString(3, emergencyId);
            ps.setString(4, approver);
            ps.setString(5, actor);
            ps.setBigDecimal(6, amount);
            ps.setBigDecimal(7, reserveSnapshot);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按窗口与应急编号锁定查询应急核销流水（FOR UPDATE），不存在返回 null。 */
    public EmergencyWriteoffRow lockEmergencyWriteoff(long windowId, String emergencyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, writeoff_key, window_id, emergency_id, approver, actor, amount,"
                            + " reserve_snapshot, created_nanos FROM emergency_writeoff"
                            + " WHERE window_id = ? AND emergency_id = ? FOR UPDATE",
                    EMERGENCY_WRITEOFF_MAPPER, windowId, emergencyId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询应急核销流水，不存在返回 null。 */
    public EmergencyWriteoffRow findEmergencyWriteoffById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, writeoff_key, window_id, emergency_id, approver, actor, amount,"
                            + " reserve_snapshot, created_nanos FROM emergency_writeoff WHERE id = ?",
                    EMERGENCY_WRITEOFF_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部应急核销流水，按主键升序。 */
    public List<EmergencyWriteoffRow> listEmergencyWriteoffs(long windowId) {
        return jdbc.query(
                "SELECT id, writeoff_key, window_id, emergency_id, approver, actor, amount,"
                        + " reserve_snapshot, created_nanos FROM emergency_writeoff WHERE window_id = ? ORDER BY id",
                EMERGENCY_WRITEOFF_MAPPER, windowId);
    }

    /** 按 reserveKey 查询储备命令指纹，不存在返回 null。 */
    public ReserveCommandRow findReserveCommand(String reserveKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT reserve_key, operation, fingerprint, response, created_nanos FROM reserve_command"
                            + " WHERE reserve_key = ?", RESERVE_COMMAND_MAPPER, reserveKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 先占位插入 reserveKey 指纹（响应为空），同事务业务成功后写回快照。 */
    public void insertReserveCommand(String reserveKey, String operation, String fingerprint, long createdNanos) {
        jdbc.update("INSERT INTO reserve_command (reserve_key, operation, fingerprint, response, created_nanos)"
                + " VALUES (?, ?, ?, NULL, ?)", reserveKey, operation, fingerprint, createdNanos);
    }

    /** 写回储备命令首次成功快照。 */
    public void updateReserveCommandResponse(String reserveKey, String response) {
        jdbc.update("UPDATE reserve_command SET response = ? WHERE reserve_key = ?", response, reserveKey);
    }
}
