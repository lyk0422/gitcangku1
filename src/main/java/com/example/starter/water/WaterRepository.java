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

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。
     * creditCoefficient 为每毫米降雨抵扣系数（立方米/毫米），null 表示不参与降雨抵扣。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status,
                                BigDecimal creditCoefficient,
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

    /** 降雨确认证据行，不可变。 */
    public record RainConfirmationRow(long id, long rainRecordId, String confirmer, long createdNanos) {
    }

    /** 降雨记录行；effectiveNanos 未生效为 null；revokedNanos/revokedBy 未撤销为 null。 */
    public record RainRecordRow(long id, String rainKey, long windowId, BigDecimal rainMillis,
                                long observedNanos, String status, String registeredBy, long createdNanos,
                                Long effectiveNanos, Long revokedNanos, String revokedBy) {
    }

    /** 核销单行，创建后不可变。 */
    public record WriteoffRow(long id, String writeoffKey, long windowId, BigDecimal totalAmount,
                              BigDecimal totalCredit, BigDecimal totalQuota, String status, String actor,
                              long createdNanos) {
    }

    /** 核销明细行，创建后不可变；coefficient 为结算时申请系数快照，appliedRainKeys 为 JSON 数组字符串。 */
    public record WriteoffItemRow(long id, long writeoffId, String allocationKey, int seqNo, BigDecimal amount,
                                  BigDecimal credit, BigDecimal quotaDeduction, BigDecimal coefficient,
                                  String appliedRainKeys) {
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
            rs.getBigDecimal("credit_coefficient"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " credit_coefficient, created_nanos, updated_nanos";

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

    private static final RowMapper<RainRecordRow> RAIN_MAPPER = (rs, n) -> new RainRecordRow(
            rs.getLong("id"), rs.getString("rain_key"), rs.getLong("window_id"),
            rs.getBigDecimal("rain_millis"), rs.getLong("observed_nanos"), rs.getString("status"),
            rs.getString("registered_by"), rs.getLong("created_nanos"),
            rs.getObject("effective_nanos") == null ? null : rs.getLong("effective_nanos"),
            rs.getObject("revoked_nanos") == null ? null : rs.getLong("revoked_nanos"),
            rs.getString("revoked_by"));

    private static final String RAIN_SELECT =
            "SELECT id, rain_key, window_id, rain_millis, observed_nanos, status, registered_by,"
                    + " created_nanos, effective_nanos, revoked_nanos, revoked_by";

    private static final RowMapper<RainConfirmationRow> RAIN_CONFIRMATION_MAPPER = (rs, n) ->
            new RainConfirmationRow(rs.getLong("id"), rs.getLong("rain_record_id"),
                    rs.getString("confirmer"), rs.getLong("created_nanos"));

    private static final RowMapper<WriteoffRow> WRITEOFF_MAPPER = (rs, n) -> new WriteoffRow(
            rs.getLong("id"), rs.getString("writeoff_key"), rs.getLong("window_id"),
            rs.getBigDecimal("total_amount"), rs.getBigDecimal("total_credit"),
            rs.getBigDecimal("total_quota"), rs.getString("status"), rs.getString("actor"),
            rs.getLong("created_nanos"));

    private static final String WRITEOFF_SELECT =
            "SELECT id, writeoff_key, window_id, total_amount, total_credit, total_quota, status,"
                    + " actor, created_nanos";

    private static final RowMapper<WriteoffItemRow> WRITEOFF_ITEM_MAPPER = (rs, n) -> new WriteoffItemRow(
            rs.getLong("id"), rs.getLong("writeoff_id"), rs.getString("allocation_key"),
            rs.getInt("seq_no"), rs.getBigDecimal("amount"), rs.getBigDecimal("credit"),
            rs.getBigDecimal("quota_deduction"),
            rs.getObject("coefficient") == null ? null : rs.getBigDecimal("coefficient"),
            rs.getString("applied_rains"));

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

    /** 插入申请（初始 REQUESTED）并返回主键；creditCoefficient 为 null 表示不参与降雨抵扣。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, BigDecimal creditCoefficient, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount, requester,"
                            + " credit_coefficient, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, ?, 'REQUESTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            ps.setBigDecimal(6, creditCoefficient);
            ps.setLong(7, nowNanos);
            ps.setLong(8, nowNanos);
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
    // 降雨记录与确认
    // ------------------------------------------------------------------

    /** 插入降雨记录（初始 PENDING）并返回主键。 */
    public long insertRainRecord(String rainKey, long windowId, BigDecimal rainMillis, long observedNanos,
                                 String registeredBy, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // 显式声明仅 id 为生成键：表内含生成列 effective_window_id，RETURN_GENERATED_KEYS 会返回多键
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rain_record (rain_key, window_id, rain_millis, observed_nanos, status,"
                            + " registered_by, created_nanos, effective_nanos, revoked_nanos, revoked_by)"
                            + " VALUES (?, ?, ?, ?, 'PENDING', ?, ?, NULL, NULL, NULL)",
                    new String[]{"id"});
            ps.setString(1, rainKey);
            ps.setLong(2, windowId);
            ps.setBigDecimal(3, rainMillis);
            ps.setLong(4, observedNanos);
            ps.setString(5, registeredBy);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询降雨记录，不存在返回 null。 */
    public RainRecordRow findRainByKey(String rainKey) {
        try {
            return jdbc.queryForObject(RAIN_SELECT + " FROM rain_record WHERE rain_key = ?",
                    RAIN_MAPPER, rainKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键锁定降雨记录行（FOR UPDATE），不存在返回 null。 */
    public RainRecordRow lockRainByKey(String rainKey) {
        try {
            return jdbc.queryForObject(RAIN_SELECT + " FROM rain_record WHERE rain_key = ? FOR UPDATE",
                    RAIN_MAPPER, rainKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 判断窗口是否已存在 EFFECTIVE 降雨记录（同窗口唯一生效约束的事务内预检）。 */
    public boolean existsEffectiveRain(long windowId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rain_record WHERE window_id = ? AND status = 'EFFECTIVE'",
                Integer.class, windowId);
        return count != null && count > 0;
    }

    /** 窗口全部降雨记录，按主键升序（登记先后稳定排序）。 */
    public List<RainRecordRow> listRainRecords(long windowId) {
        return jdbc.query(RAIN_SELECT + " FROM rain_record WHERE window_id = ? ORDER BY id",
                RAIN_MAPPER, windowId);
    }

    /** 窗口当前 EFFECTIVE 降雨记录，按主键升序（通常至多一条）。 */
    public List<RainRecordRow> listEffectiveRain(long windowId) {
        return jdbc.query(
                RAIN_SELECT + " FROM rain_record WHERE window_id = ? AND status = 'EFFECTIVE' ORDER BY id",
                RAIN_MAPPER, windowId);
    }

    /** 插入确认证据（每名操作人对同一记录至多一次，由唯一约束保证）。 */
    public void insertRainConfirmation(long rainRecordId, String confirmer, long createdNanos) {
        jdbc.update("INSERT INTO rain_confirmation (rain_record_id, confirmer, created_nanos)"
                + " VALUES (?, ?, ?)", rainRecordId, confirmer, createdNanos);
    }

    /** 统计记录当前确认人数。 */
    public int countRainConfirmations(long rainRecordId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rain_confirmation WHERE rain_record_id = ?",
                Integer.class, rainRecordId);
        return count == null ? 0 : count;
    }

    /** 记录全部确认证据，按主键升序（确认先后稳定排序）。 */
    public List<RainConfirmationRow> listRainConfirmations(long rainRecordId) {
        return jdbc.query(
                "SELECT id, rain_record_id, confirmer, created_nanos FROM rain_confirmation"
                        + " WHERE rain_record_id = ? ORDER BY id",
                RAIN_CONFIRMATION_MAPPER, rainRecordId);
    }

    /** 降雨记录生效：置 EFFECTIVE 并记录生效时刻。 */
    public void markRainEffective(long rainRecordId, long effectiveNanos) {
        jdbc.update("UPDATE rain_record SET status = 'EFFECTIVE', effective_nanos = ? WHERE id = ?",
                effectiveNanos, rainRecordId);
    }

    /** 撤销降雨记录：置 REVOKED 并记录撤销时刻与操作人；不改写 effective_nanos 历史证据。 */
    public void markRainRevoked(long rainRecordId, long revokedNanos, String revokedBy) {
        jdbc.update("UPDATE rain_record SET status = 'REVOKED', revoked_nanos = ?, revoked_by = ? WHERE id = ?",
                revokedNanos, revokedBy, rainRecordId);
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    /** 插入核销单（SETTLED）并返回主键；金额已满足守恒约束。 */
    public long insertWriteoff(String writeoffKey, long windowId, BigDecimal totalAmount, BigDecimal totalCredit,
                               BigDecimal totalQuota, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO writeoff (writeoff_key, window_id, total_amount, total_credit, total_quota,"
                            + " status, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, 'SETTLED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setLong(2, windowId);
            ps.setBigDecimal(3, totalAmount);
            ps.setBigDecimal(4, totalCredit);
            ps.setBigDecimal(5, totalQuota);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 插入不可变核销明细并返回主键；appliedRainKeys 为 JSON 数组字符串快照。 */
    public long insertWriteoffItem(long writeoffId, int seqNo, String allocationKey, BigDecimal amount,
                                   BigDecimal credit, BigDecimal quotaDeduction, BigDecimal coefficient,
                                   String appliedRainKeys) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO writeoff_item (writeoff_id, allocation_key, seq_no, amount, credit,"
                            + " quota_deduction, coefficient, applied_rains)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, writeoffId);
            ps.setString(2, allocationKey);
            ps.setInt(3, seqNo);
            ps.setBigDecimal(4, amount);
            ps.setBigDecimal(5, credit);
            ps.setBigDecimal(6, quotaDeduction);
            ps.setBigDecimal(7, coefficient);
            ps.setString(8, appliedRainKeys);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销单，不存在返回 null。 */
    public WriteoffRow findWriteoffByKey(String writeoffKey) {
        try {
            return jdbc.queryForObject(WRITEOFF_SELECT + " FROM writeoff WHERE writeoff_key = ?",
                    WRITEOFF_MAPPER, writeoffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 核销单全部明细，按 seq_no 升序稳定排序。 */
    public List<WriteoffItemRow> listWriteoffItems(long writeoffId) {
        return jdbc.query(
                "SELECT id, writeoff_id, allocation_key, seq_no, amount, credit, quota_deduction, coefficient,"
                        + " applied_rains FROM writeoff_item WHERE writeoff_id = ? ORDER BY seq_no",
                WRITEOFF_ITEM_MAPPER, writeoffId);
    }

    /** 窗口全部核销单，按主键升序（核销先后稳定排序）。 */
    public List<WriteoffRow> listWriteoffs(long windowId) {
        return jdbc.query(WRITEOFF_SELECT + " FROM writeoff WHERE window_id = ? ORDER BY id",
                WRITEOFF_MAPPER, windowId);
    }

    /**
     * 条件扣减持有配额：仅当 held_amount >= deduction 时扣减并返回 true，否则不改动返回 false。
     * 用于在窗口/申请行锁内做最终余额裁决，保证持有额度不会为负。
     */
    public boolean deductHeldAmountIfSufficient(long allocationId, BigDecimal deduction, long updatedNanos) {
        int rows = jdbc.update(
                "UPDATE allocation SET held_amount = held_amount - ?, updated_nanos = ?"
                        + " WHERE id = ? AND held_amount >= ?",
                deduction, updatedNanos, allocationId, deduction);
        return rows > 0;
    }
}
