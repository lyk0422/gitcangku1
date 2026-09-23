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
import java.util.Collection;
import java.util.Collections;
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

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，version 为当前额度版本。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status, long version,
                                long createdNanos, long updatedNanos) {
    }

    /** 转让流水行，创建后不可变。 */
    public record TransferRow(long id, String transferKey, long windowId, String sourceAllocationKey,
                              String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 清算批次行，成功提交后不可变。 */
    public record SettlementBatchRow(long id, String settlementKey, String requestId, String commandKey,
                                     long windowId, int instructionCount, long createdNanos) {
    }

    /** 清算指令行，按输入顺序不可变保留。 */
    public record SettlementInstructionRow(long id, long batchId, int seqNo, String instructionKey,
                                           String fromAllocationKey, String toAllocationKey,
                                           BigDecimal volume, long createdNanos) {
    }

    /** 清算主体净额快照行，不可变；净额为 0 的主体同样保留一行。 */
    public record SettlementSnapshotRow(long id, long batchId, String allocationKey, BigDecimal netChange,
                                        BigDecimal balanceBefore, BigDecimal balanceAfter, long versionBefore,
                                        long versionAfter) {
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
            rs.getString("requester"), rs.getString("status"), rs.getLong("version"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status, version,"
                    + " created_nanos, updated_nanos";

    private static final RowMapper<SettlementBatchRow> SETTLEMENT_BATCH_MAPPER = (rs, n) -> new SettlementBatchRow(
            rs.getLong("id"), rs.getString("settlement_key"), rs.getString("request_id"),
            rs.getString("command_key"), rs.getLong("window_id"), rs.getInt("instruction_count"),
            rs.getLong("created_nanos"));

    private static final RowMapper<SettlementInstructionRow> SETTLEMENT_INSTRUCTION_MAPPER = (rs, n) ->
            new SettlementInstructionRow(
                    rs.getLong("id"), rs.getLong("batch_id"), rs.getInt("seq_no"),
                    rs.getString("instruction_key"), rs.getString("from_allocation_key"),
                    rs.getString("to_allocation_key"), rs.getBigDecimal("volume"),
                    rs.getLong("created_nanos"));

    private static final RowMapper<SettlementSnapshotRow> SETTLEMENT_SNAPSHOT_MAPPER = (rs, n) ->
            new SettlementSnapshotRow(
                    rs.getLong("id"), rs.getLong("batch_id"), rs.getString("allocation_key"),
                    rs.getBigDecimal("net_change"), rs.getBigDecimal("balance_before"),
                    rs.getBigDecimal("balance_after"), rs.getLong("version_before"),
                    rs.getLong("version_after"));

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
    // 批量净额清算
    // ------------------------------------------------------------------

    /** 插入清算批次（成功后不可变）并返回自增主键。 */
    public long insertSettlementBatch(String settlementKey, String requestId, String commandKey, long windowId,
                                      int instructionCount, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement_batch (settlement_key, request_id, command_key, window_id,"
                            + " instruction_count, created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlementKey);
            ps.setString(2, requestId);
            ps.setString(3, commandKey);
            ps.setLong(4, windowId);
            ps.setInt(5, instructionCount);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 插入一条清算原始指令（输入顺序由 seqNo 保留）。 */
    public void insertSettlementInstruction(long batchId, int seqNo, String instructionKey,
                                            String fromAllocationKey, String toAllocationKey,
                                            BigDecimal volume, long createdNanos) {
        jdbc.update("INSERT INTO settlement_instruction (batch_id, seq_no, instruction_key,"
                + " from_allocation_key, to_allocation_key, volume, created_nanos)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchId, seqNo, instructionKey, fromAllocationKey, toAllocationKey, volume, createdNanos);
    }

    /** 插入一条主体净额快照（净额为 0 的主体同样写入）。 */
    public void insertSettlementSnapshot(long batchId, String allocationKey, BigDecimal netChange,
                                         BigDecimal balanceBefore, BigDecimal balanceAfter, long versionBefore,
                                         long versionAfter) {
        jdbc.update("INSERT INTO settlement_subject_snapshot (batch_id, allocation_key, net_change,"
                + " balance_before, balance_after, version_before, version_after)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchId, allocationKey, netChange, balanceBefore, balanceAfter, versionBefore, versionAfter);
    }

    /**
     * 一次更新主体净额并递增额度版本：held_amount += netChange，version += 1。
     * 非负由事务内一致视图校验保证；CHECK 约束作为最终防线。
     */
    public void applySettlementNetChange(long allocationId, BigDecimal netChange, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount + ?, version = version + 1,"
                + " updated_nanos = ? WHERE id = ?", netChange, updatedNanos, allocationId);
    }

    /** 净额为 0 的主体余额不变，但额度版本仍加一。 */
    public void incrementAllocationVersion(long allocationId, long updatedNanos) {
        jdbc.update("UPDATE allocation SET version = version + 1, updated_nanos = ? WHERE id = ?",
                updatedNanos, allocationId);
    }

    /**
     * 按主键 id 升序锁定一组申请行（FOR UPDATE），用于清算批次在窗口锁内取得全部涉及主体的
     * 一致读视图并避免多行锁死锁；不存在的键不出现在结果中。
     */
    public List<AllocationRow> lockAllocationsByKeys(Collection<String> allocationKeys) {
        if (allocationKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(allocationKeys.size(), "?"));
        return jdbc.query(
                ALLOCATION_SELECT + " FROM allocation WHERE allocation_key IN (" + placeholders
                        + ") ORDER BY id FOR UPDATE",
                ALLOCATION_MAPPER, allocationKeys.toArray());
    }

    /** 按业务键查询清算批次，不存在返回 null。 */
    public SettlementBatchRow findSettlementByKey(String settlementKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, settlement_key, request_id, command_key, window_id, instruction_count,"
                            + " created_nanos FROM settlement_batch WHERE settlement_key = ?",
                    SETTLEMENT_BATCH_MAPPER, settlementKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 requestId 查询首个成功清算批次（成功批次 requestId 唯一），不存在返回 null。 */
    public SettlementBatchRow findSettlementByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, settlement_key, request_id, command_key, window_id, instruction_count,"
                            + " created_nanos FROM settlement_batch WHERE request_id = ? ORDER BY id LIMIT 1",
                    SETTLEMENT_BATCH_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 判断指令键是否已被某成功批次使用（失败批次整体回滚不占键）。 */
    public boolean existsSettlementInstructionKey(String instructionKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_instruction WHERE instruction_key = ?",
                Integer.class, instructionKey);
        return count != null && count > 0;
    }

    /** 批次全部原始指令，严格按输入序号升序。 */
    public List<SettlementInstructionRow> listSettlementInstructions(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, seq_no, instruction_key, from_allocation_key, to_allocation_key,"
                        + " volume, created_nanos FROM settlement_instruction WHERE batch_id = ? ORDER BY seq_no",
                SETTLEMENT_INSTRUCTION_MAPPER, batchId);
    }

    /** 批次全部主体快照，按申请键排序保证返回稳定。 */
    public List<SettlementSnapshotRow> listSettlementSnapshots(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, allocation_key, net_change, balance_before, balance_after,"
                        + " version_before, version_after FROM settlement_subject_snapshot"
                        + " WHERE batch_id = ? ORDER BY allocation_key",
                SETTLEMENT_SNAPSHOT_MAPPER, batchId);
    }

    /** 某主体参与过的全部批次快照，按批次（提交顺序）升序。 */
    public List<SettlementSnapshotRow> listSubjectSettlementHistory(String allocationKey) {
        return jdbc.query(
                "SELECT s.id, s.batch_id, s.allocation_key, s.net_change, s.balance_before, s.balance_after,"
                        + " s.version_before, s.version_after FROM settlement_subject_snapshot s"
                        + " JOIN settlement_batch b ON b.id = s.batch_id"
                        + " WHERE s.allocation_key = ? ORDER BY b.id, s.id",
                SETTLEMENT_SNAPSHOT_MAPPER, allocationKey);
    }
}
