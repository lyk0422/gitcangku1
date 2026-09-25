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

    /** 核销记录行（原流水），创建后不可变；version 为同一申请内自 1 递增的核销版本。 */
    public record WriteoffRow(long id, String writeoffKey, String allocationKey, long windowId, int version,
                              BigDecimal amount, long meterNanos, String actor, long createdNanos) {
    }

    /** 计量更正行；fingerprint 为 核销键|原核销版本|校正数|读表时刻|原因|操作者。 */
    public record CorrectionRow(long id, String meterKey, String fingerprint, String writeoffKey,
                                String allocationKey, long windowId, int originalVersion,
                                BigDecimal correctedAmount, long meterNanos, String reason, String actor,
                                String status, long createdNanos, Long decidedNanos) {
    }

    /** 结算流水行，创建后不可变；delta 为对持有额度的有符号影响。 */
    public record LedgerEntryRow(long id, long windowId, String allocationKey, String kind, String refKey,
                                 BigDecimal delta, BigDecimal balanceAfter, long eventNanos,
                                 long createdNanos) {
    }

    /** 拒绝原因日志行。 */
    public record RejectionRow(long id, Long windowId, String meterKey, String operation, String code,
                               String message, long createdNanos) {
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

    private static final RowMapper<WriteoffRow> WRITEOFF_MAPPER = (rs, n) -> new WriteoffRow(
            rs.getLong("id"), rs.getString("writeoff_key"), rs.getString("allocation_key"),
            rs.getLong("window_id"), rs.getInt("version"), rs.getBigDecimal("amount"),
            rs.getLong("meter_nanos"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final String WRITEOFF_SELECT =
            "SELECT id, writeoff_key, allocation_key, window_id, version, amount, meter_nanos, actor,"
                    + " created_nanos";

    private static final RowMapper<CorrectionRow> CORRECTION_MAPPER = (rs, n) -> new CorrectionRow(
            rs.getLong("id"), rs.getString("meter_key"), rs.getString("fingerprint"),
            rs.getString("writeoff_key"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getInt("original_version"), rs.getBigDecimal("corrected_amount"),
            rs.getLong("meter_nanos"), rs.getString("reason"), rs.getString("actor"),
            rs.getString("status"), rs.getLong("created_nanos"),
            rs.getObject("decided_nanos") == null ? null : rs.getLong("decided_nanos"));

    private static final String CORRECTION_SELECT =
            "SELECT id, meter_key, fingerprint, writeoff_key, allocation_key, window_id, original_version,"
                    + " corrected_amount, meter_nanos, reason, actor, status, created_nanos, decided_nanos";

    private static final RowMapper<LedgerEntryRow> LEDGER_MAPPER = (rs, n) -> new LedgerEntryRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getString("allocation_key"),
            rs.getString("kind"), rs.getString("ref_key"), rs.getBigDecimal("delta"),
            rs.getBigDecimal("balance_after"), rs.getLong("event_nanos"), rs.getLong("created_nanos"));

    private static final String LEDGER_SELECT =
            "SELECT id, window_id, allocation_key, kind, ref_key, delta, balance_after, event_nanos,"
                    + " created_nanos";

    private static final RowMapper<RejectionRow> REJECTION_MAPPER = (rs, n) -> new RejectionRow(
            rs.getLong("id"), rs.getObject("window_id") == null ? null : rs.getLong("window_id"),
            rs.getString("meter_key"), rs.getString("operation"), rs.getString("code"),
            rs.getString("message"), rs.getLong("created_nanos"));

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
    // 核销（原流水）
    // ------------------------------------------------------------------

    /** 插入不可变核销记录并返回主键；version 由服务层在申请行锁内计算。 */
    public long insertWriteoff(String writeoffKey, String allocationKey, long windowId, int version,
                               BigDecimal amount, long meterNanos, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO write_off (writeoff_key, allocation_key, window_id, version, amount,"
                            + " meter_nanos, actor, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setString(2, allocationKey);
            ps.setLong(3, windowId);
            ps.setInt(4, version);
            ps.setBigDecimal(5, amount);
            ps.setLong(6, meterNanos);
            ps.setString(7, actor);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销记录，不存在返回 null。 */
    public WriteoffRow findWriteoffByKey(String writeoffKey) {
        try {
            return jdbc.queryForObject(WRITEOFF_SELECT + " FROM write_off WHERE writeoff_key = ?",
                    WRITEOFF_MAPPER, writeoffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请全部核销记录，按版本升序。 */
    public List<WriteoffRow> listWriteoffsByAllocation(String allocationKey) {
        return jdbc.query(WRITEOFF_SELECT + " FROM write_off WHERE allocation_key = ? ORDER BY version",
                WRITEOFF_MAPPER, allocationKey);
    }

    /** 申请下一个核销版本（须在申请行锁内调用）。 */
    public int nextWriteoffVersion(String allocationKey) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM write_off WHERE allocation_key = ?",
                Integer.class, allocationKey);
        return next == null ? 1 : next;
    }

    /** 直接设定申请持有额度（更正/撤销重算后的最终余额）并记录变更时间。 */
    public void setHeldAmount(long id, BigDecimal heldAmount, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = ?, updated_nanos = ? WHERE id = ?",
                heldAmount, updatedNanos, id);
    }

    /** 源申请的全部转出流水（已结算转让），按主键升序。 */
    public List<TransferRow> listTransfersBySource(String sourceAllocationKey) {
        return jdbc.query(
                "SELECT id, transfer_key, window_id, source_allocation_key, target_allocation_key,"
                        + " amount, actor, created_nanos FROM transfer WHERE source_allocation_key = ?"
                        + " ORDER BY id",
                TRANSFER_MAPPER, sourceAllocationKey);
    }

    // ------------------------------------------------------------------
    // 计量更正
    // ------------------------------------------------------------------

    /** 插入更正申请（初始 REQUESTED）并返回主键。 */
    public long insertCorrection(String meterKey, String fingerprint, String writeoffKey,
                                 String allocationKey, long windowId, int originalVersion,
                                 BigDecimal correctedAmount, long meterNanos, String reason, String actor,
                                 long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_correction (meter_key, fingerprint, writeoff_key, allocation_key,"
                            + " window_id, original_version, corrected_amount, meter_nanos, reason, actor,"
                            + " status, created_nanos, decided_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, meterKey);
            ps.setString(2, fingerprint);
            ps.setString(3, writeoffKey);
            ps.setString(4, allocationKey);
            ps.setLong(5, windowId);
            ps.setInt(6, originalVersion);
            ps.setBigDecimal(7, correctedAmount);
            ps.setLong(8, meterNanos);
            ps.setString(9, reason);
            ps.setString(10, actor);
            ps.setLong(11, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询更正，不存在返回 null。 */
    public CorrectionRow findCorrectionByKey(String meterKey) {
        try {
            return jdbc.queryForObject(CORRECTION_SELECT + " FROM meter_correction WHERE meter_key = ?",
                    CORRECTION_MAPPER, meterKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定更正行（FOR UPDATE），不存在返回 null。 */
    public CorrectionRow lockCorrectionByKey(String meterKey) {
        try {
            return jdbc.queryForObject(
                    CORRECTION_SELECT + " FROM meter_correction WHERE meter_key = ? FOR UPDATE",
                    CORRECTION_MAPPER, meterKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请全部已批准（未撤销）更正，按裁决时间、主键升序。 */
    public List<CorrectionRow> listApprovedCorrections(String allocationKey) {
        return jdbc.query(
                CORRECTION_SELECT + " FROM meter_correction WHERE allocation_key = ? AND status = 'APPROVED'"
                        + " ORDER BY decided_nanos, id",
                CORRECTION_MAPPER, allocationKey);
    }

    /** 更新更正状态（APPROVED/REVOKED）并记录裁决时间。 */
    public void updateCorrectionStatus(long id, String status, long decidedNanos) {
        jdbc.update("UPDATE meter_correction SET status = ?, decided_nanos = ? WHERE id = ?",
                status, decidedNanos, id);
    }

    // ------------------------------------------------------------------
    // 结算流水 / 读表快照 / 拒绝原因
    // ------------------------------------------------------------------

    /** 插入不可变结算流水并返回主键。 */
    public long insertLedgerEntry(long windowId, String allocationKey, String kind, String refKey,
                                  BigDecimal delta, BigDecimal balanceAfter, long eventNanos,
                                  long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ledger_entry (window_id, allocation_key, kind, ref_key, delta,"
                            + " balance_after, event_nanos, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, windowId);
            ps.setString(2, allocationKey);
            ps.setString(3, kind);
            ps.setString(4, refKey);
            ps.setBigDecimal(5, delta);
            ps.setBigDecimal(6, balanceAfter);
            ps.setLong(7, eventNanos);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 申请全部结算流水，按主键升序。 */
    public List<LedgerEntryRow> listLedgerEntries(String allocationKey) {
        return jdbc.query(LEDGER_SELECT + " FROM ledger_entry WHERE allocation_key = ? ORDER BY id",
                LEDGER_MAPPER, allocationKey);
    }

    /** 插入不可变读表快照并返回主键。 */
    public long insertSnapshot(String meterKey, String writeoffKey, long windowId, int originalVersion,
                               BigDecimal correctedAmount, long meterNanos, String reason, String actor,
                               long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_snapshot (meter_key, writeoff_key, window_id, original_version,"
                            + " corrected_amount, meter_nanos, reason, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, meterKey);
            ps.setString(2, writeoffKey);
            ps.setLong(3, windowId);
            ps.setInt(4, originalVersion);
            ps.setBigDecimal(5, correctedAmount);
            ps.setLong(6, meterNanos);
            ps.setString(7, reason);
            ps.setString(8, actor);
            ps.setLong(9, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 窗口全部读表快照，按主键升序。 */
    public List<Long> listSnapshotIds(long windowId) {
        return jdbc.query("SELECT id FROM meter_snapshot WHERE window_id = ? ORDER BY id",
                (rs, n) -> rs.getLong("id"), windowId);
    }

    /** 插入拒绝原因日志（独立事务，业务失败回滚不影响本日志）。 */
    public void insertRejection(Long windowId, String meterKey, String operation, String code,
                                String message, long createdNanos) {
        jdbc.update("INSERT INTO rejection_log (window_id, meter_key, operation, code, message,"
                        + " created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                windowId, meterKey, operation, code, message, createdNanos);
    }

    /** 窗口全部拒绝原因日志，按主键升序。 */
    public List<RejectionRow> listRejections(long windowId) {
        return jdbc.query(
                "SELECT id, window_id, meter_key, operation, code, message, created_nanos"
                        + " FROM rejection_log WHERE window_id = ? ORDER BY id",
                REJECTION_MAPPER, windowId);
    }
}
