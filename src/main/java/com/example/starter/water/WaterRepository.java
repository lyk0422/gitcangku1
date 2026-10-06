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

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度。
     *  fieldId/irrigationNanos 为田块绑定信息，未绑定为 null。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, String requester, String status, Long fieldId,
                                Long irrigationNanos, long createdNanos, long updatedNanos) {
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
            rs.getObject("field_id") == null ? null : rs.getLong("field_id"),
            rs.getObject("irrigation_nanos") == null ? null : rs.getLong("irrigation_nanos"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, requester, status,"
                    + " field_id, irrigation_nanos, created_nanos, updated_nanos";

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
    // 田块入渗
    // ------------------------------------------------------------------

    /** 田块行；capacity/recoveryPerHour/enabledNanos 创建后不可变，version 随已批准序列变更 +1。 */
    public record FieldRow(long id, String fieldKey, String ownerUserId, long capacity, long recoveryPerHour,
                           long enabledNanos, long version, long createdNanos) {
    }

    /** 批准时冻结的入渗序列快照行。 */
    public record SnapshotRow(long id, long fieldId, long fieldVersion, String allocationKey,
                              long irrigationNanos, BigDecimal deductAmount, BigDecimal balanceAfter,
                              long computedNanos) {
    }

    /** 新增写入幂等行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record RequestRow(String requestKey, String identityJson, String response, long createdNanos) {
    }

    private static final RowMapper<FieldRow> FIELD_MAPPER = (rs, n) -> new FieldRow(
            rs.getLong("id"), rs.getString("field_key"), rs.getString("owner_user_id"),
            rs.getLong("capacity"), rs.getLong("recovery_per_hour"), rs.getLong("enabled_nanos"),
            rs.getLong("version"), rs.getLong("created_nanos"));

    private static final String FIELD_SELECT =
            "SELECT id, field_key, owner_user_id, capacity, recovery_per_hour, enabled_nanos,"
                    + " version, created_nanos";

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getLong("field_id"), rs.getLong("field_version"),
            rs.getString("allocation_key"), rs.getLong("irrigation_nanos"),
            rs.getBigDecimal("deduct_amount"), rs.getBigDecimal("balance_after"),
            rs.getLong("computed_nanos"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getString("request_key"), rs.getString("identity_json"), rs.getString("response"),
            rs.getLong("created_nanos"));

    /** 首次关联时插入田块（版本 0），返回主键；field_key 冲突抛 DuplicateKeyException。 */
    public long insertField(String fieldKey, String ownerUserId, long capacity, long recoveryPerHour,
                            long enabledNanos, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO field_plot (field_key, owner_user_id, capacity, recovery_per_hour,"
                            + " enabled_nanos, version, created_nanos) VALUES (?, ?, ?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fieldKey);
            ps.setString(2, ownerUserId);
            ps.setLong(3, capacity);
            ps.setLong(4, recoveryPerHour);
            ps.setLong(5, enabledNanos);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询田块，不存在返回 null。 */
    public FieldRow findFieldByKey(String fieldKey) {
        try {
            return jdbc.queryForObject(FIELD_SELECT + " FROM field_plot WHERE field_key = ?",
                    FIELD_MAPPER, fieldKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定田块行（FOR UPDATE），用于串行化批准/取消/转让的入渗重算。 */
    public FieldRow lockFieldById(long id) {
        try {
            return jdbc.queryForObject(FIELD_SELECT + " FROM field_plot WHERE id = ? FOR UPDATE",
                    FIELD_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 田块已批准序列版本 +1。 */
    public void incrementFieldVersion(long fieldId) {
        jdbc.update("UPDATE field_plot SET version = version + 1 WHERE id = ?", fieldId);
    }

    /** 插入田块绑定申请（初始 REQUESTED、持有 0）并返回主键。 */
    public long insertFieldAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                      String requester, long fieldId, long irrigationNanos, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, held_amount,"
                            + " requester, status, field_id, irrigation_nanos, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, 'REQUESTED', ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            ps.setLong(6, fieldId);
            ps.setLong(7, irrigationNanos);
            ps.setLong(8, nowNanos);
            ps.setLong(9, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 田块当前全部 APPROVED 申请，按灌溉时刻、申请键字典序排列（入渗扣量顺序）。 */
    public List<AllocationRow> listApprovedFieldAllocations(long fieldId) {
        return jdbc.query(ALLOCATION_SELECT
                        + " FROM allocation WHERE field_id = ? AND status = 'APPROVED'"
                        + " ORDER BY irrigation_nanos, allocation_key",
                ALLOCATION_MAPPER, fieldId);
    }

    /** 删除田块全部冻结快照（批准时整体重写前调用）。 */
    public void deleteSnapshots(long fieldId) {
        jdbc.update("DELETE FROM field_balance_snapshot WHERE field_id = ?", fieldId);
    }

    /** 插入一行冻结快照。 */
    public void insertSnapshot(long fieldId, long fieldVersion, String allocationKey, long irrigationNanos,
                               BigDecimal deductAmount, BigDecimal balanceAfter, long computedNanos) {
        jdbc.update("INSERT INTO field_balance_snapshot (field_id, field_version, allocation_key,"
                        + " irrigation_nanos, deduct_amount, balance_after, computed_nanos)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                fieldId, fieldVersion, allocationKey, irrigationNanos, deductAmount, balanceAfter,
                computedNanos);
    }

    /** 田块冻结快照，按灌溉时刻、申请键字典序。 */
    public List<SnapshotRow> listSnapshots(long fieldId) {
        return jdbc.query("SELECT id, field_id, field_version, allocation_key, irrigation_nanos,"
                        + " deduct_amount, balance_after, computed_nanos FROM field_balance_snapshot"
                        + " WHERE field_id = ? ORDER BY irrigation_nanos, allocation_key",
                SNAPSHOT_MAPPER, fieldId);
    }

    /** 按幂等键查询新增写入日志，不存在返回 null。 */
    public RequestRow findRequest(String requestKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT request_key, identity_json, response, created_nanos FROM request_log"
                            + " WHERE request_key = ?", REQUEST_MAPPER, requestKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 先占位插入新增写入日志（响应为空），同事务内业务成功后写回响应。 */
    public void insertRequest(String requestKey, String identityJson, long createdNanos) {
        jdbc.update("INSERT INTO request_log (request_key, identity_json, response, created_nanos)"
                + " VALUES (?, ?, NULL, ?)", requestKey, identityJson, createdNanos);
    }

    /** 写回新增写入首次成功响应。 */
    public void updateRequestResponse(String requestKey, String response) {
        jdbc.update("UPDATE request_log SET response = ? WHERE request_key = ?", response, requestKey);
    }
}
