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

    /** 供水窗口行；version 为旱情声明版本，每次显式声明显式生效加 1。 */
    public record WindowRow(long id, String windowKey, String channelId, long startNanos, long endNanos,
                            BigDecimal plannedVolume, long version, long createdNanos) {
    }

    /**
     * 配水申请行；amount 为不可改写的原申请水量，heldAmount 为当前持有额度，
     * baselineHeldAmount 为旱情削减基准持有额度（完整额度，削减/转出不改写）。
     */
    public record AllocationRow(long id, String allocationKey, long windowId, String userId, String priority,
                                BigDecimal amount, BigDecimal heldAmount, BigDecimal baselineHeldAmount,
                                String requester, String status, long createdNanos, long updatedNanos) {
    }

    /** 转让流水行，创建后不可变。 */
    public record TransferRow(long id, String transferKey, long windowId, String sourceAllocationKey,
                              String targetAllocationKey, BigDecimal amount, String actor, long createdNanos) {
    }

    /** 限供行（按总量限供，与旱情比例削减相互独立）。 */
    public record CurtailmentRow(long id, long windowId, BigDecimal volume, String status, long createdNanos,
                                 Long cancelledNanos) {
    }

    /** 旱情分级比例削减声明行；同一窗口至多一条 ACTIVE。 */
    public record DroughtRow(long id, String curtailmentKey, long windowId, String level,
                             int essentialPct, int normalPct, int deferrablePct, long expectedVersion,
                             String status, long createdNanos, Long supersededNanos) {
    }

    /** 旱情削减逐笔明细行，创建后不可变。 */
    public record DroughtDetailRow(long id, long curtailmentId, long allocationId, String allocationKey,
                                   String priority, BigDecimal originalHeld, BigDecimal targetHeld,
                                   BigDecimal reducedAmount, long createdNanos) {
    }

    /** 幂等命令行；response 为 null 表示响应尚未写回（同事务内）。 */
    public record CommandRow(String commandKey, String operation, String params, String response,
                             long createdNanos) {
    }

    private static final RowMapper<WindowRow> WINDOW_MAPPER = (rs, n) -> new WindowRow(
            rs.getLong("id"), rs.getString("window_key"), rs.getString("channel_id"),
            rs.getLong("start_nanos"), rs.getLong("end_nanos"),
            rs.getBigDecimal("planned_volume"), rs.getLong("version"), rs.getLong("created_nanos"));

    private static final String WINDOW_COLUMNS =
            "id, window_key, channel_id, start_nanos, end_nanos, planned_volume, version, created_nanos";

    private static final RowMapper<AllocationRow> ALLOCATION_MAPPER = (rs, n) -> new AllocationRow(
            rs.getLong("id"), rs.getString("allocation_key"), rs.getLong("window_id"),
            rs.getString("user_id"), rs.getString("priority"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("held_amount"),
            rs.getBigDecimal("baseline_held_amount"),
            rs.getString("requester"), rs.getString("status"),
            rs.getLong("created_nanos"), rs.getLong("updated_nanos"));

    private static final String ALLOCATION_SELECT =
            "SELECT id, allocation_key, window_id, user_id, priority, amount, held_amount,"
                    + " baseline_held_amount, requester, status, created_nanos, updated_nanos";

    private static final RowMapper<TransferRow> TRANSFER_MAPPER = (rs, n) -> new TransferRow(
            rs.getLong("id"), rs.getString("transfer_key"), rs.getLong("window_id"),
            rs.getString("source_allocation_key"), rs.getString("target_allocation_key"),
            rs.getBigDecimal("amount"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<CurtailmentRow> CURTAILMENT_MAPPER = (rs, n) -> new CurtailmentRow(
            rs.getLong("id"), rs.getLong("window_id"), rs.getBigDecimal("volume"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("cancelled_nanos") == null ? null : rs.getLong("cancelled_nanos"));

    private static final RowMapper<DroughtRow> DROUGHT_MAPPER = (rs, n) -> new DroughtRow(
            rs.getLong("id"), rs.getString("curtailment_key"), rs.getLong("window_id"),
            rs.getString("level"), rs.getInt("essential_pct"), rs.getInt("normal_pct"),
            rs.getInt("deferrable_pct"), rs.getLong("expected_version"), rs.getString("status"),
            rs.getLong("created_nanos"),
            rs.getObject("superseded_nanos") == null ? null : rs.getLong("superseded_nanos"));

    private static final String DROUGHT_COLUMNS =
            "id, curtailment_key, window_id, level, essential_pct, normal_pct, deferrable_pct,"
                    + " expected_version, status, created_nanos, superseded_nanos";

    private static final RowMapper<DroughtDetailRow> DROUGHT_DETAIL_MAPPER = (rs, n) -> new DroughtDetailRow(
            rs.getLong("id"), rs.getLong("curtailment_id"), rs.getLong("allocation_id"),
            rs.getString("allocation_key"), rs.getString("priority"),
            rs.getBigDecimal("original_held"), rs.getBigDecimal("target_held"),
            rs.getBigDecimal("reduced_amount"), rs.getLong("created_nanos"));

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
                    "INSERT INTO supply_window (window_key, channel_id, start_nanos, end_nanos, planned_volume,"
                            + " version, created_nanos) VALUES (?, ?, ?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
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
                    "SELECT " + WINDOW_COLUMNS + " FROM supply_window WHERE id = ?", WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键锁定窗口行（FOR UPDATE），用于串行化批准、转让、限供与旱情声明。 */
    public WindowRow lockWindowById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + WINDOW_COLUMNS + " FROM supply_window WHERE id = ? FOR UPDATE",
                    WINDOW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 旱情声明生效后推进窗口版本号。 */
    public void bumpWindowVersion(long id) {
        jdbc.update("UPDATE supply_window SET version = version + 1 WHERE id = ?", id);
    }

    /** 判断同渠道是否存在与 [startNanos, endNanos) 重叠的窗口（相邻合法）。 */
    public boolean existsOverlappingWindow(String channelId, long startNanos, long endNanos) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supply_window WHERE channel_id = ? AND start_nanos < ? AND ? < end_nanos",
                Integer.class, channelId, endNanos, startNanos);
        return count != null && count > 0;
    }

    /** 插入申请（初始 REQUESTED，持有额度与旱情基准均为 0）并返回主键。 */
    public long insertAllocation(String allocationKey, long windowId, String userId, String priority,
                                 BigDecimal amount, String requester, long nowNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO allocation (allocation_key, window_id, user_id, priority, amount, held_amount,"
                            + " baseline_held_amount, requester, status, created_nanos, updated_nanos)"
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
     * 更新申请状态与变更时间，并同步持有额度与旱情基准：批准时二者等于原申请水量，取消时持有额度归零
     * （基准保留作废），REQUESTED 保持不变。
     */
    public void updateAllocationStatus(long id, String status, long updatedNanos) {
        jdbc.update("UPDATE allocation SET status = ?, updated_nanos = ?,"
                + " held_amount = CASE WHEN ? = 'APPROVED' THEN amount WHEN ? = 'CANCELLED' THEN 0"
                + " ELSE held_amount END,"
                + " baseline_held_amount = CASE WHEN ? = 'APPROVED' THEN amount"
                + " ELSE baseline_held_amount END WHERE id = ?",
                status, updatedNanos, status, status, status, id);
    }

    /**
     * 转让扣减源持有额度与旱情基准（不得为负由事务内校验保证）：水权随转让永久转移给目标，
     * 因此旱情重算/回补所依据的原始持有额度也同步扣减，保证基准总量不发生虚增。
     */
    public void decrementHeldAndBaseline(long id, BigDecimal delta, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = held_amount - ?,"
                + " baseline_held_amount = baseline_held_amount - ?, updated_nanos = ? WHERE id = ?",
                delta, delta, updatedNanos, id);
    }

    /** 旱情重算：直接设定目标持有额度，基准持有额度保持不变。 */
    public void updateHeldAmount(long id, BigDecimal heldAmount, long updatedNanos) {
        jdbc.update("UPDATE allocation SET held_amount = ?, updated_nanos = ? WHERE id = ?",
                heldAmount, updatedNanos, id);
    }

    /**
     * 锁定窗口内全部 APPROVED 申请行（FOR UPDATE），按申请业务键升序返回；
     * 旱情同级取整差额即按该顺序由首笔承担。
     */
    public List<AllocationRow> lockApprovedAllocations(long windowId) {
        return jdbc.query(
                ALLOCATION_SELECT + " FROM allocation WHERE window_id = ? AND status = 'APPROVED'"
                        + " ORDER BY allocation_key ASC FOR UPDATE",
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

    /** 查询窗口当前生效总量限供，无则 null。 */
    public CurtailmentRow findActiveCurtailment(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, window_id, volume, status, created_nanos, cancelled_nanos"
                            + " FROM curtailment WHERE window_id = ? AND status = 'ACTIVE'",
                    CURTAILMENT_MAPPER, windowId);
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

    /** 窗口全部总量限供记录（含已取消），按主键升序。 */
    public List<CurtailmentRow> listCurtailments(long windowId) {
        return jdbc.query(
                "SELECT id, window_id, volume, status, created_nanos, cancelled_nanos"
                        + " FROM curtailment WHERE window_id = ? ORDER BY id", CURTAILMENT_MAPPER, windowId);
    }

    /** 插入旱情削减声明（ACTIVE）并返回主键。 */
    public long insertDrought(String curtailmentKey, long windowId, String level, int essentialPct,
                              int normalPct, int deferrablePct, long expectedVersion, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO drought_curtailment (curtailment_key, window_id, level, essential_pct,"
                            + " normal_pct, deferrable_pct, expected_version, status, created_nanos,"
                            + " superseded_nanos) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, curtailmentKey);
            ps.setLong(2, windowId);
            ps.setString(3, level);
            ps.setInt(4, essentialPct);
            ps.setInt(5, normalPct);
            ps.setInt(6, deferrablePct);
            ps.setLong(7, expectedVersion);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 把窗口当前 ACTIVE 旱情声明置为 SUPERSEDED。 */
    public void supersedeDrought(long id, long supersededNanos) {
        jdbc.update("UPDATE drought_curtailment SET status = 'SUPERSEDED', superseded_nanos = ? WHERE id = ?",
                supersededNanos, id);
    }

    /** 按业务键查询旱情声明，不存在返回 null。 */
    public DroughtRow findDroughtByKey(String curtailmentKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + DROUGHT_COLUMNS + " FROM drought_curtailment WHERE curtailment_key = ?",
                    DROUGHT_MAPPER, curtailmentKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询旱情声明，不存在返回 null。 */
    public DroughtRow findDroughtById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + DROUGHT_COLUMNS + " FROM drought_curtailment WHERE id = ?",
                    DROUGHT_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 查询窗口当前生效旱情声明（普通读），无则 null。 */
    public DroughtRow findActiveDrought(long windowId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + DROUGHT_COLUMNS + " FROM drought_curtailment"
                            + " WHERE window_id = ? AND status = 'ACTIVE'", DROUGHT_MAPPER, windowId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部旱情声明（含已覆盖），按生效顺序（主键升序）。 */
    public List<DroughtRow> listDroughts(long windowId) {
        return jdbc.query(
                "SELECT " + DROUGHT_COLUMNS + " FROM drought_curtailment WHERE window_id = ? ORDER BY id",
                DROUGHT_MAPPER, windowId);
    }

    /** 插入一条旱情削减明细。 */
    public void insertDroughtDetail(long curtailmentId, long allocationId, String allocationKey,
                                    String priority, BigDecimal originalHeld, BigDecimal targetHeld,
                                    BigDecimal reducedAmount, long createdNanos) {
        jdbc.update("INSERT INTO drought_curtailment_detail (curtailment_id, allocation_id, allocation_key,"
                + " priority, original_held, target_held, reduced_amount, created_nanos)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                curtailmentId, allocationId, allocationKey, priority, originalHeld, targetHeld,
                reducedAmount, createdNanos);
    }

    /** 某次声明的全部削减明细，按申请标识升序（同级差额承担顺序可追溯）。 */
    public List<DroughtDetailRow> listDroughtDetails(long curtailmentId) {
        return jdbc.query(
                "SELECT id, curtailment_id, allocation_id, allocation_key, priority, original_held,"
                        + " target_held, reduced_amount, created_nanos FROM drought_curtailment_detail"
                        + " WHERE curtailment_id = ? ORDER BY id",
                DROUGHT_DETAIL_MAPPER, curtailmentId);
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
}
