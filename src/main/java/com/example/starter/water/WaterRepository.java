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

    /** 渠道行；version 随每次停运窗口变更递增。 */
    public record ChannelRow(String channelId, long version, long createdNanos, long updatedNanos) {
    }

    /** 停运窗口行；recoveredNanos 为 null 表示未记录提前恢复。 */
    public record OutageRow(long id, String outageKey, String channelId, long startNanos, long endNanos,
                            String status, Long recoveredNanos, long channelVersion, long createdNanos) {
    }

    /** 供应风险行，创建后不可变。 */
    public record RiskRow(long id, long outageId, String allocationKey, long createdNanos) {
    }

    /** 核销流水行，创建后不可变；batchKey 为 null 表示单笔核销。 */
    public record SettlementRow(long id, String settlementKey, String batchKey, String allocationKey,
                                long windowId, BigDecimal amount, long createdNanos) {
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

    private static final RowMapper<ChannelRow> CHANNEL_MAPPER = (rs, n) -> new ChannelRow(
            rs.getString("channel_id"), rs.getLong("version"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String OUTAGE_SELECT =
            "SELECT id, outage_key, channel_id, start_nanos, end_nanos, status, recovered_nanos,"
                    + " channel_version, created_nanos";

    private static final RowMapper<OutageRow> OUTAGE_MAPPER = (rs, n) -> new OutageRow(
            rs.getLong("id"), rs.getString("outage_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"), rs.getString("status"),
            rs.getObject("recovered_nanos") == null ? null : rs.getLong("recovered_nanos"),
            rs.getLong("channel_version"), rs.getLong("created_nanos"));

    private static final RowMapper<RiskRow> RISK_MAPPER = (rs, n) -> new RiskRow(
            rs.getLong("id"), rs.getLong("outage_id"), rs.getString("allocation_key"),
            rs.getLong("created_nanos"));

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"), rs.getString("settlement_key"), rs.getString("batch_key"),
            rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getBigDecimal("amount"), rs.getLong("created_nanos"));

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
    // 渠道与停运窗口
    // ------------------------------------------------------------------

    /** 按 ID 查询渠道，不存在返回 null。 */
    public ChannelRow findChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT channel_id, version, created_nanos, updated_nanos FROM channel WHERE channel_id = ?",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 ID 锁定渠道行（FOR UPDATE），串行化停运变更、核销与转让结算，不存在返回 null。 */
    public ChannelRow lockChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT channel_id, version, created_nanos, updated_nanos"
                            + " FROM channel WHERE channel_id = ? FOR UPDATE",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 创建渠道（版本 0）；并发重复创建由调用方捕获 DuplicateKeyException 忽略。 */
    public void insertChannel(String channelId, long nowNanos) {
        jdbc.update("INSERT INTO channel (channel_id, version, created_nanos, updated_nanos)"
                + " VALUES (?, 0, ?, ?)", channelId, nowNanos, nowNanos);
    }

    /** 渠道版本 +1 并记录变更时间。 */
    public void bumpChannelVersion(String channelId, long updatedNanos) {
        jdbc.update("UPDATE channel SET version = version + 1, updated_nanos = ? WHERE channel_id = ?",
                updatedNanos, channelId);
    }

    /** 插入停运窗口（SCHEDULED）并返回主键。 */
    public long insertOutage(String outageKey, String channelId, long startNanos, long endNanos,
                             long channelVersion, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outage_window (outage_key, channel_id, start_nanos, end_nanos, status,"
                            + " recovered_nanos, channel_version, created_nanos)"
                            + " VALUES (?, ?, ?, ?, 'SCHEDULED', NULL, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, outageKey);
            ps.setString(2, channelId);
            ps.setLong(3, startNanos);
            ps.setLong(4, endNanos);
            ps.setLong(5, channelVersion);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询停运窗口（含已删除），不存在返回 null。 */
    public OutageRow findOutageByKey(String outageKey) {
        try {
            return jdbc.queryForObject(OUTAGE_SELECT + " FROM outage_window WHERE outage_key = ?",
                    OUTAGE_MAPPER, outageKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询停运窗口（含已删除），不存在返回 null。 */
    public OutageRow findOutageById(long id) {
        try {
            return jdbc.queryForObject(OUTAGE_SELECT + " FROM outage_window WHERE id = ?",
                    OUTAGE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 判断同渠道是否存在与 [startNanos, endNanos) 重叠的生效停运窗口（相邻合法，已删除不计）。 */
    public boolean existsOverlappingOutage(String channelId, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outage_window WHERE channel_id = ? AND status = 'SCHEDULED'"
                        + " AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelId, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 软删除停运窗口（仅未开始可删除，由业务层校验），并记录变更后的渠道版本。 */
    public void markOutageDeleted(long id, long channelVersion) {
        jdbc.update("UPDATE outage_window SET status = 'DELETED', channel_version = ? WHERE id = ?",
                channelVersion, id);
    }

    /** 记录提前恢复时刻，并记录变更后的渠道版本。 */
    public void markOutageRecovered(long id, long recoveredNanos, long channelVersion) {
        jdbc.update("UPDATE outage_window SET recovered_nanos = ?, channel_version = ? WHERE id = ?",
                recoveredNanos, channelVersion, id);
    }

    /**
     * 查询在 now 时刻生效且与供水窗口 [windowStart, windowEnd) 相交的停运窗口：
     * 未删除，且未恢复或恢复时刻晚于 now（恢复仅影响之后的核销）。
     */
    public List<OutageRow> listEffectiveOutages(String channelId, long windowStart, long windowEnd, long now) {
        return jdbc.query(OUTAGE_SELECT + " FROM outage_window WHERE channel_id = ? AND status = 'SCHEDULED'"
                        + " AND start_nanos < ? AND ? < end_nanos"
                        + " AND (recovered_nanos IS NULL OR recovered_nanos > ?) ORDER BY id",
                OUTAGE_MAPPER, channelId, windowEnd, windowStart, now);
    }

    /** 写入停运窗口受影响申请（不可变）。 */
    public void insertOutageAllocation(long outageId, String allocationKey) {
        jdbc.update("INSERT INTO outage_allocation (outage_id, allocation_key) VALUES (?, ?)",
                outageId, allocationKey);
    }

    /** 停运窗口受影响申请业务键，按业务键升序（规范化顺序）。 */
    public List<String> listOutageAllocationKeys(long outageId) {
        return jdbc.queryForList(
                "SELECT allocation_key FROM outage_allocation WHERE outage_id = ? ORDER BY allocation_key",
                String.class, outageId);
    }

    /** 写入不可变供应风险。 */
    public void insertRisk(long outageId, String allocationKey, long createdNanos) {
        jdbc.update("INSERT INTO supply_risk (outage_id, allocation_key, created_nanos) VALUES (?, ?, ?)",
                outageId, allocationKey, createdNanos);
    }

    /** 申请是否已存在供应风险（风险申请不能再次转让）。 */
    public boolean existsRiskForAllocation(String allocationKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supply_risk WHERE allocation_key = ?", Integer.class, allocationKey);
        return count != null && count > 0;
    }

    /** 申请全部供应风险，按主键升序。 */
    public List<RiskRow> listRisksByAllocation(String allocationKey) {
        return jdbc.query(
                "SELECT id, outage_id, allocation_key, created_nanos FROM supply_risk"
                        + " WHERE allocation_key = ? ORDER BY id",
                RISK_MAPPER, allocationKey);
    }

    /** 停运窗口写入的全部供应风险，按主键升序。 */
    public List<RiskRow> listRisksByOutage(long outageId) {
        return jdbc.query(
                "SELECT id, outage_id, allocation_key, created_nanos FROM supply_risk"
                        + " WHERE outage_id = ? ORDER BY id",
                RISK_MAPPER, outageId);
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    /** 插入不可变核销流水并返回主键；batchKey 为 null 表示单笔核销。 */
    public long insertSettlement(String settlementKey, String batchKey, String allocationKey, long windowId,
                                 BigDecimal amount, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement (settlement_key, batch_key, allocation_key, window_id, amount,"
                            + " created_nanos) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlementKey);
            ps.setString(2, batchKey);
            ps.setString(3, allocationKey);
            ps.setLong(4, windowId);
            ps.setBigDecimal(5, amount);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public SettlementRow findSettlementByKey(String settlementKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, settlement_key, batch_key, allocation_key, window_id, amount, created_nanos"
                            + " FROM settlement WHERE settlement_key = ?",
                    SETTLEMENT_MAPPER, settlementKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 批量核销业务键是否已使用。 */
    public boolean existsSettlementBatch(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement WHERE batch_key = ?", Integer.class, batchKey);
        return count != null && count > 0;
    }

    /** 窗口累计已核销水量（BigDecimal 精确求和），无则 0。 */
    public BigDecimal sumSettledAmount(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM settlement WHERE window_id = ?",
                BigDecimal.class, windowId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
