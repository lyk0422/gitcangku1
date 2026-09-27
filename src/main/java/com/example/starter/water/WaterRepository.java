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

    /** 渠道 UTC 自然日蒸发率行；该日存在核销记录后率冻结。 */
    public record EvaporationRateRow(long id, String channelId, long dayStartNanos, BigDecimal rate,
                                     long version, long createdNanos, long updatedNanos) {
    }

    /** 核销记录行（不可变证据）；upstreamDeduction 恒等于 settledDelivered + lossVolume。 */
    public record SettlementRow(long id, String settlementKey, String batchKey, long windowId, String channelId,
                                String allocationKey, String downstreamUserId, long dayStartNanos,
                                long occurredNanos, BigDecimal deliveredVolume, BigDecimal settledDeliveredVolume,
                                BigDecimal lossVolume, BigDecimal upstreamDeduction, BigDecimal rateValue,
                                long rateVersion, String status, String actor, long createdNanos) {
    }

    /** 核销反向更正行（只追加证据）；restoredUpstreamVolume 等于原 upstreamDeduction。 */
    public record CorrectionRow(long id, String correctionKey, long originalSettlementId, String batchKey,
                                long windowId, String channelId, String allocationKey, String downstreamUserId,
                                long dayStartNanos, BigDecimal reversedDeliveredVolume,
                                BigDecimal reversedSettledDeliveredVolume, BigDecimal reversedLossVolume,
                                BigDecimal restoredUpstreamVolume, BigDecimal rateValue, long rateVersion,
                                String reason, String actor, long createdNanos) {
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

    private static final RowMapper<EvaporationRateRow> RATE_MAPPER = (rs, n) -> new EvaporationRateRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getLong("day_start_nanos"),
            rs.getBigDecimal("rate"), rs.getLong("version"), rs.getLong("created_nanos"),
            rs.getLong("updated_nanos"));

    private static final String SETTLEMENT_SELECT =
            "SELECT id, settlement_key, batch_key, window_id, channel_id, allocation_key, downstream_user_id,"
                    + " day_start_nanos, occurred_nanos, delivered_volume, settled_delivered_volume,"
                    + " loss_volume, upstream_deduction, rate_value, rate_version, status, actor, created_nanos";

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"), rs.getString("settlement_key"), rs.getString("batch_key"),
            rs.getLong("window_id"), rs.getString("channel_id"), rs.getString("allocation_key"),
            rs.getString("downstream_user_id"), rs.getLong("day_start_nanos"), rs.getLong("occurred_nanos"),
            rs.getBigDecimal("delivered_volume"), rs.getBigDecimal("settled_delivered_volume"),
            rs.getBigDecimal("loss_volume"), rs.getBigDecimal("upstream_deduction"),
            rs.getBigDecimal("rate_value"), rs.getLong("rate_version"), rs.getString("status"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private static final String CORRECTION_SELECT =
            "SELECT id, correction_key, original_settlement_id, batch_key, window_id, channel_id,"
                    + " allocation_key, downstream_user_id, day_start_nanos, reversed_delivered_volume,"
                    + " reversed_settled_delivered_volume, reversed_loss_volume, restored_upstream_volume,"
                    + " rate_value, rate_version, reason, actor, created_nanos";

    private static final RowMapper<CorrectionRow> CORRECTION_MAPPER = (rs, n) -> new CorrectionRow(
            rs.getLong("id"), rs.getString("correction_key"), rs.getLong("original_settlement_id"),
            rs.getString("batch_key"), rs.getLong("window_id"), rs.getString("channel_id"),
            rs.getString("allocation_key"), rs.getString("downstream_user_id"),
            rs.getLong("day_start_nanos"), rs.getBigDecimal("reversed_delivered_volume"),
            rs.getBigDecimal("reversed_settled_delivered_volume"), rs.getBigDecimal("reversed_loss_volume"),
            rs.getBigDecimal("restored_upstream_volume"), rs.getBigDecimal("rate_value"),
            rs.getLong("rate_version"), rs.getString("reason"), rs.getString("actor"),
            rs.getLong("created_nanos"));

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
    // 渠道日蒸发率
    // ------------------------------------------------------------------

    /** 首次登记渠道 UTC 自然日蒸发率（版本 1）并返回主键。 */
    public long insertRate(String channelId, long dayStartNanos, BigDecimal rate, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO evaporation_rate (channel_id, day_start_nanos, rate, version,"
                            + " created_nanos, updated_nanos) VALUES (?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, dayStartNanos);
            ps.setBigDecimal(3, rate);
            ps.setLong(4, createdNanos);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按渠道与 UTC 自然日查询蒸发率，不存在返回 null。 */
    public EvaporationRateRow findRate(String channelId, long dayStartNanos) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, channel_id, day_start_nanos, rate, version, created_nanos, updated_nanos"
                            + " FROM evaporation_rate WHERE channel_id = ? AND day_start_nanos = ?",
                    RATE_MAPPER, channelId, dayStartNanos);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定渠道日蒸发率行（FOR UPDATE），用于与当日核销串行裁决，不存在返回 null。 */
    public EvaporationRateRow lockRate(String channelId, long dayStartNanos) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, channel_id, day_start_nanos, rate, version, created_nanos, updated_nanos"
                            + " FROM evaporation_rate WHERE channel_id = ? AND day_start_nanos = ? FOR UPDATE",
                    RATE_MAPPER, channelId, dayStartNanos);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 当日首次核销前修改率值，版本号加一。 */
    public void updateRate(long id, BigDecimal rate, long updatedNanos) {
        jdbc.update("UPDATE evaporation_rate SET rate = ?, version = version + 1, updated_nanos = ? WHERE id = ?",
                rate, updatedNanos, id);
    }

    /** 渠道全部日蒸发率，按 UTC 日升序。 */
    public List<EvaporationRateRow> listRates(String channelId) {
        return jdbc.query(
                "SELECT id, channel_id, day_start_nanos, rate, version, created_nanos, updated_nanos"
                        + " FROM evaporation_rate WHERE channel_id = ? ORDER BY day_start_nanos",
                RATE_MAPPER, channelId);
    }

    /** 该渠道当日首笔核销的结算时间（UTC 纳秒），无核销返回 null；用于判断率是否已冻结。 */
    public Long firstSettlementNanos(String channelId, long dayStartNanos) {
        return jdbc.query(
                        "SELECT MIN(created_nanos) FROM settlement WHERE channel_id = ? AND day_start_nanos = ?",
                        rs -> rs.next() ? (rs.getObject(1) == null ? null : rs.getLong(1)) : null,
                        channelId, dayStartNanos);
    }

    // ------------------------------------------------------------------
    // 送达核销
    // ------------------------------------------------------------------

    /** 插入不可变核销记录并返回主键；上游扣减、送达、损耗的守恒由 CHECK 约束兜底。 */
    public long insertSettlement(String settlementKey, String batchKey, long windowId, String channelId,
                                 String allocationKey, String downstreamUserId, long dayStartNanos,
                                 long occurredNanos, BigDecimal delivered, BigDecimal settledDelivered,
                                 BigDecimal loss, BigDecimal deduction, BigDecimal rate, long rateVersion,
                                 String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement (settlement_key, batch_key, window_id, channel_id, allocation_key,"
                            + " downstream_user_id, day_start_nanos, occurred_nanos, delivered_volume,"
                            + " settled_delivered_volume, loss_volume, upstream_deduction, rate_value,"
                            + " rate_version, status, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlementKey);
            ps.setString(2, batchKey);
            ps.setLong(3, windowId);
            ps.setString(4, channelId);
            ps.setString(5, allocationKey);
            ps.setString(6, downstreamUserId);
            ps.setLong(7, dayStartNanos);
            ps.setLong(8, occurredNanos);
            ps.setBigDecimal(9, delivered);
            ps.setBigDecimal(10, settledDelivered);
            ps.setBigDecimal(11, loss);
            ps.setBigDecimal(12, deduction);
            ps.setBigDecimal(13, rate);
            ps.setLong(14, rateVersion);
            ps.setString(15, actor);
            ps.setLong(16, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销记录，不存在返回 null。 */
    public SettlementRow findSettlementByKey(String settlementKey) {
        try {
            return jdbc.queryForObject(
                    SETTLEMENT_SELECT + " FROM settlement WHERE settlement_key = ?",
                    SETTLEMENT_MAPPER, settlementKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定核销记录（FOR UPDATE），用于反向更正串行化。 */
    public SettlementRow lockSettlementById(long id) {
        try {
            return jdbc.queryForObject(SETTLEMENT_SELECT + " FROM settlement WHERE id = ? FOR UPDATE",
                    SETTLEMENT_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部核销记录，按主键升序（历史顺序稳定，旧证据不改写）。 */
    public List<SettlementRow> listSettlementsByWindow(long windowId) {
        return jdbc.query(SETTLEMENT_SELECT + " FROM settlement WHERE window_id = ? ORDER BY id",
                SETTLEMENT_MAPPER, windowId);
    }

    /** 占用批量核销业务键（唯一约束兜底跨 commandKey 复用），随批量事务提交或回滚。 */
    public void insertBatchKey(String batchKey, long windowId, long createdNanos) {
        jdbc.update("INSERT INTO settlement_batch (batch_key, window_id, created_nanos) VALUES (?, ?, ?)",
                batchKey, windowId, createdNanos);
    }

    /** 指定上游申请的全部核销记录，按主键升序。 */
    public List<SettlementRow> listSettlementsByAllocation(String allocationKey) {
        return jdbc.query(SETTLEMENT_SELECT + " FROM settlement WHERE allocation_key = ? ORDER BY id",
                SETTLEMENT_MAPPER, allocationKey);
    }

    // ------------------------------------------------------------------
    // 核销反向更正
    // ------------------------------------------------------------------

    /** 插入只追加的反向更正记录并返回主键；唯一约束保证一笔原核销至多一条更正。 */
    public long insertCorrection(String correctionKey, long originalSettlementId, String batchKey,
                                 long windowId, String channelId, String allocationKey,
                                 String downstreamUserId, long dayStartNanos, BigDecimal reversedDelivered,
                                 BigDecimal reversedSettledDelivered, BigDecimal reversedLoss,
                                 BigDecimal restoredUpstream, BigDecimal rate, long rateVersion,
                                 String reason, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement_correction (correction_key, original_settlement_id, batch_key,"
                            + " window_id, channel_id, allocation_key, downstream_user_id, day_start_nanos,"
                            + " reversed_delivered_volume, reversed_settled_delivered_volume,"
                            + " reversed_loss_volume, restored_upstream_volume, rate_value, rate_version,"
                            + " reason, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, correctionKey);
            ps.setLong(2, originalSettlementId);
            ps.setString(3, batchKey);
            ps.setLong(4, windowId);
            ps.setString(5, channelId);
            ps.setString(6, allocationKey);
            ps.setString(7, downstreamUserId);
            ps.setLong(8, dayStartNanos);
            ps.setBigDecimal(9, reversedDelivered);
            ps.setBigDecimal(10, reversedSettledDelivered);
            ps.setBigDecimal(11, reversedLoss);
            ps.setBigDecimal(12, restoredUpstream);
            ps.setBigDecimal(13, rate);
            ps.setLong(14, rateVersion);
            ps.setString(15, reason);
            ps.setString(16, actor);
            ps.setLong(17, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询更正记录，不存在返回 null。 */
    public CorrectionRow findCorrectionByKey(String correctionKey) {
        try {
            return jdbc.queryForObject(CORRECTION_SELECT + " FROM settlement_correction WHERE correction_key = ?",
                    CORRECTION_MAPPER, correctionKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }

    }

    /** 按原核销主键查询更正记录，不存在返回 null。 */
    public CorrectionRow findCorrectionByOriginalSettlement(long originalSettlementId) {
        try {
            return jdbc.queryForObject(
                    CORRECTION_SELECT + " FROM settlement_correction WHERE original_settlement_id = ?",
                    CORRECTION_MAPPER, originalSettlementId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部更正记录，按主键升序。 */
    public List<CorrectionRow> listCorrectionsByWindow(long windowId) {
        return jdbc.query(CORRECTION_SELECT + " FROM settlement_correction WHERE window_id = ? ORDER BY id",
                CORRECTION_MAPPER, windowId);
    }

    /** 更正回补上游持有额度（不得超过原申请水量由事务内校验保证）并记录变更时间。 */
    public void incrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount + ?, updated_nanos = ? WHERE id = ?",
                delta, updatedNanos, id);
    }
}
