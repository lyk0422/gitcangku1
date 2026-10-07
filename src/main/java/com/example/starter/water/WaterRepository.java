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

    /** 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，executedAmount 为累计已执行水量。 */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, BigDecimal amount,
                                BigDecimal heldAmount, BigDecimal executedAmount, String requester, String status,
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

    /** 双池配置与当前体积行；面积为固定正整数（平方米），体积单位立方米，水位 = 体积 / 面积。 */
    public record BasinPairRow(long id, long windowId, long upperAreaM2, long lowerAreaM2,
                               BigDecimal upperVolumeM3, BigDecimal lowerVolumeM3,
                               BigDecimal upperMinM3, BigDecimal upperMaxM3,
                               BigDecimal lowerMinM3, BigDecimal lowerMaxM3,
                               long createdNanos, long updatedNanos) {
    }

    /** 闸门执行记录行，创建后不可变；水位为展示值（舍入不参与门禁）。 */
    public record ExecutionRow(long id, String executionKey, String allocationKey, long windowId,
                               BigDecimal volumeM3,
                               BigDecimal upperBeforeM3, BigDecimal upperAfterM3,
                               BigDecimal lowerBeforeM3, BigDecimal lowerAfterM3,
                               BigDecimal upperLevelBeforeM, BigDecimal upperLevelAfterM,
                               BigDecimal lowerLevelBeforeM, BigDecimal lowerLevelAfterM,
                               String actor, long createdNanos) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("created_nanos"));

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getBigDecimal("executed_amount"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, amount, held_amount, executed_amount, requester, status,"
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

    private static final RowMapper<BasinPairRow> BASIN_PAIR_MAPPER = (rs, n) -> new BasinPairRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getLong("upper_area_m2"),
            rs.getLong("lower_area_m2"), rs.getBigDecimal("upper_volume_m3"),
            rs.getBigDecimal("lower_volume_m3"), rs.getBigDecimal("upper_min_m3"),
            rs.getBigDecimal("upper_max_m3"), rs.getBigDecimal("lower_min_m3"),
            rs.getBigDecimal("lower_max_m3"), rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String BASIN_PAIR_SELECT =
            "SELECT id, window_id, upper_area_m2, lower_area_m2, upper_volume_m3, lower_volume_m3,"
                    + " upper_min_m3, upper_max_m3, lower_min_m3, lower_max_m3, created_nanos, updated_nanos";

    private static final RowMapper<ExecutionRow> EXECUTION_MAPPER = (rs, n) -> new ExecutionRow(
            rs.getLong("id"), rs.getString("execution_key"), rs.getString("allocation_key"),
            rs.getLong("window_id"), rs.getBigDecimal("volume_m3"),
            rs.getBigDecimal("upper_before_m3"), rs.getBigDecimal("upper_after_m3"),
            rs.getBigDecimal("lower_before_m3"), rs.getBigDecimal("lower_after_m3"),
            rs.getBigDecimal("upper_level_before_m"), rs.getBigDecimal("upper_level_after_m"),
            rs.getBigDecimal("lower_level_before_m"), rs.getBigDecimal("lower_level_after_m"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private static final String EXECUTION_SELECT =
            "SELECT id, execution_key, allocation_key, window_id, volume_m3,"
                    + " upper_before_m3, upper_after_m3, lower_before_m3, lower_after_m3,"
                    + " upper_level_before_m, upper_level_after_m, lower_level_before_m, lower_level_after_m,"
                    + " actor, created_nanos";

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

    /** 执行记账：累计已执行水量增加、持有额度等额扣减（不得为负由事务内校验保证）。 */
    public void addExecutedAmount(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET executed_amount = executed_amount + ?,"
                + " held_amount = held_amount - ?, updated_nanos = ? WHERE id = ?",
                delta, delta, updatedNanos, id);
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

    /** 插入双池配置并返回自增主键。 */
    public long insertBasinPair(long windowId, long upperAreaM2, long lowerAreaM2,
                                BigDecimal upperVolumeM3, BigDecimal lowerVolumeM3,
                                BigDecimal upperMinM3, BigDecimal upperMaxM3,
                                BigDecimal lowerMinM3, BigDecimal lowerMaxM3, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO basin_pair (window_id, upper_area_m2, lower_area_m2,"
                            + " upper_volume_m3, lower_volume_m3, upper_min_m3, upper_max_m3,"
                            + " lower_min_m3, lower_max_m3, created_nanos, updated_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, windowId);
            ps.setLong(2, upperAreaM2);
            ps.setLong(3, lowerAreaM2);
            ps.setBigDecimal(4, upperVolumeM3);
            ps.setBigDecimal(5, lowerVolumeM3);
            ps.setBigDecimal(6, upperMinM3);
            ps.setBigDecimal(7, upperMaxM3);
            ps.setBigDecimal(8, lowerMinM3);
            ps.setBigDecimal(9, lowerMaxM3);
            ps.setLong(10, nowNanos);
            ps.setLong(11, nowNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按窗口查询双池配置，不存在（普通窗口）返回 null。 */
    public BasinPairRow findBasinPairByWindowId(long windowId) {
        try {
            return jdbc.queryForObject(BASIN_PAIR_SELECT + " FROM basin_pair WHERE window_id = ?",
                    BASIN_PAIR_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按窗口锁定双池行（FOR UPDATE），用于串行化执行输水，不存在返回 null。 */
    public BasinPairRow lockBasinPairByWindowId(long windowId) {
        try {
            return jdbc.queryForObject(BASIN_PAIR_SELECT + " FROM basin_pair WHERE window_id = ? FOR UPDATE",
                    BASIN_PAIR_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 执行输水后更新双池当前体积（体积上下界由事务内校验与表 CHECK 双重保证）。 */
    public void updateBasinVolumes(long id, BigDecimal upperVolumeM3, BigDecimal lowerVolumeM3,
                                   long updatedNanos) {
        jdbc.update("UPDATE basin_pair SET upper_volume_m3 = ?, lower_volume_m3 = ?, updated_nanos = ?"
                + " WHERE id = ?", upperVolumeM3, lowerVolumeM3, updatedNanos, id);
    }

    /** 插入不可变闸门执行记录并返回主键。 */
    public long insertExecution(String executionKey, String allocationKey, long windowId, BigDecimal volumeM3,
                                BigDecimal upperBeforeM3, BigDecimal upperAfterM3,
                                BigDecimal lowerBeforeM3, BigDecimal lowerAfterM3,
                                BigDecimal upperLevelBeforeM, BigDecimal upperLevelAfterM,
                                BigDecimal lowerLevelBeforeM, BigDecimal lowerLevelAfterM,
                                String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO gate_execution (execution_key, allocation_key, window_id, volume_m3,"
                            + " upper_before_m3, upper_after_m3, lower_before_m3, lower_after_m3,"
                            + " upper_level_before_m, upper_level_after_m,"
                            + " lower_level_before_m, lower_level_after_m, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, executionKey);
            ps.setString(2, allocationKey);
            ps.setLong(3, windowId);
            ps.setBigDecimal(4, volumeM3);
            ps.setBigDecimal(5, upperBeforeM3);
            ps.setBigDecimal(6, upperAfterM3);
            ps.setBigDecimal(7, lowerBeforeM3);
            ps.setBigDecimal(8, lowerAfterM3);
            ps.setBigDecimal(9, upperLevelBeforeM);
            ps.setBigDecimal(10, upperLevelAfterM);
            ps.setBigDecimal(11, lowerLevelBeforeM);
            ps.setBigDecimal(12, lowerLevelAfterM);
            ps.setString(13, actor);
            ps.setLong(14, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询执行记录，不存在返回 null。 */
    public ExecutionRow findExecutionByKey(String executionKey) {
        try {
            return jdbc.queryForObject(EXECUTION_SELECT + " FROM gate_execution WHERE execution_key = ?",
                    EXECUTION_MAPPER, executionKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请全部执行记录（不可变），按主键升序。 */
    public List<ExecutionRow> listExecutionsByAllocation(String allocationKey) {
        return jdbc.query(EXECUTION_SELECT + " FROM gate_execution WHERE allocation_key = ? ORDER BY id",
                EXECUTION_MAPPER, allocationKey);
    }
}
