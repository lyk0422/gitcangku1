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

    /** 灌渠中断行；中断窗口与顺延分钟数登记后不可变。 */
    public record OutageRow(long id, String outageKey, String channelId, long startNanos, long endNanos,
                            String reason, int postponeMinutes, String status, long createdNanos) {
    }

    /** 排班行；original* 为发布时原始窗口，start/end 为当前窗口，顺延只改当前窗口。 */
    public record ScheduleRow(long id, String scheduleKey, long windowId, String channelId,
                              long originalStartNanos, long originalEndNanos, long startNanos, long endNanos,
                              String status, long createdNanos, long updatedNanos) {
    }

    /** 顺延对应关系行，插入后不可变。 */
    public record PostponementRow(long id, long scheduleId, long outageId, String outageKey, String reason,
                                  int postponeMinutes, long originalStartNanos, long originalEndNanos,
                                  long fromStartNanos, long fromEndNanos, long toStartNanos, long toEndNanos,
                                  long createdNanos) {
    }

    /** 历史用水核销行，窗口字段为核销当时排班快照。 */
    public record SettlementRow(long id, String settlementKey, long scheduleId, String scheduleKey,
                                long scheduledStartNanos, long scheduledEndNanos, BigDecimal volume,
                                String status, long createdNanos) {
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

    private static final RowMapper<OutageRow> OUTAGE_MAPPER = (rs, n) -> new OutageRow(
            rs.getLong("id"), rs.getString("outage_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"), rs.getString("reason"),
            rs.getInt("postpone_minutes"), rs.getString("status"), rs.getLong("created_nanos"));

    private static final String OUTAGE_SELECT =
            "SELECT id, outage_key, channel_id, start_nanos, end_nanos, reason, postpone_minutes, status,"
                    + " created_nanos";

    private static final RowMapper<ScheduleRow> SCHEDULE_MAPPER = (rs, n) -> new ScheduleRow(
            rs.getLong("id"), rs.getString("schedule_key"), rs.getLong("window_id"),
            rs.getString("channel_id"), rs.getLong("original_start_nanos"), rs.getLong("original_end_nanos"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String SCHEDULE_SELECT =
            "SELECT id, schedule_key, window_id, channel_id, original_start_nanos, original_end_nanos,"
                    + " start_nanos, end_nanos, status, created_nanos, updated_nanos";

    private static final RowMapper<PostponementRow> POSTPONEMENT_MAPPER = (rs, n) -> new PostponementRow(
            rs.getLong("id"), rs.getLong("schedule_id"), rs.getLong("outage_id"),
            rs.getString("outage_key"), rs.getString("reason"), rs.getInt("postpone_minutes"),
            rs.getLong("original_start_nanos"), rs.getLong("original_end_nanos"),
            rs.getLong("from_start_nanos"), rs.getLong("from_end_nanos"),
            rs.getLong("to_start_nanos"), rs.getLong("to_end_nanos"), rs.getLong("created_nanos"));

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"), rs.getString("settlement_key"), rs.getLong("schedule_id"),
            rs.getString("schedule_key"), rs.getLong("scheduled_start_nanos"), rs.getLong("scheduled_end_nanos"),
            rs.getBigDecimal("volume"), rs.getString("status"), rs.getLong("created_nanos"));

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
    // 灌渠中断与排班顺延
    // ------------------------------------------------------------------

    /**
     * 获取渠道行锁：锁行不存在时先幂等插入（不同事务并发插入靠主键冲突，败者重读加锁），
     * 再 SELECT ... FOR UPDATE，使同一渠道的中断登记与排班发布按事务提交顺序串行。
     */
    public void lockChannel(String channelId, long nowNanos) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM channel_mutex WHERE channel_id = ?",
                Integer.class, channelId);
        if (exists == null || exists == 0) {
            try {
                jdbc.update("INSERT INTO channel_mutex (channel_id, created_nanos) VALUES (?, ?)",
                        channelId, nowNanos);
            } catch (DuplicateKeyException ignored) {
                // 并发事务已插入锁行，下面加锁即可
            }
        }
        jdbc.queryForObject("SELECT channel_id FROM channel_mutex WHERE channel_id = ? FOR UPDATE",
                String.class, channelId);
    }

    /** 插入灌渠中断（REGISTERED）并返回主键。 */
    public long insertOutage(String outageKey, String channelId, long startNanos, long endNanos,
                             String reason, int postponeMinutes, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO canal_outage (outage_key, channel_id, start_nanos, end_nanos, reason,"
                            + " postpone_minutes, status, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'REGISTERED', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, outageKey);
            ps.setString(2, channelId);
            ps.setLong(3, startNanos);
            ps.setLong(4, endNanos);
            ps.setString(5, reason);
            ps.setInt(6, postponeMinutes);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询中断，不存在返回 null。 */
    public OutageRow findOutageByKey(String outageKey) {
        try {
            return jdbc.queryForObject(OUTAGE_SELECT + " FROM canal_outage WHERE outage_key = ?",
                    OUTAGE_MAPPER, outageKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询中断，不存在返回 null。 */
    public OutageRow findOutageById(long id) {
        try {
            return jdbc.queryForObject(OUTAGE_SELECT + " FROM canal_outage WHERE id = ?", OUTAGE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 列出某渠道与 [startNanos, endNanos) 按 UTC 左闭右开交叉的已登记中断，
     * 在渠道行锁内调用，用于拒绝向中断窗口内发布排班；按起始时刻升序。
     */
    public List<OutageRow> listOverlappingOutages(String channelId, long startNanos, long endNanos) {
        return jdbc.query(
                OUTAGE_SELECT + " FROM canal_outage WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos"
                        + " ORDER BY start_nanos, id",
                OUTAGE_MAPPER, channelId, endNanos, startNanos);
    }

    /** 渠道全部已登记中断，按主键升序。 */
    public List<OutageRow> listOutagesByChannel(String channelId) {
        return jdbc.query(OUTAGE_SELECT + " FROM canal_outage WHERE channel_id = ? ORDER BY id",
                OUTAGE_MAPPER, channelId);
    }

    /** 插入排班（SCHEDULED，当前窗口等于原始窗口）并返回主键。 */
    public long insertSchedule(String scheduleKey, long windowId, String channelId,
                               long startNanos, long endNanos, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO schedule_slot (schedule_key, window_id, channel_id, original_start_nanos,"
                            + " original_end_nanos, start_nanos, end_nanos, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scheduleKey);
            ps.setLong(2, windowId);
            ps.setString(3, channelId);
            ps.setLong(4, startNanos);
            ps.setLong(5, endNanos);
            ps.setLong(6, startNanos);
            ps.setLong(7, endNanos);
            ps.setLong(8, createdNanos);
            ps.setLong(9, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询排班，不存在返回 null。 */
    public ScheduleRow findScheduleByKey(String scheduleKey) {
        try {
            return jdbc.queryForObject(SCHEDULE_SELECT + " FROM schedule_slot WHERE schedule_key = ?",
                    SCHEDULE_MAPPER, scheduleKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询排班，不存在返回 null。 */
    public ScheduleRow findScheduleById(long id) {
        try {
            return jdbc.queryForObject(SCHEDULE_SELECT + " FROM schedule_slot WHERE id = ?", SCHEDULE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定排班行（FOR UPDATE），不存在返回 null。 */
    public ScheduleRow lockScheduleByKey(String scheduleKey) {
        try {
            return jdbc.queryForObject(SCHEDULE_SELECT + " FROM schedule_slot WHERE schedule_key = ? FOR UPDATE",
                    SCHEDULE_MAPPER, scheduleKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 列出某渠道与 [startNanos, endNanos) 按 UTC 左闭右开交叉的排班（含各状态），
     * 在渠道行锁内调用，保证与并发发布/中断互不漏判；按当前起始时刻升序。
     */
    public List<ScheduleRow> listOverlappingSchedules(String channelId, long startNanos, long endNanos) {
        return jdbc.query(
                SCHEDULE_SELECT + " FROM schedule_slot WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos"
                        + " ORDER BY start_nanos, id",
                SCHEDULE_MAPPER, channelId, endNanos, startNanos);
    }

    /**
     * 列出某渠道与 [startNanos, endNanos) 按 UTC 左闭右开交叉的排班（含各状态）并对行加 FOR UPDATE 锁，
     * 在渠道行锁内调用，保证与并发用水核销互斥；按当前起始时刻升序。
     */
    public List<ScheduleRow> lockOverlappingSchedules(String channelId, long startNanos, long endNanos) {
        return jdbc.query(
                SCHEDULE_SELECT + " FROM schedule_slot WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos"
                        + " ORDER BY start_nanos, id FOR UPDATE",
                SCHEDULE_MAPPER, channelId, endNanos, startNanos);
    }

    /** 渠道全部排班，按主键升序。 */
    public List<ScheduleRow> listSchedulesByChannel(String channelId) {
        return jdbc.query(SCHEDULE_SELECT + " FROM schedule_slot WHERE channel_id = ? ORDER BY id",
                SCHEDULE_MAPPER, channelId);
    }

    /** 配水窗口内全部排班，按主键升序。 */
    public List<ScheduleRow> listSchedulesByWindow(long windowId) {
        return jdbc.query(SCHEDULE_SELECT + " FROM schedule_slot WHERE window_id = ? ORDER BY id",
                SCHEDULE_MAPPER, windowId);
    }

    /** 整批顺延：更新排班当前窗口与变更时间。 */
    public void moveSchedule(long scheduleId, long newStartNanos, long newEndNanos, long updatedNanos) {
        jdbc.update("UPDATE schedule_slot SET start_nanos = ?, end_nanos = ?, updated_nanos = ? WHERE id = ?",
                newStartNanos, newEndNanos, updatedNanos, scheduleId);
    }

    /** 插入不可变顺延对应关系。 */
    public void insertPostponement(long scheduleId, long outageId, String outageKey, String reason,
                                   int postponeMinutes, long originalStartNanos, long originalEndNanos,
                                   long fromStartNanos, long fromEndNanos, long toStartNanos, long toEndNanos,
                                   long createdNanos) {
        jdbc.update("INSERT INTO schedule_postponement (schedule_id, outage_id, outage_key, reason,"
                + " postpone_minutes, original_start_nanos, original_end_nanos, from_start_nanos, from_end_nanos,"
                + " to_start_nanos, to_end_nanos, created_nanos)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                scheduleId, outageId, outageKey, reason, postponeMinutes,
                originalStartNanos, originalEndNanos, fromStartNanos, fromEndNanos,
                toStartNanos, toEndNanos, createdNanos);
    }

    /** 排班的全部顺延记录（顺延链），按发生顺序（主键）升序。 */
    public List<PostponementRow> listPostponements(long scheduleId) {
        return jdbc.query(
                "SELECT id, schedule_id, outage_id, outage_key, reason, postpone_minutes, original_start_nanos,"
                        + " original_end_nanos, from_start_nanos, from_end_nanos, to_start_nanos, to_end_nanos,"
                        + " created_nanos FROM schedule_postponement WHERE schedule_id = ? ORDER BY id",
                POSTPONEMENT_MAPPER, scheduleId);
    }

    /** 某中断首次登记时产生的全部顺延记录，按排班当前起始时刻、主键升序。 */
    public List<PostponementRow> listPostponementsByOutage(long outageId) {
        return jdbc.query(
                "SELECT p.id, p.schedule_id, p.outage_id, p.outage_key, p.reason, p.postpone_minutes,"
                        + " p.original_start_nanos, p.original_end_nanos, p.from_start_nanos, p.from_end_nanos,"
                        + " p.to_start_nanos, p.to_end_nanos, p.created_nanos"
                        + " FROM schedule_postponement p JOIN schedule_slot s ON s.id = p.schedule_id"
                        + " WHERE p.outage_id = ? ORDER BY s.start_nanos, p.id",
                POSTPONEMENT_MAPPER, outageId);
    }

    /** 插入历史用水核销（SETTLED）并返回主键。 */
    public long insertSettlement(String settlementKey, long scheduleId, String scheduleKey,
                                 long scheduledStartNanos, long scheduledEndNanos, BigDecimal volume,
                                 long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO usage_settlement (settlement_key, schedule_id, schedule_key, scheduled_start_nanos,"
                            + " scheduled_end_nanos, volume, status, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'SETTLED', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlementKey);
            ps.setLong(2, scheduleId);
            ps.setString(3, scheduleKey);
            ps.setLong(4, scheduledStartNanos);
            ps.setLong(5, scheduledEndNanos);
            ps.setBigDecimal(6, volume);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销记录，不存在返回 null。 */
    public SettlementRow findSettlementByKey(String settlementKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, settlement_key, schedule_id, schedule_key, scheduled_start_nanos,"
                            + " scheduled_end_nanos, volume, status, created_nanos"
                            + " FROM usage_settlement WHERE settlement_key = ?",
                    SETTLEMENT_MAPPER, settlementKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 排班全部核销记录，按主键升序。 */
    public List<SettlementRow> listSettlementsBySchedule(long scheduleId) {
        return jdbc.query(
                "SELECT id, settlement_key, schedule_id, schedule_key, scheduled_start_nanos,"
                        + " scheduled_end_nanos, volume, status, created_nanos"
                        + " FROM usage_settlement WHERE schedule_id = ? ORDER BY id",
                SETTLEMENT_MAPPER, scheduleId);
    }

    /** 将排班置为已核销（COMPLETED）。 */
    public void markScheduleCompleted(long scheduleId, long updatedNanos) {
        jdbc.update("UPDATE schedule_slot SET status = 'COMPLETED', updated_nanos = ? WHERE id = ?",
                updatedNanos, scheduleId);
    }
}
