package com.example.starter.water;

import org.springframework.dao.DuplicateKeyException;
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

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度；排班时段为空表示不排班。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status,
                                Long scheduledStartNanos, Long scheduledEndNanos,
                                long createdNanos, long updatedNanos) {
    }

    /** 灌渠行，每个渠道一行，供中断登记与排班发布取行锁串行化。 */
    public record ChannelRow(long id, String channelKey, long createdNanos) {
    }

    /** 灌渠中断窗口行，登记后不可变。 */
    public record OutageRow(long id, String requestId, String channelKey, String reason,
                            long startNanos, long endNanos, int postponeMinutes, String status,
                            long createdNanos) {
    }

    /** 排班顺延对应关系行，登记后不可变。 */
    public record PostponementRow(long id, String outageRequestId, String channelKey, String allocationKey,
                                  long originalStartNanos, long originalEndNanos,
                                  long newStartNanos, long newEndNanos, int postponeMinutes, String reason,
                                  long createdNanos) {
    }

    /** 历史用水核销行，排班时段为核销当时快照，创建后不可变。 */
    public record WaterUsageRow(long id, String usageKey, String allocationKey, long windowId,
                                BigDecimal volume, Long scheduleStartNanos, Long scheduleEndNanos,
                                String actor, long createdNanos) {
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
            rs.getString("requester"), rs.getString("status"),
            rs.getObject("scheduled_start_nanos") == null ? null : rs.getLong("scheduled_start_nanos"),
            rs.getObject("scheduled_end_nanos") == null ? null : rs.getLong("scheduled_end_nanos"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_COLUMNS =
            "id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " scheduled_start_nanos, scheduled_end_nanos, created_nanos, updated_nanos";

    /** 与 {@link #ALLOCATION_COLUMNS} 对齐、带 allocation 别名 a. 的列，供 JOIN 查询使用。 */
    private static final String ALLOCATION_COLUMNS_WITH_ALIAS =
            "SELECT a.id, a.allocation_key, a.window_id, a.user_id, a.amount, a.held_amount, a.requester,"
                    + " a.status, a.scheduled_start_nanos, a.scheduled_end_nanos, a.created_nanos,"
                    + " a.updated_nanos";

    /** 与 {@link #ALLOCATION_COLUMNS} 对齐的完整列，供新增表的 JOIN 查询复用。 */
    private static final String ALLOCATION_SELECT =
            "SELECT " + ALLOCATION_COLUMNS + " FROM allocation";

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

    /** 插入申请（初始 REQUESTED）并返回主键；scheduledStart/End 同时为 null 表示不排班。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, Long scheduledStartNanos, Long scheduledEndNanos,
                                 long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester,"
                            + " status, scheduled_start_nanos, scheduled_end_nanos, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            if (scheduledStartNanos == null || scheduledEndNanos == null) {
                ps.setObject(6, null);
                ps.setObject(7, null);
            } else {
                ps.setLong(6, scheduledStartNanos);
                ps.setLong(7, scheduledEndNanos);
            }
            ps.setLong(8, nowNanos);
            ps.setLong(9, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询申请，不存在返回 null。 */
    public AllocationRow findAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(ALLOCATION_SELECT + " WHERE allocation_key = ?",
                    ALLOCATION_MAPPER, allocationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定申请行（FOR UPDATE），用于串行化转让的源/目标争用，不存在返回 null。 */
    public AllocationRow lockAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(ALLOCATION_SELECT + " WHERE allocation_key = ? FOR UPDATE",
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
                ALLOCATION_SELECT + " WHERE window_id = ? ORDER BY id",
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
    // 灌渠、中断、顺延
    // ------------------------------------------------------------------

    private static final RowMapper<ChannelRow> CHANNEL_MAPPER = (rs, n) -> new ChannelRow(
            rs.getLong("id"), rs.getString("channel_key"), rs.getLong("created_nanos"));

    private static final RowMapper<OutageRow> OUTAGE_MAPPER = (rs, n) -> new OutageRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("channel_key"),
            rs.getString("reason"), rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getInt("postpone_minutes"), rs.getString("status"), rs.getLong("created_nanos"));

    private static final RowMapper<PostponementRow> POSTPONEMENT_MAPPER = (rs, n) -> new PostponementRow(
            rs.getLong("id"), rs.getString("outage_request_id"), rs.getString("channel_key"),
            rs.getString("allocation_key"),
            rs.getLong("original_start_nanos"), rs.getLong("original_end_nanos"),
            rs.getLong("new_start_nanos"), rs.getLong("new_end_nanos"),
            rs.getInt("postpone_minutes"), rs.getString("reason"), rs.getLong("created_nanos"));

    private static final RowMapper<WaterUsageRow> WATER_USAGE_MAPPER = (rs, n) -> new WaterUsageRow(
            rs.getLong("id"), rs.getString("usage_key"), rs.getString("allocation_key"),
            rs.getLong("window_id"), rs.getBigDecimal("volume"),
            rs.getObject("schedule_start_nanos") == null ? null : rs.getLong("schedule_start_nanos"),
            rs.getObject("schedule_end_nanos") == null ? null : rs.getLong("schedule_end_nanos"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    /**
     * 若灌渠主数据尚不存在则插入。并发登记/发布可能同时插入，唯一键冲突说明其他事务已建行，
     * 属正常情况，吞掉冲突后由调用方继续锁定该行。
     */
    public void insertChannelIfAbsent(String channelKey, long createdNanos) {
        try {
            jdbc.update("INSERT INTO canal_channel (channel_key, created_nanos)"
                    + " SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM canal_channel WHERE channel_key = ?)",
                    channelKey, createdNanos, channelKey);
        } catch (DuplicateKeyException e) {
            // 灌渠行已被并发事务创建，忽略
        }
    }

    /** 按业务键锁定灌渠行（FOR UPDATE），调用前需先执行 {@link #insertChannelIfAbsent}，不存在返回 null。 */
    public ChannelRow lockChannelByKey(String channelKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, channel_key, created_nanos FROM canal_channel WHERE channel_key = ? FOR UPDATE",
                    CHANNEL_MAPPER, channelKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入中断窗口（REGISTERED）并返回主键；requestId 唯一冲突由调用方转 409。 */
    public long insertOutage(String requestId, String channelKey, String reason, long startNanos,
                             long endNanos, int postponeMinutes, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO canal_outage (request_id, channel_key, reason, start_nanos, end_nanos,"
                            + " postpone_minutes, status, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, channelKey);
            ps.setString(3, reason);
            ps.setLong(4, startNanos);
            ps.setLong(5, endNanos);
            ps.setInt(6, postponeMinutes);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按请求幂等键查询中断，不存在返回 null。 */
    public OutageRow findOutageByRequestId(String requestId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, request_id, channel_key, reason, start_nanos, end_nanos, postpone_minutes,"
                            + " status, created_nanos FROM canal_outage WHERE request_id = ?",
                    OUTAGE_MAPPER, requestId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 灌渠全部中断记录，按主键升序。 */
    public List<OutageRow> listOutagesByChannel(String channelKey) {
        return jdbc.query(
                "SELECT id, request_id, channel_key, reason, start_nanos, end_nanos, postpone_minutes,"
                        + " status, created_nanos FROM canal_outage WHERE channel_key = ? ORDER BY id",
                OUTAGE_MAPPER, channelKey);
    }

    /** 插入一条顺延对应关系，同一中断对同一申请至多一条（唯一约束兜底）。 */
    public long insertPostponement(String outageRequestId, String channelKey, String allocationKey,
                                   long originalStartNanos, long originalEndNanos,
                                   long newStartNanos, long newEndNanos, int postponeMinutes, String reason,
                                   long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO schedule_postponement (outage_request_id, channel_key, allocation_key,"
                            + " original_start_nanos, original_end_nanos, new_start_nanos, new_end_nanos,"
                            + " postpone_minutes, reason, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, outageRequestId);
            ps.setString(2, channelKey);
            ps.setString(3, allocationKey);
            ps.setLong(4, originalStartNanos);
            ps.setLong(5, originalEndNanos);
            ps.setLong(6, newStartNanos);
            ps.setLong(7, newEndNanos);
            ps.setInt(8, postponeMinutes);
            ps.setString(9, reason);
            ps.setLong(10, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 中断产生的全部顺延对应关系，按主键升序。 */
    public List<PostponementRow> listPostponementsByOutage(String outageRequestId) {
        return jdbc.query(
                "SELECT id, outage_request_id, channel_key, allocation_key, original_start_nanos,"
                        + " original_end_nanos, new_start_nanos, new_end_nanos, postpone_minutes, reason,"
                        + " created_nanos FROM schedule_postponement WHERE outage_request_id = ? ORDER BY id",
                POSTPONEMENT_MAPPER, outageRequestId);
    }

    /**
     * 返回同灌渠内排班时段与 [startNanos, endNanos) 交叉（左闭右开，相邻不算）的未取消申请，
     * 按申请主键升序；调用方须已持有灌渠行锁，再对返回行用 {@link #lockAllocationByKey} 逐行加锁。
     */
    public List<AllocationRow> listCrossingActiveAllocations(String channelKey, long startNanos, long endNanos) {
        return jdbc.query(
                ALLOCATION_COLUMNS_WITH_ALIAS + " FROM allocation a JOIN supply_window w ON a.window_id = w.id"
                        + " WHERE w.channel_id = ? AND a.status <> 'CANCELLED'"
                        + " AND a.scheduled_start_nanos IS NOT NULL"
                        + " AND a.scheduled_start_nanos < ? AND ? < a.scheduled_end_nanos"
                        + " ORDER BY a.id",
                ALLOCATION_MAPPER, channelKey, endNanos, startNanos);
    }

    /** 判断同灌渠是否存在与 [startNanos, endNanos) 交叉的已登记中断窗口（左闭右开，相邻不算）。 */
    public boolean existsOverlappingOutage(String channelKey, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM canal_outage WHERE channel_key = ?"
                        + " AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelKey, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 查询同灌渠全部占用灌渠的排班（REQUESTED/APPROVED 且带排班时段），用于整批顺延冲突校验。 */
    public List<AllocationRow> listActiveSchedulesOnChannel(String channelKey) {
        return jdbc.query(
                ALLOCATION_COLUMNS_WITH_ALIAS + " FROM allocation a JOIN supply_window w ON a.window_id = w.id"
                        + " WHERE w.channel_id = ? AND a.status IN ('REQUESTED', 'APPROVED')"
                        + " AND a.scheduled_start_nanos IS NOT NULL ORDER BY a.id",
                ALLOCATION_MAPPER, channelKey);
    }

    /**
     * 判断同灌渠是否存在与 [startNanos, endNanos) 重叠的排班占用（左闭右开，相邻合法），
     * 可排除指定申请自身；取消的申请不占用灌渠。
     */
    public boolean existsOverlappingActiveSchedule(String channelId, long startNanos, long endNanos,
                                                   String excludeAllocationKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN supply_window w ON a.window_id = w.id"
                        + " WHERE w.channel_id = ? AND a.status IN ('REQUESTED', 'APPROVED')"
                        + " AND a.scheduled_start_nanos IS NOT NULL"
                        + " AND a.scheduled_start_nanos < ? AND ? < a.scheduled_end_nanos"
                        + " AND a.allocation_key <> ?",
                Integer.class, channelId, endNanos, startNanos, excludeAllocationKey);
        return count != null && count > 0;
    }

    /** 整批顺延：更新单个排班的当前时段并记录变更时间。 */
    public void updateAllocationSchedule(long id, long newStartNanos, long newEndNanos, long updatedNanos) {
        jdbc.update("UPDATE allocation SET scheduled_start_nanos = ?, scheduled_end_nanos = ?,"
                + " updated_nanos = ? WHERE id = ?", newStartNanos, newEndNanos, updatedNanos, id);
    }

    // ------------------------------------------------------------------
    // 历史用水核销
    // ------------------------------------------------------------------

    /** 插入用水核销快照并返回主键；usageKey 唯一冲突由调用方转 409。 */
    public long insertWaterUsage(String usageKey, String allocationKey, long windowId, BigDecimal volume,
                                 Long scheduleStartNanos, Long scheduleEndNanos, String actor,
                                 long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO water_usage (usage_key, allocation_key, window_id, volume,"
                            + " schedule_start_nanos, schedule_end_nanos, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, usageKey);
            ps.setString(2, allocationKey);
            ps.setLong(3, windowId);
            ps.setBigDecimal(4, volume);
            if (scheduleStartNanos == null || scheduleEndNanos == null) {
                ps.setObject(5, null);
                ps.setObject(6, null);
            } else {
                ps.setLong(5, scheduleStartNanos);
                ps.setLong(6, scheduleEndNanos);
            }
            ps.setString(7, actor);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销记录，不存在返回 null。 */
    public WaterUsageRow findWaterUsageByKey(String usageKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, usage_key, allocation_key, window_id, volume, schedule_start_nanos,"
                            + " schedule_end_nanos, actor, created_nanos FROM water_usage WHERE usage_key = ?",
                    WATER_USAGE_MAPPER, usageKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请的全部历史核销，按主键升序。 */
    public List<WaterUsageRow> listWaterUsagesByAllocation(String allocationKey) {
        return jdbc.query(
                "SELECT id, usage_key, allocation_key, window_id, volume, schedule_start_nanos,"
                        + " schedule_end_nanos, actor, created_nanos FROM water_usage"
                        + " WHERE allocation_key = ? ORDER BY id",
                WATER_USAGE_MAPPER, allocationKey);
    }

    /** 申请已核销水量合计，无则 0。 */
    public BigDecimal sumSettledVolume(String allocationKey) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume), 0) FROM water_usage WHERE allocation_key = ?",
                BigDecimal.class, allocationKey);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
