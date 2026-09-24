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

    /** 供水窗口行；droughtLevel 为当前旱情等级，version 为旱情乐观校验版本号。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, String droughtLevel, long version, long createdNanos) {
    }

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，
     * baseHeldAmount 为旱情削减前持有额度基线（旱情削减不改写）。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, String priority,
                                BigDecimal amount, BigDecimal heldAmount, BigDecimal baseHeldAmount,
                                String requester, String status, long createdNanos, long updatedNanos) {
    }

    /** 转让流水行，创建后不可变。 */
    public record TransferRow(long id, String transferKey, long windowId, String sourceAllocationKey,
                              String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 限供行。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 旱情削减声明行，创建后不可变。 */
    public record DroughtRow(long id, String curtailmentKey, long windowId, String level, int essentialPct,
                             int normalPct, int deferrablePct, long windowVersion, long createdNanos) {
    }

    /** 旱情削减逐申请明细行，创建后不可变。 */
    public record DroughtDetailRow(long id, long curtailmentId, String allocationKey, String priority,
                                   BigDecimal previousHeld, BigDecimal newHeld) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    private static final String WINDOW_SELECT =
            "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, drought_level, version,"
                    + " created_nanos";

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getString("drought_level"), rs.getLong("version"),
            rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getString("priority"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"), rs.getBigDecimal("base_held_amount"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, priority, amount, held_amount, base_held_amount,"
                    + " requester, status, created_nanos, updated_nanos";

    private static final RowMapper<TransferRow> TRANSFER_MAPPER = (rs, n) -> new TransferRow(
            rs.getLong("id"), rs.getString("transfer_key"), rs.getLong("window_id"),
            rs.getString("source_allocation_key"), rs.getString("target_allocation_key"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<CurtailmentRow> CURTAILMENT_MAPPER = (rs, n) -> new CurtailmentRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getBigDecimal("volume"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<DroughtRow> DROUGHT_MAPPER = (rs, n) -> new DroughtRow(
            rs.getLong("id"), rs.getString("curtailment_key"), rs.getLong("window_id"), rs.getString("level"),
            rs.getInt("essential_pct"), rs.getInt("normal_pct"), rs.getInt("deferrable_pct"),
            rs.getLong("window_version"), rs.getLong("created_nanos"));

    private static final String DROUGHT_SELECT =
            "SELECT id, curtailment_key, window_id, level, essential_pct, normal_pct, deferrable_pct,"
                    + " window_version, created_nanos";

    private static final RowMapper<DroughtDetailRow> DROUGHT_DETAIL_MAPPER = (rs, n) -> new DroughtDetailRow(
            rs.getLong("id"), rs.getLong("curtailment_id"), rs.getString("allocation_key"),
            rs.getString("priority"), rs.getBigDecimal("previous_held"), rs.getBigDecimal("new_held"));

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
            return jdbc.queryForObject(WINDOW_SELECT + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、限供与旱情等级变更。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(WINDOW_SELECT + " FROM supply_window WHERE id = ? FOR UPDATE",
                    WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 旱情等级变更：更新当前等级并递增版本号。 */
    public void updateWindowDrought(long id, String droughtLevel, long newVersion) {
        jdbc.update("UPDATE supply_window SET drought_level = ?, version = ? WHERE id = ?",
                droughtLevel, newVersion, id);
    }

    /** 判断同渠道是否存在与 [startNanos, endNanos) 重叠的窗口（相邻合法）。 */
    public boolean existsOverlappingWindow(String channelId, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supply_window WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelId, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 插入申请（初始 REQUESTED）并返回主键。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, String priority,
                                 BigDecimal amount, String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, priority, amount, held_amount,"
                            + " base_held_amount, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, 0, 0, ?, 'REQUESTED', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setString(4, priority);
            ps.setBigDecimal(5, amount);
            ps.setString(6, requester);
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
     * 更新申请状态与变更时间，并同步持有额度及其旱情基线：批准时两者都等于原申请水量，
     * 取消时两者归零，REQUESTED 保持不变。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END,"
                + " base_held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE base_held_amount END WHERE id = ?",
                status, updatedNanos, status, status, status, status, id);
    }

    /** 转让扣减源持有额度及其旱情基线（不得为负由事务内校验保证）并记录变更时间。 */
    public void decrementHeldAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?,"
                + " base_held_amount = base_held_amount - ?, updated_nanos = ? WHERE id = ?",
                delta, delta, updatedNanos, id);
    }

    /** 旱情削减/回补：仅调整当前持有额度（不改写基线与原申请水量）并记录变更时间。 */
    public void setHeldAmount(long id, BigDecimal newHeld, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = ?, updated_nanos = ? WHERE id = ?",
                newHeld, updatedNanos, id);
    }

    /** 窗口全部 APPROVED 申请，按申请业务键升序（旱情同级取整差额由首笔承担）。 */
    public List<AllocationRow> listApprovedAllocations(long windowId) {
        return jdbc.query(
                ALLOCATION_SELECT + " FROM allocation WHERE window_id = ? AND status = 'APPROVED'"
                        + " ORDER BY allocation_key",
                ALLOCATION_MAPPER, windowId);
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

    /** 插入旱情削减声明并返回主键。 */
    public long insertDrought(String curtailmentKey, long windowId, String level, int essentialPct,
                              int normalPct, int deferrablePct, long windowVersion, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drought_curtailment (curtailment_key, window_id, level, essential_pct,"
                            + " normal_pct, deferrable_pct, window_version, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, curtailmentKey);
            ps.setLong(2, windowId);
            ps.setString(3, level);
            ps.setInt(4, essentialPct);
            ps.setInt(5, normalPct);
            ps.setInt(6, deferrablePct);
            ps.setLong(7, windowVersion);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询旱情削减声明，不存在返回 null。 */
    public DroughtRow findDroughtByKey(String curtailmentKey) {
        try {
            return jdbc.queryForObject(DROUGHT_SELECT + " FROM drought_curtailment WHERE curtailment_key = ?",
                    DROUGHT_MAPPER, curtailmentKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口最近一次旱情削减声明，从未声明返回 null。 */
    public DroughtRow findLatestDrought(long windowId) {
        List<DroughtRow> rows = jdbc.query(
                DROUGHT_SELECT + " FROM drought_curtailment WHERE window_id = ? ORDER BY id DESC LIMIT 1",
                DROUGHT_MAPPER, windowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 窗口全部旱情削减声明（含 NONE 恢复），按主键升序。 */
    public List<DroughtRow> listDroughts(long windowId) {
        return jdbc.query(DROUGHT_SELECT + " FROM drought_curtailment WHERE window_id = ? ORDER BY id",
                DROUGHT_MAPPER, windowId);
    }

    /** 插入旱情削减逐申请明细并返回主键。 */
    public long insertDroughtDetail(long curtailmentId, String allocationKey, String priority,
                                    BigDecimal previousHeld, BigDecimal newHeld) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drought_curtailment_detail (curtailment_id, allocation_key, priority,"
                            + " previous_held, new_held) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, curtailmentId);
            ps.setString(2, allocationKey);
            ps.setString(3, priority);
            ps.setBigDecimal(4, previousHeld);
            ps.setBigDecimal(5, newHeld);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 某次旱情削减声明的全部明细，按申请业务键升序。 */
    public List<DroughtDetailRow> listDroughtDetails(long curtailmentId) {
        return jdbc.query(
                "SELECT id, curtailment_id, allocation_key, priority, previous_held, new_held"
                        + " FROM drought_curtailment_detail WHERE curtailment_id = ? ORDER BY allocation_key",
                DROUGHT_DETAIL_MAPPER, curtailmentId);
    }
}
