package com.example.starter.water;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
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

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，
     * settledDelivered/settledLoss 为仍有效核销的累计送达与损耗（更正时冲回）。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, BigDecimal settledDelivered, BigDecimal settledLoss,
                                String requester, String status,
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

    /** 渠道日蒸发率版本行；superseded=false 为当日当前版本，历史版本保留为证据。 */
    public record EvaporationRateRow(long id, String channelId, LocalDate rateDate, BigDecimal rate,
                                     int version, boolean superseded, long createdNanos) {
    }

    /** 核销批次行；单笔核销也归属一个批次。 */
    public record SettlementBatchRow(long id, String batchKey, long windowId, String status, int itemCount,
                                     long createdNanos) {
    }

    /** 核销凭证行，创建后数量字段不可变；REVERSED 表示已被反向更正，证据仍保留。 */
    public record SettlementRow(long id, String settlementKey, long batchId, String batchKey, long windowId,
                                String channelId, long allocationId, String allocationKey, long rateId,
                                int rateVersion, LocalDate rateDate, BigDecimal rateValue,
                                BigDecimal rawDelivered, BigDecimal loss, BigDecimal upstreamDeduction,
                                String status, long createdNanos) {
    }

    /** 反向更正行；冲回额等于原凭证，创建后不可变。 */
    public record CorrectionRow(long id, String correctionKey, long settlementId, String settlementKey,
                                long windowId, BigDecimal deliveredRefund, BigDecimal lossRefund,
                                BigDecimal deductionRefund, String reason, long createdNanos) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getBigDecimal("settled_delivered"), rs.getBigDecimal("settled_loss"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, settled_delivered,"
                    + " settled_loss, requester, status, created_nanos, updated_nanos";

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
            rs.getLong("id"), rs.getString("channel_id"), rs.getDate("rate_date").toLocalDate(),
            rs.getBigDecimal("rate"), rs.getInt("version"), rs.getInt("superseded") != 0,
            rs.getLong("created_nanos"));

    private static final String RATE_SELECT =
            "SELECT id, channel_id, rate_date, rate, version, superseded, created_nanos";

    private static final RowMapper<SettlementBatchRow> BATCH_MAPPER = (rs, n) -> new SettlementBatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getLong("window_id"), rs.getString("status"),
            rs.getInt("item_count"), rs.getLong("created_nanos"));

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"), rs.getString("settlement_key"), rs.getLong("batch_id"),
            rs.getString("batch_key"), rs.getLong("window_id"), rs.getString("channel_id"),
            rs.getLong("allocation_id"), rs.getString("allocation_key"), rs.getLong("rate_id"),
            rs.getInt("rate_version"), rs.getDate("rate_date").toLocalDate(), rs.getBigDecimal("rate_value"),
            rs.getBigDecimal("raw_delivered"), rs.getBigDecimal("loss"),
            rs.getBigDecimal("upstream_deduction"), rs.getString("status"), rs.getLong("created_nanos"));

    private static final String SETTLEMENT_SELECT =
            "SELECT s.id, s.settlement_key, s.batch_id, b.batch_key, s.window_id, s.channel_id,"
                    + " s.allocation_id, s.allocation_key, s.rate_id, s.rate_version, s.rate_date,"
                    + " s.rate_value, s.raw_delivered, s.loss, s.upstream_deduction, s.status, s.created_nanos"
                    + " FROM settlement s JOIN settlement_batch b ON b.id = s.batch_id";

    private static final RowMapper<CorrectionRow> CORRECTION_MAPPER = (rs, n) -> new CorrectionRow(
            rs.getLong("id"), rs.getString("correction_key"), rs.getLong("settlement_id"),
            rs.getString("settlement_key"), rs.getLong("window_id"), rs.getBigDecimal("delivered_refund"),
            rs.getBigDecimal("loss_refund"), rs.getBigDecimal("deduction_refund"),
            rs.getString("reason"), rs.getLong("created_nanos"));

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
    // 日蒸发率
    // ------------------------------------------------------------------

    /** 锁定渠道当日当前（未被取代）版本行，不存在返回 null。 */
    public EvaporationRateRow lockCurrentRate(String channelId, LocalDate rateDate) {
        try {
            return jdbc.queryForObject(
                    RATE_SELECT + " FROM evaporation_rate WHERE channel_id = ? AND rate_date = ? AND superseded = 0"
                            + " FOR UPDATE", RATE_MAPPER, channelId, Date.valueOf(rateDate));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 查询渠道当日当前版本，不存在返回 null（读操作不加锁）。 */
    public EvaporationRateRow findCurrentRate(String channelId, LocalDate rateDate) {
        try {
            return jdbc.queryForObject(
                    RATE_SELECT + " FROM evaporation_rate WHERE channel_id = ? AND rate_date = ? AND superseded = 0",
                    RATE_MAPPER, channelId, Date.valueOf(rateDate));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定蒸发率版本行。 */
    public EvaporationRateRow lockRateById(long id) {
        try {
            return jdbc.queryForObject(RATE_SELECT + " FROM evaporation_rate WHERE id = ? FOR UPDATE",
                    RATE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道当日是否存在任何版本（含历史版本），用于判断当天是否已登记过。 */
    public boolean existsRateVersion(String channelId, LocalDate rateDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaporation_rate WHERE channel_id = ? AND rate_date = ?",
                Integer.class, channelId, Date.valueOf(rateDate));
        return count != null && count > 0;
    }

    /** 追加新版本：旧当前版本置 superseded=1，插入 version+1 的新版本。 */
    public long insertRateVersion(String channelId, LocalDate rateDate, BigDecimal rate, int version,
                                  long createdNanos) {
        if (version > 1) {
            jdbc.update("UPDATE evaporation_rate SET superseded = 1 WHERE channel_id = ? AND rate_date = ?",
                    channelId, Date.valueOf(rateDate));
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO evaporation_rate (channel_id, rate_date, rate, version, superseded, created_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setDate(2, Date.valueOf(rateDate));
            ps.setBigDecimal(3, rate);
            ps.setInt(4, version);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 渠道某日是否存在该日的核销凭证（任意状态），用于冻结当日率修改。 */
    public boolean existsSettlementOnDate(String channelId, LocalDate rateDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE channel_id = ? AND rate_date = ?",
                Integer.class, channelId, Date.valueOf(rateDate));
        return count != null && count > 0;
    }

    /** 渠道全部蒸发率版本（含历史），按日期、版本号升序。 */
    public List<EvaporationRateRow> listRates(String channelId) {
        return jdbc.query(
                RATE_SELECT + " FROM evaporation_rate WHERE channel_id = ? ORDER BY rate_date, version",
                RATE_MAPPER, channelId);
    }

    // ------------------------------------------------------------------
    // 核销批次、凭证与更正
    // ------------------------------------------------------------------

    /** 插入核销批次（COMMITTED）并返回主键。 */
    public long insertBatch(String batchKey, long windowId, int itemCount, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement_batch (batch_key, window_id, status, item_count, created_nanos)"
                            + " VALUES (?, ?, 'COMMITTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchKey);
            ps.setLong(2, windowId);
            ps.setInt(3, itemCount);
            ps.setLong(4, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 插入核销凭证并返回主键；数量与率版本均在此固化。 */
    public long insertSettlement(String settlementKey, long batchId, long windowId, String channelId,
                                 long allocationId, String allocationKey, long rateId, int rateVersion,
                                 LocalDate rateDate, BigDecimal rateValue, BigDecimal rawDelivered,
                                 BigDecimal loss, BigDecimal upstreamDeduction, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement (settlement_key, batch_id, window_id, channel_id, allocation_id,"
                            + " allocation_key, rate_id, rate_version, rate_date, rate_value, raw_delivered,"
                            + " loss, upstream_deduction, status, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlementKey);
            ps.setLong(2, batchId);
            ps.setLong(3, windowId);
            ps.setString(4, channelId);
            ps.setLong(5, allocationId);
            ps.setString(6, allocationKey);
            ps.setLong(7, rateId);
            ps.setInt(8, rateVersion);
            ps.setDate(9, Date.valueOf(rateDate));
            ps.setBigDecimal(10, rateValue);
            ps.setBigDecimal(11, rawDelivered);
            ps.setBigDecimal(12, loss);
            ps.setBigDecimal(13, upstreamDeduction);
            ps.setLong(14, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销凭证，不存在返回 null。 */
    public SettlementRow findSettlementByKey(String settlementKey) {
        try {
            return jdbc.queryForObject(
                    SETTLEMENT_SELECT + " WHERE s.settlement_key = ?",
                    SETTLEMENT_MAPPER, settlementKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定核销凭证行（单表 FOR UPDATE，batch_key 以 NULL 列占位，加锁后如需该字段另行联表读）。 */
    public SettlementRow lockSettlementById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT s.id, s.settlement_key, s.batch_id, NULL AS batch_key, s.window_id,"
                            + " s.channel_id, s.allocation_id, s.allocation_key, s.rate_id, s.rate_version,"
                            + " s.rate_date, s.rate_value, s.raw_delivered, s.loss, s.upstream_deduction,"
                            + " s.status, s.created_nanos FROM settlement s WHERE s.id = ? FOR UPDATE",
                    SETTLEMENT_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 将凭证置为 REVERSED（反向更正时调用，证据行保留）。 */
    public void markSettlementReversed(long id) {
        jdbc.update("UPDATE settlement SET status = 'REVERSED' WHERE id = ?", id);
    }

    /** 窗口全部核销凭证（含已更正），按主键升序。 */
    public List<SettlementRow> listSettlements(long windowId) {
        return jdbc.query(SETTLEMENT_SELECT + " WHERE s.window_id = ? ORDER BY s.id",
                SETTLEMENT_MAPPER, windowId);
    }

    /** 申请全部核销凭证（含已更正），按主键升序。 */
    public List<SettlementRow> listSettlementsByAllocation(long allocationId) {
        return jdbc.query(SETTLEMENT_SELECT + " WHERE s.allocation_id = ? ORDER BY s.id",
                SETTLEMENT_MAPPER, allocationId);
    }

    /**
     * 核销入账：同一申请行上扣减持有额度并累加送达/损耗累计（FOR UPDATE 已由调用方持有）。
     * 不得为负由 chk_allocation_held 与事务内预校验双重保证。
     */
    public void applySettlementToAllocation(long allocationId, BigDecimal delivered, BigDecimal loss,
                                            long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?,"
                        + " settled_delivered = settled_delivered + ?, settled_loss = settled_loss + ?,"
                        + " updated_nanos = ? WHERE id = ?",
                delivered.add(loss), delivered, loss, updatedNanos, allocationId);
    }

    /** 反向更正冲回：恢复持有额度并等额冲减送达/损耗累计。 */
    public void applyCorrectionToAllocation(long allocationId, BigDecimal delivered, BigDecimal loss,
                                            long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount + ?,"
                        + " settled_delivered = settled_delivered - ?, settled_loss = settled_loss - ?,"
                        + " updated_nanos = ? WHERE id = ?",
                delivered.add(loss), delivered, loss, updatedNanos, allocationId);
    }

    /** 插入反向更正凭证并返回主键。 */
    public long insertCorrection(String correctionKey, long settlementId, String settlementKey, long windowId,
                                 BigDecimal deliveredRefund, BigDecimal lossRefund, BigDecimal deductionRefund,
                                 String reason, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement_correction (correction_key, settlement_id, settlement_key,"
                            + " window_id, delivered_refund, loss_refund, deduction_refund, reason, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, correctionKey);
            ps.setLong(2, settlementId);
            ps.setString(3, settlementKey);
            ps.setLong(4, windowId);
            ps.setBigDecimal(5, deliveredRefund);
            ps.setBigDecimal(6, lossRefund);
            ps.setBigDecimal(7, deductionRefund);
            ps.setString(8, reason);
            ps.setLong(9, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询更正凭证，不存在返回 null。 */
    public CorrectionRow findCorrectionByKey(String correctionKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, correction_key, settlement_id, settlement_key, window_id, delivered_refund,"
                            + " loss_refund, deduction_refund, reason, created_nanos"
                            + " FROM settlement_correction WHERE correction_key = ?",
                    CORRECTION_MAPPER, correctionKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询更正凭证，不存在返回 null。 */
    public CorrectionRow findCorrectionById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, correction_key, settlement_id, settlement_key, window_id, delivered_refund,"
                            + " loss_refund, deduction_refund, reason, created_nanos"
                            + " FROM settlement_correction WHERE id = ?",
                    CORRECTION_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部更正凭证，按主键升序。 */
    public List<CorrectionRow> listCorrections(long windowId) {
        return jdbc.query(
                "SELECT id, correction_key, settlement_id, settlement_key, window_id, delivered_refund,"
                        + " loss_refund, deduction_refund, reason, created_nanos"
                        + " FROM settlement_correction WHERE window_id = ? ORDER BY id",
                CORRECTION_MAPPER, windowId);
    }

    /** 按主键查询批次，不存在返回 null。 */
    public SettlementBatchRow findBatchByKey(String batchKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, batch_key, window_id, status, item_count, created_nanos"
                            + " FROM settlement_batch WHERE batch_key = ?", BATCH_MAPPER, batchKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
