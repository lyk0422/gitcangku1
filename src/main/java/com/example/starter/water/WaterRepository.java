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
import java.sql.Types;
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

    /** 蒸发率版本行，登记后不可变。 */
    public record EvaporationRateRow(long id, String channelId, LocalDate rateDate, int version,
                                     BigDecimal rate, long createdNanos) {
    }

    /** 核销记录行，创建后不可变；更正记录的 delivered/loss/deduction 为原记录相反数。 */
    public record SettlementRow(long id, String allocationKey, long windowId, String channelId,
                                LocalDate rateDate, int rateVersion, BigDecimal rate, BigDecimal delivered,
                                BigDecimal loss, BigDecimal deduction, BigDecimal upstreamRemaining,
                                String kind, Long correctsId, int batchNo, String commandKey, long createdNanos) {
    }

    /** 渠道日核销净额汇总（更正按负值计入）。 */
    public record SettlementTotals(BigDecimal netDelivered, BigDecimal netLoss, BigDecimal netDeduction,
                                   long settlementCount) {
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

    private static final RowMapper<EvaporationRateRow> EVAP_RATE_MAPPER = (rs, n) -> new EvaporationRateRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getDate("rate_date").toLocalDate(),
            rs.getInt("version"), rs.getBigDecimal("rate"), rs.getLong("created_nanos"));

    private static final String EVAP_RATE_SELECT =
            "SELECT id, channel_id, rate_date, version, rate, created_nanos";

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("channel_id"), rs.getDate("rate_date").toLocalDate(),
            rs.getInt("rate_version"), rs.getBigDecimal("rate"), rs.getBigDecimal("delivered"),
            rs.getBigDecimal("loss"), rs.getBigDecimal("deduction"), rs.getBigDecimal("upstream_remaining"),
            rs.getString("kind"),
            rs.getObject("corrects_id") == null ? null : rs.getLong("corrects_id"),
            rs.getInt("batch_no"), rs.getString("command_key"), rs.getLong("created_nanos"));

    private static final String SETTLEMENT_SELECT =
            "SELECT id, allocation_key, window_id, channel_id, rate_date, rate_version, rate, delivered,"
                    + " loss, deduction, upstream_remaining, kind, corrects_id, batch_no, command_key,"
                    + " created_nanos";

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
    // 蒸发率版本
    // ------------------------------------------------------------------

    /** 登记蒸发率新版本并返回主键；版本号由调用方在锁内计算。 */
    public long insertEvaporationRate(String channelId, LocalDate rateDate, int version, BigDecimal rate,
                                      long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO evaporation_rate (channel_id, rate_date, version, rate, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setDate(2, Date.valueOf(rateDate));
            ps.setInt(3, version);
            ps.setBigDecimal(4, rate);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询蒸发率版本，不存在返回 null。 */
    public EvaporationRateRow findRateById(long id) {
        try {
            return jdbc.queryForObject(EVAP_RATE_SELECT + " FROM evaporation_rate WHERE id = ?",
                    EVAP_RATE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 查询渠道当日最新蒸发率版本，未登记返回 null。 */
    public EvaporationRateRow findLatestRateVersion(String channelId, LocalDate rateDate) {
        try {
            return jdbc.queryForObject(
                    EVAP_RATE_SELECT + " FROM evaporation_rate WHERE channel_id = ? AND rate_date = ?"
                            + " ORDER BY version DESC LIMIT 1",
                    EVAP_RATE_MAPPER, channelId, Date.valueOf(rateDate));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定渠道当日最新蒸发率版本行（FOR UPDATE），用于串行化改率与核销，未登记返回 null。 */
    public EvaporationRateRow lockLatestRateVersion(String channelId, LocalDate rateDate) {
        try {
            return jdbc.queryForObject(
                    EVAP_RATE_SELECT + " FROM evaporation_rate WHERE channel_id = ? AND rate_date = ?"
                            + " ORDER BY version DESC LIMIT 1 FOR UPDATE",
                    EVAP_RATE_MAPPER, channelId, Date.valueOf(rateDate));
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道当日全部蒸发率版本，按版本号升序。 */
    public List<EvaporationRateRow> listRateVersions(String channelId, LocalDate rateDate) {
        return jdbc.query(
                EVAP_RATE_SELECT + " FROM evaporation_rate WHERE channel_id = ? AND rate_date = ?"
                        + " ORDER BY version",
                EVAP_RATE_MAPPER, channelId, Date.valueOf(rateDate));
    }

    // ------------------------------------------------------------------
    // 核销记录
    // ------------------------------------------------------------------

    /** 插入不可变核销/更正记录并返回主键。 */
    public long insertSettlement(String allocationKey, long windowId, String channelId, LocalDate rateDate,
                                 int rateVersion, BigDecimal rate, BigDecimal delivered, BigDecimal loss,
                                 BigDecimal deduction, BigDecimal upstreamRemaining, String kind,
                                 Long correctsId, int batchNo, String commandKey, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement (allocation_key, window_id, channel_id, rate_date, rate_version,"
                            + " rate, delivered, loss, deduction, upstream_remaining, kind, corrects_id,"
                            + " batch_no, command_key, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, channelId);
            ps.setDate(4, Date.valueOf(rateDate));
            ps.setInt(5, rateVersion);
            ps.setBigDecimal(6, rate);
            ps.setBigDecimal(7, delivered);
            ps.setBigDecimal(8, loss);
            ps.setBigDecimal(9, deduction);
            ps.setBigDecimal(10, upstreamRemaining);
            ps.setString(11, kind);
            if (correctsId == null) {
                ps.setNull(12, Types.BIGINT);
            } else {
                ps.setLong(12, correctsId);
            }
            ps.setInt(13, batchNo);
            ps.setString(14, commandKey);
            ps.setLong(15, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询核销记录，不存在返回 null。 */
    public SettlementRow findSettlementById(long id) {
        try {
            return jdbc.queryForObject(SETTLEMENT_SELECT + " FROM settlement WHERE id = ?",
                    SETTLEMENT_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定核销记录（FOR UPDATE），用于串行化更正，不存在返回 null。 */
    public SettlementRow lockSettlementById(long id) {
        try {
            return jdbc.queryForObject(SETTLEMENT_SELECT + " FROM settlement WHERE id = ? FOR UPDATE",
                    SETTLEMENT_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请全部核销/更正记录，按主键升序（发生顺序）。 */
    public List<SettlementRow> listSettlementsByAllocation(String allocationKey) {
        return jdbc.query(SETTLEMENT_SELECT + " FROM settlement WHERE allocation_key = ? ORDER BY id",
                SETTLEMENT_MAPPER, allocationKey);
    }

    /** 渠道当日是否已存在核销记录（SETTLEMENT 类型），用于判定改率是否被锁。 */
    public boolean existsSettlementForChannelDate(String channelId, LocalDate rateDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE channel_id = ? AND rate_date = ? AND kind = 'SETTLEMENT'",
                Integer.class, channelId, Date.valueOf(rateDate));
        return count != null && count > 0;
    }

    /** 是否已存在针对指定核销记录的更正记录。 */
    public boolean existsCorrectionFor(long settlementId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE corrects_id = ? AND kind = 'CORRECTION'",
                Integer.class, settlementId);
        return count != null && count > 0;
    }

    /** 渠道当日核销净额汇总（更正按负值计入）与 SETTLEMENT 记录数。 */
    public SettlementTotals sumSettlementsForChannelDate(String channelId, LocalDate rateDate) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(delivered), 0) AS nd, COALESCE(SUM(loss), 0) AS nl,"
                        + " COALESCE(SUM(deduction), 0) AS nde,"
                        + " COALESCE(SUM(CASE WHEN kind = 'SETTLEMENT' THEN 1 ELSE 0 END), 0) AS cnt"
                        + " FROM settlement WHERE channel_id = ? AND rate_date = ?",
                (rs, n) -> new SettlementTotals(rs.getBigDecimal("nd"), rs.getBigDecimal("nl"),
                        rs.getBigDecimal("nde"), rs.getLong("cnt")),
                channelId, Date.valueOf(rateDate));
    }

    /** 更正回补上游持有额度（不得越过原申请水量由事务内校验保证）并记录变更时间。 */
    public void incrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount + ?, updated_nanos = ? WHERE id = ?",
                delta, updatedNanos, id);
    }
}
