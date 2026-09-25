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
                            BigDecimal plannedVolume, int quarter, long createdNanos) {
    }

    /** 配水申请行；carriedOut 为已结转出的水量，可结转余量 = amount - carriedOut。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal carriedOut, String requester, String status,
                                long createdNanos, long updatedNanos) {
    }

    /** 限供行。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    /** 季度结转流水行，写入后不可变。 */
    public record CarryoverRow(long id, String carryoverKey, String userId, long sourceAllocationId,
                               long sourceWindowId, long targetWindowId, long targetAllocationId,
                               BigDecimal amount, long createdNanos) {
    }

    /** 按用水户与季度查询的余量视图行（申请 JOIN 窗口）。 */
    public record RemainderRow(long windowId, String windowKey, int quarter, String allocationKey,
                               String status, BigDecimal amount, BigDecimal carriedOut) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getInt("quarter"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("carried_out"),
            rs.getString("requester"), rs.getString("status"), rs.getLong("created_nanos"),
            rs.getLong("updated_nanos"));

    private static final RowMapper<CarryoverRow> CARRYOVER_MAPPER = (rs, n) -> new CarryoverRow(
            rs.getLong("id"), rs.getString("carryover_key"), rs.getString("user_id"),
            rs.getLong("source_allocation_id"), rs.getLong("source_window_id"),
            rs.getLong("target_window_id"), rs.getLong("target_allocation_id"),
            rs.getBigDecimal("amount"), rs.getLong("created_nanos"));

    private static final RowMapper<RemainderRow> REMAINDER_MAPPER = (rs, n) -> new RemainderRow(
            rs.getLong("window_id"), rs.getString("window_key"), rs.getInt("quarter"),
            rs.getString("allocation_key"), rs.getString("status"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("carried_out"));

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
                             BigDecimal plannedVolume, int quarter, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO supply_window (window_key, channel_id, start_nanos, end_nanos, planned_volume, quarter, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, windowKey);
            ps.setString(2, channelId);
            ps.setLong(3, startNanos);
            ps.setLong(4, endNanos);
            ps.setBigDecimal(5, plannedVolume);
            ps.setInt(6, quarter);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询窗口，不存在返回 null。 */
    public WindowRow findWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, quarter, created_nanos"
                            + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准与限供。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_key, channel_id, start_nanos, end_nanos, planned_volume, quarter, created_nanos"
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

    /** 插入申请并返回主键；approved 为 true 时直接以 APPROVED 落库（结转目标申请）。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                 String requester, boolean approved, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, carried_out, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, allocationKey);
            ps.setLong(2, windowId);
            ps.setString(3, userId);
            ps.setBigDecimal(4, amount);
            ps.setString(5, requester);
            ps.setString(6, approved ? "APPROVED" : "REQUESTED");
            ps.setLong(7, nowNanos);
            ps.setLong(8, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询申请，不存在返回 null。 */
    public AllocationRow findAllocationByKey(String allocationKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, allocation_key, window_id, user_id, amount, carried_out, requester, status, created_nanos, updated_nanos"
                            + " FROM allocation WHERE allocation_key = ?", ALLOCATION_MAPPER, allocationKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询申请，不存在返回 null。 */
    public AllocationRow findAllocationById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, allocation_key, window_id, user_id, amount, carried_out, requester, status, created_nanos, updated_nanos"
                            + " FROM allocation WHERE id = ?", ALLOCATION_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 更新申请状态与变更时间。 */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ? WHERE id = ?",
                status, updatedNanos, id);
    }

    /** 窗口当前所有 APPROVED 申请水量之和（BigDecimal 精确求和），无则 0。 */
    public BigDecimal sumApprovedAmount(long windowId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM allocation WHERE window_id = ? AND status = 'APPROVED'",
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
                "SELECT id, allocation_key, window_id, user_id, amount, carried_out, requester, status, created_nanos, updated_nanos"
                        + " FROM allocation WHERE window_id = ? ORDER BY id", ALLOCATION_MAPPER, windowId);
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
    // 季度结转
    // ------------------------------------------------------------------

    /**
     * 原子扣减源申请的可结转余量：仅当申请仍为 APPROVED 且扣减后不超过原申请水量时生效。
     * 返回受影响行数；0 表示并发结转或状态已变化，调用方据此返回 409。
     */
    public int deductCarryableRemainder(long allocationId, BigDecimal amount, long updatedNanos) {
        return jdbc.update(
                "UPDATE allocation SET carried_out = carried_out + ?, updated_nanos = ?"
                        + " WHERE id = ? AND status = 'APPROVED' AND carried_out + ? <= amount",
                amount, updatedNanos, allocationId, amount);
    }

    /** 写入不可变结转流水并返回主键。 */
    public long insertCarryover(String carryoverKey, String userId, long sourceAllocationId,
                                long sourceWindowId, long targetWindowId, long targetAllocationId,
                                BigDecimal amount, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO carryover (carryover_key, user_id, source_allocation_id, source_window_id,"
                            + " target_window_id, target_allocation_id, amount, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, carryoverKey);
            ps.setString(2, userId);
            ps.setLong(3, sourceAllocationId);
            ps.setLong(4, sourceWindowId);
            ps.setLong(5, targetWindowId);
            ps.setLong(6, targetAllocationId);
            ps.setBigDecimal(7, amount);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询结转流水，不存在返回 null。 */
    public CarryoverRow findCarryoverByKey(String carryoverKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, carryover_key, user_id, source_allocation_id, source_window_id,"
                            + " target_window_id, target_allocation_id, amount, created_nanos"
                            + " FROM carryover WHERE carryover_key = ?", CARRYOVER_MAPPER, carryoverKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 查询结转流水；userId 为 null 时返回全部，按主键升序。 */
    public List<CarryoverRow> listCarryovers(String userId) {
        String sql = "SELECT id, carryover_key, user_id, source_allocation_id, source_window_id,"
                + " target_window_id, target_allocation_id, amount, created_nanos FROM carryover";
        if (userId == null) {
            return jdbc.query(sql + " ORDER BY id", CARRYOVER_MAPPER);
        }
        return jdbc.query(sql + " WHERE user_id = ? ORDER BY id", CARRYOVER_MAPPER, userId);
    }

    /** 按用水户与季度查询其全部申请的跨窗口余量视图，按窗口与申请主键升序。 */
    public List<RemainderRow> listRemainders(String userId, int quarter) {
        return jdbc.query(
                "SELECT a.window_id, w.window_key, w.quarter, a.allocation_key, a.status, a.amount, a.carried_out"
                        + " FROM allocation a JOIN supply_window w ON w.id = a.window_id"
                        + " WHERE a.user_id = ? AND w.quarter = ? ORDER BY a.window_id, a.id",
                REMAINDER_MAPPER, userId, quarter);
    }
}
