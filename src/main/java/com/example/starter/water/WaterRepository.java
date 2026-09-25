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

    /** 供水窗口行；quarter 为 null 表示未标记季度，不参与结转。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, Integer quarter, long createdNanos) {
    }

    /** 配水申请行；可结转余量 = amount - consumedVolume - carriedOutVolume。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal consumedVolume, BigDecimal carriedOutVolume,
                                String requester, String status, long createdNanos, long updatedNanos) {
    }

    /** 限供行。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 季度结转流水行，不可变。 */
    public record CarryoverRow(long id, String carryoverKey, long sourceWindowId, long targetWindowId,
                               String userId, BigDecimal amount, String targetAllocationKey, long createdNanos) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"),
            rs.getObject("quarter") == null ? null : rs.getInt("quarter"),
            rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("consumed_volume"), rs.getBigDecimal("carried_out_volume"),
            rs.getString("requester"), rs.getString("status"), rs.getLong("created_nanos"),
            rs.getLong("updated_nanos"));

    private static final RowMapper<CurtailmentRow> CURTAILMENT_MAPPER = (rs, n) -> new CurtailmentRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getBigDecimal("volume"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_key"), rs.getString("operation"), rs.getString("params"),
            rs.getString("response"), rs.getLong("created_nanos"));

    private static final RowMapper<CarryoverRow> CARRYOVER_MAPPER = (rs, n) -> new CarryoverRow(
            rs.getLong("id"), rs.getString("carryover_key"), rs.getLong("source_window_id"),
            rs.getLong("target_window_id"), rs.getString("user_id"), rs.getBigDecimal("amount"),
            rs.getString("target_allocation_key"), rs.getLong("created_nanos"));

    private static final String WINDOW_COLUMNS =
            "id, window_key, channel_id, start_nanos, end_nanos, planned_volume, quarter, created_nanos";

    private static final String ALLOCATION_COLUMNS =
            "id, allocation_key, window_id, user_id, amount, consumed_volume, carried_out_volume,"
                    + " requester, status, created_nanos, updated_nanos";

    private static final String CARRYOVER_COLUMNS =
            "id, carryover_key, source_window_id, target_window_id, user_id, amount,"
                    + " target_allocation_key, created_nanos";

    private final JdbcTemplate jdbc;

    public WaterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入窗口并返回自增主键；quarter 为 null 表示未标记季度。 */
    public long insertWindow(String windowKey, String channelId, long startNanos, long endNanos,
                             BigDecimal plannedVolume, Integer quarter, long createdNanos) {
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
            if (quarter == null) {
                ps.setNull(6, java.sql.Types.TINYINT);
            } else {
                ps.setInt(6, quarter);
            }
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询窗口，不存在返回 null。 */
    public WindowRow findWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + WINDOW_COLUMNS + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准与限供。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + WINDOW_COLUMNS + " FROM supply_window WHERE id = ? FOR UPDATE",
                    WINDOW_MAPPER, id);
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
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
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
            return jdbc.queryForObject(
                    "SELECT " + ALLOCATION_COLUMNS + " FROM allocation WHERE allocation_key = ?",
                    ALLOCATION_MAPPER, allocationKey);
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
                "SELECT " + ALLOCATION_COLUMNS + " FROM allocation WHERE window_id = ? ORDER BY id",
                ALLOCATION_MAPPER, windowId);
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

    /** 插入直接处于 APPROVED 状态的申请（结转在目标窗口新建），返回主键。 */
    public long insertApprovedAllocation(String allocationKey, long windowId, String userId, BigDecimal amount,
                                         String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, amount, requester, status, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, 'APPROVED', ?, ?)", Statement.RETURN_GENERATED_KEYS);
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

    /** 用水户在窗口内全部 APPROVED 申请，按主键升序（结转扣减顺序）。 */
    public List<AllocationRow> listApprovedAllocations(long windowId, String userId) {
        return jdbc.query(
                "SELECT " + ALLOCATION_COLUMNS
                        + " FROM allocation WHERE window_id = ? AND user_id = ? AND status = 'APPROVED' ORDER BY id",
                ALLOCATION_MAPPER, windowId, userId);
    }

    /** 用水户在窗口内的全部申请（任意状态），按主键升序。 */
    public List<AllocationRow> listAllocations(long windowId, String userId) {
        return jdbc.query(
                "SELECT " + ALLOCATION_COLUMNS + " FROM allocation WHERE window_id = ? AND user_id = ? ORDER BY id",
                ALLOCATION_MAPPER, windowId, userId);
    }

    /** 用水户全部申请，按主键升序（跨窗口余量查询）。 */
    public List<AllocationRow> listAllocationsByUser(String userId) {
        return jdbc.query(
                "SELECT " + ALLOCATION_COLUMNS + " FROM allocation WHERE user_id = ? ORDER BY id",
                ALLOCATION_MAPPER, userId);
    }

    /**
     * 乐观扣减源申请的可结转余量：仅当申请仍为 APPROVED 且剩余余量足够时累加 carried_out_volume。
     * 返回受影响行数；0 表示并发下余量已被其他结转占用或状态已变化。
     */
    public int deductCarriedOut(long allocationId, BigDecimal amount) {
        return jdbc.update(
                "UPDATE allocation SET carried_out_volume = carried_out_volume + ?"
                        + " WHERE id = ? AND status = 'APPROVED'"
                        + " AND amount - consumed_volume - carried_out_volume >= ?",
                amount, allocationId, amount);
    }

    /** 写入不可变结转流水并返回主键。 */
    public long insertCarryover(String carryoverKey, long sourceWindowId, long targetWindowId, String userId,
                                BigDecimal amount, String targetAllocationKey, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO carryover (carryover_key, source_window_id, target_window_id, user_id, amount,"
                            + " target_allocation_key, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, carryoverKey);
            ps.setLong(2, sourceWindowId);
            ps.setLong(3, targetWindowId);
            ps.setString(4, userId);
            ps.setBigDecimal(5, amount);
            ps.setString(6, targetAllocationKey);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询结转流水，不存在返回 null。 */
    public CarryoverRow findCarryoverByKey(String carryoverKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + CARRYOVER_COLUMNS + " FROM carryover WHERE carryover_key = ?",
                    CARRYOVER_MAPPER, carryoverKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 全部结转流水，按主键升序。 */
    public List<CarryoverRow> listCarryovers() {
        return jdbc.query("SELECT " + CARRYOVER_COLUMNS + " FROM carryover ORDER BY id", CARRYOVER_MAPPER);
    }

    /** 指定用水户的结转流水，按主键升序。 */
    public List<CarryoverRow> listCarryoversByUser(String userId) {
        return jdbc.query(
                "SELECT " + CARRYOVER_COLUMNS + " FROM carryover WHERE user_id = ? ORDER BY id",
                CARRYOVER_MAPPER, userId);
    }
}
