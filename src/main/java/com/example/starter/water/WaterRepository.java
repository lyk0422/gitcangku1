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

    /** 渠道行；capacity 为 null 表示不限核销容量。 */
    public record ChannelRow(String channelId, BigDecimal capacity, int version, long createdNanos) {
    }

    /** 停运窗口行；recoveredNanos 为 null 表示未提前恢复。 */
    public record OutageRow(long id, String outageKey, String channelId, long startNanos, long endNanos,
                            String status, Long recoveredNanos, long createdNanos) {
    }

    /** 供应风险行，创建后不可变。 */
    public record RiskRow(long id, long outageId, String allocationKey, long createdNanos) {
    }

    /** 核销流水行，创建后不可变；batchKey 为 null 表示单笔核销。 */
    public record SettlementRow(long id, String settlementKey, String allocationKey, BigDecimal amount,
                                String actor, String batchKey, long createdNanos) {
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
            rs.getString("channel_id"), rs.getBigDecimal("capacity"),
            rs.getInt("version"), rs.getLong("created_nanos"));

    private static final RowMapper<OutageRow> OUTAGE_MAPPER = (rs, n) -> new OutageRow(
            rs.getLong("id"), rs.getString("outage_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"), rs.getString("status"),
            rs.getObject("recovered_nanos") == null ? null : rs.getLong("recovered_nanos"),
            rs.getLong("created_nanos"));

    private static final String OUTAGE_SELECT =
            "SELECT id, outage_key, channel_id, start_nanos, end_nanos, status, recovered_nanos, created_nanos";

    private static final RowMapper<RiskRow> RISK_MAPPER = (rs, n) -> new RiskRow(
            rs.getLong("id"), rs.getLong("outage_id"), rs.getString("allocation_key"),
            rs.getLong("created_nanos"));

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"), rs.getString("settlement_key"), rs.getString("allocation_key"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getString("batch_key"),
            rs.getLong("created_nanos"));

    private static final String SETTLEMENT_SELECT =
            "SELECT id, settlement_key, allocation_key, amount, actor, batch_key, created_nanos";

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
    // 渠道
    // ------------------------------------------------------------------

    /** 登记渠道（已存在则忽略），初始版本 1、容量不限。 */
    public void upsertChannel(String channelId, long createdNanos) {
        jdbc.update("INSERT IGNORE INTO canal_channel (channel_id, capacity, version, created_nanos)"
                + " VALUES (?, NULL, 1, ?)", channelId, createdNanos);
    }

    /** 按 ID 查询渠道，不存在返回 null。 */
    public ChannelRow findChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT channel_id, capacity, version, created_nanos FROM canal_channel WHERE channel_id = ?",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按 ID 锁定渠道行（FOR UPDATE），串行化渠道变更、停运与核销裁决，不存在返回 null。 */
    public ChannelRow lockChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT channel_id, capacity, version, created_nanos FROM canal_channel"
                            + " WHERE channel_id = ? FOR UPDATE",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道变更：设置核销容量（null 表示不限）并将版本号加 1。 */
    public void updateChannel(String channelId, BigDecimal capacity) {
        jdbc.update("UPDATE canal_channel SET capacity = ?, version = version + 1 WHERE channel_id = ?",
                capacity, channelId);
    }

    /** 停运下达/删除/恢复：仅将渠道版本号加 1。 */
    public void bumpChannelVersion(String channelId) {
        jdbc.update("UPDATE canal_channel SET version = version + 1 WHERE channel_id = ?", channelId);
    }

    // ------------------------------------------------------------------
    // 停运窗口
    // ------------------------------------------------------------------

    /** 插入停运窗口（SCHEDULED）并返回主键。 */
    public long insertOutage(String outageKey, String channelId, long startNanos, long endNanos,
                             long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO canal_outage (outage_key, channel_id, start_nanos, end_nanos, status,"
                            + " recovered_nanos, created_nanos)"
                            + " VALUES (?, ?, ?, ?, 'SCHEDULED', NULL, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, outageKey);
            ps.setString(2, channelId);
            ps.setLong(3, startNanos);
            ps.setLong(4, endNanos);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询停运窗口，不存在返回 null。 */
    public OutageRow findOutageByKey(String outageKey) {
        try {
            return jdbc.queryForObject(OUTAGE_SELECT + " FROM canal_outage WHERE outage_key = ?",
                    OUTAGE_MAPPER, outageKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询停运窗口，不存在返回 null。 */
    public OutageRow findOutageById(long id) {
        try {
            return jdbc.queryForObject(OUTAGE_SELECT + " FROM canal_outage WHERE id = ?",
                    OUTAGE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定停运窗口行（FOR UPDATE），不存在返回 null。 */
    public OutageRow lockOutageByKey(String outageKey) {
        try {
            return jdbc.queryForObject(OUTAGE_SELECT + " FROM canal_outage WHERE outage_key = ? FOR UPDATE",
                    OUTAGE_MAPPER, outageKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部停运窗口（含已删除），按主键升序。 */
    public List<OutageRow> listOutagesByChannel(String channelId) {
        return jdbc.query(OUTAGE_SELECT + " FROM canal_outage WHERE channel_id = ? ORDER BY id",
                OUTAGE_MAPPER, channelId);
    }

    /** 判断同渠道是否存在与 [startNanos, endNanos) 重叠的未删除停运窗口（相邻合法）。 */
    public boolean existsOverlappingOutage(String channelId, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM canal_outage WHERE channel_id = ? AND status <> 'DELETED'"
                        + " AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelId, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 删除停运窗口（置 DELETED，仅未开始可删，由业务层校验）。 */
    public void deleteOutage(long id) {
        jdbc.update("UPDATE canal_outage SET status = 'DELETED' WHERE id = ?", id);
    }

    /** 记录提前恢复时刻（置 RECOVERED），生效区间被截断为 [start, recovered)。 */
    public void recoverOutage(long id, long recoveredNanos) {
        jdbc.update("UPDATE canal_outage SET status = 'RECOVERED', recovered_nanos = ? WHERE id = ?",
                recoveredNanos, id);
    }

    /** 写入停运窗口的受影响申请集合（已规范化排序去重）。 */
    public void insertOutageAllocations(long outageId, List<String> allocationKeys) {
        for (String allocationKey : allocationKeys) {
            jdbc.update("INSERT INTO outage_allocation (outage_id, allocation_key) VALUES (?, ?)",
                    outageId, allocationKey);
        }
    }

    /** 停运窗口的受影响申请键集合，按字典序升序。 */
    public List<String> listOutageAllocations(long outageId) {
        return jdbc.queryForList(
                "SELECT allocation_key FROM outage_allocation WHERE outage_id = ? ORDER BY allocation_key",
                String.class, outageId);
    }

    /** 清空停运窗口的受影响申请集合（删除停运时级联清理）。 */
    public void deleteOutageAllocations(long outageId) {
        jdbc.update("DELETE FROM outage_allocation WHERE outage_id = ?", outageId);
    }

    /**
     * 查询与申请供水窗口 [windowStart, windowEnd) 相交的生效停运窗口：
     * 同渠道、未删除、申请在受影响集合内，且生效区间（被提前恢复时刻截断）与供水窗口相交。
     */
    public List<OutageRow> findConflictingOutages(String channelId, String allocationKey,
                                                  long windowStart, long windowEnd) {
        return jdbc.query("SELECT o.id, o.outage_key, o.channel_id, o.start_nanos, o.end_nanos, o.status,"
                        + " o.recovered_nanos, o.created_nanos FROM canal_outage o"
                        + " JOIN outage_allocation a ON a.outage_id = o.id"
                        + " WHERE o.channel_id = ? AND o.status <> 'DELETED' AND a.allocation_key = ?"
                        + " AND o.start_nanos < ? AND ? < COALESCE(o.recovered_nanos, o.end_nanos)"
                        + " ORDER BY o.id",
                OUTAGE_MAPPER, channelId, allocationKey, windowEnd, windowStart);
    }

    // ------------------------------------------------------------------
    // 供应风险
    // ------------------------------------------------------------------

    /** 写入不可变供应风险记录。 */
    public void insertRisk(long outageId, String allocationKey, long createdNanos) {
        jdbc.update("INSERT INTO supply_risk (outage_id, allocation_key, created_nanos) VALUES (?, ?, ?)",
                outageId, allocationKey, createdNanos);
    }

    /** 申请的全部供应风险记录，按主键升序。 */
    public List<RiskRow> listRisksByAllocation(String allocationKey) {
        return jdbc.query(
                "SELECT id, outage_id, allocation_key, created_nanos FROM supply_risk"
                        + " WHERE allocation_key = ? ORDER BY id",
                RISK_MAPPER, allocationKey);
    }

    /** 申请是否存在供应风险（风险申请不能作为转出方再次转让）。 */
    public boolean existsRiskForAllocation(String allocationKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supply_risk WHERE allocation_key = ?", Integer.class, allocationKey);
        return count != null && count > 0;
    }

    /** 渠道下已批准且仍持有额度（未结算）的申请，用于下达停运时写入供应风险。 */
    public List<AllocationRow> listApprovedUnsettledAllocations(String channelId) {
        return jdbc.query("SELECT a.id, a.allocation_key, a.window_id, a.user_id, a.amount, a.held_amount,"
                        + " a.requester, a.status, a.created_nanos, a.updated_nanos"
                        + " FROM allocation a"
                        + " JOIN supply_window w ON w.id = a.window_id"
                        + " WHERE w.channel_id = ? AND a.status = 'APPROVED' AND a.held_amount > 0"
                        + " ORDER BY a.id",
                ALLOCATION_MAPPER, channelId);
    }

    // ------------------------------------------------------------------
    // 核销流水
    // ------------------------------------------------------------------

    /** 插入不可变核销流水并返回主键。 */
    public long insertSettlement(String settlementKey, String allocationKey, BigDecimal amount,
                                 String actor, String batchKey, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement (settlement_key, allocation_key, amount, actor, batch_key,"
                            + " created_nanos) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlementKey);
            ps.setString(2, allocationKey);
            ps.setBigDecimal(3, amount);
            ps.setString(4, actor);
            ps.setString(5, batchKey);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public SettlementRow findSettlementByKey(String settlementKey) {
        try {
            return jdbc.queryForObject(SETTLEMENT_SELECT + " FROM settlement WHERE settlement_key = ?",
                    SETTLEMENT_MAPPER, settlementKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请的全部核销流水，按主键升序。 */
    public List<SettlementRow> listSettlementsByAllocation(String allocationKey) {
        return jdbc.query(SETTLEMENT_SELECT + " FROM settlement WHERE allocation_key = ? ORDER BY id",
                SETTLEMENT_MAPPER, allocationKey);
    }

    /** 批量核销命令产生的全部核销流水，按主键升序。 */
    public List<SettlementRow> listSettlementsByBatch(String batchKey) {
        return jdbc.query(SETTLEMENT_SELECT + " FROM settlement WHERE batch_key = ? ORDER BY id",
                SETTLEMENT_MAPPER, batchKey);
    }

    /** 渠道累计已核销水量（BigDecimal 精确求和），无则 0。 */
    public BigDecimal sumSettledByChannel(String channelId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(s.amount), 0) FROM settlement s"
                        + " JOIN allocation a ON a.allocation_key = s.allocation_key"
                        + " JOIN supply_window w ON w.id = a.window_id WHERE w.channel_id = ?",
                BigDecimal.class, channelId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
