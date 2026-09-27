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

    /** 渠道异常判定规则版本行，创建后不可变。 */
    public record ChannelRuleRow(long id, String channelId, int version, BigDecimal maxFlowRate,
                                 int maxIntervalMinutes, String actor, long createdNanos) {
    }

    /** 计量读数行，创建后不可变。 */
    public record ReadingRow(long id, String channelId, long observedNanos, BigDecimal cumulativeValue,
                             String actor, long createdNanos) {
    }

    /** 计量异常行（含判定规则版本号），创建后不可变。 */
    public record AnomalyRow(long id, String channelId, long ruleId, int ruleVersion, long readingId,
                             String type, BigDecimal actualValue, BigDecimal requiredValue,
                             BigDecimal deltaValue, long prevObservedNanos, BigDecimal prevValue,
                             long readingObservedNanos, BigDecimal readingValue, String readingActor,
                             long createdNanos) {
    }

    /** 异常解除记录行，创建后不可变。 */
    public record ReleaseRow(long id, long anomalyId, String channelId, String diagnosis,
                             BigDecimal substituteValue, long substituteObservedNanos, String actor,
                             long createdNanos) {
    }

    /** 实际核销流水行，创建后不可变。 */
    public record SettlementRow(long id, String settlementKey, String allocationKey, long windowId,
                                String channelId, BigDecimal amount, String actor, long createdNanos) {
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

    private static final RowMapper<ChannelRuleRow> RULE_MAPPER = (rs, n) -> new ChannelRuleRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getInt("version"),
            rs.getBigDecimal("max_flow_rate"), rs.getInt("max_interval_minutes"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getLong("observed_nanos"),
            rs.getBigDecimal("cumulative_value"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final String ANOMALY_SELECT =
            "SELECT a.id, a.channel_id, a.rule_id, r.version AS rule_version, a.reading_id, a.type,"
                    + " a.actual_value, a.required_value, a.delta_value, a.prev_observed_nanos, a.prev_value,"
                    + " a.reading_observed_nanos, a.reading_value, a.reading_actor, a.created_nanos"
                    + " FROM anomaly a JOIN channel_rule r ON r.id = a.rule_id";

    private static final RowMapper<AnomalyRow> ANOMALY_MAPPER = (rs, n) -> new AnomalyRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getLong("rule_id"), rs.getInt("rule_version"),
            rs.getLong("reading_id"), rs.getString("type"), rs.getBigDecimal("actual_value"),
            rs.getBigDecimal("required_value"), rs.getBigDecimal("delta_value"),
            rs.getLong("prev_observed_nanos"), rs.getBigDecimal("prev_value"),
            rs.getLong("reading_observed_nanos"), rs.getBigDecimal("reading_value"),
            rs.getString("reading_actor"), rs.getLong("created_nanos"));

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, n) -> new ReleaseRow(
            rs.getLong("id"), rs.getLong("anomaly_id"), rs.getString("channel_id"),
            rs.getString("diagnosis"), rs.getBigDecimal("substitute_value"),
            rs.getLong("substitute_observed_nanos"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) -> new SettlementRow(
            rs.getLong("id"), rs.getString("settlement_key"), rs.getString("allocation_key"),
            rs.getLong("window_id"), rs.getString("channel_id"), rs.getBigDecimal("amount"),
            rs.getString("actor"), rs.getLong("created_nanos"));

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
    // 渠道注册 / 异常规则 / 读数 / 异常 / 冻结解除 / 核销
    // ------------------------------------------------------------------

    /** 渠道是否已登记。 */
    public boolean channelExists(String channelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_registry WHERE channel_id = ?", Integer.class, channelId);
        return count != null && count > 0;
    }

    /** 登记渠道（不存在时插入，已存在忽略），用于获得渠道级串行锁行。 */
    public void ensureChannel(String channelId, long createdNanos) {
        if (channelExists(channelId)) {
            return;
        }
        try {
            jdbc.update("INSERT INTO channel_registry (channel_id, created_nanos) VALUES (?, ?)",
                    channelId, createdNanos);
        } catch (DuplicateKeyException e) {
            // 并发登记同一渠道：行已存在，继续即可
        }
    }

    /** 锁定渠道注册行（FOR UPDATE），串行化读数、核销、解除与规则配置，不存在返回 false。 */
    public boolean lockChannel(String channelId) {
        return !jdbc.query(
                "SELECT channel_id FROM channel_registry WHERE channel_id = ? FOR UPDATE",
                (rs, n) -> rs.getString("channel_id"), channelId).isEmpty();
    }

    /** 插入规则新版本并返回主键。 */
    public long insertChannelRule(String channelId, int version, BigDecimal maxFlowRate,
                                  int maxIntervalMinutes, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO channel_rule (channel_id, version, max_flow_rate, max_interval_minutes,"
                            + " actor, created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setInt(2, version);
            ps.setBigDecimal(3, maxFlowRate);
            ps.setInt(4, maxIntervalMinutes);
            ps.setString(5, actor);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 查询渠道当前最新规则版本，未配置返回 null。 */
    public ChannelRuleRow findCurrentRule(String channelId) {
        List<ChannelRuleRow> rows = jdbc.query(
                "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, actor, created_nanos"
                        + " FROM channel_rule WHERE channel_id = ? ORDER BY version DESC LIMIT 1",
                RULE_MAPPER, channelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 渠道全部规则版本，按版本号升序。 */
    public List<ChannelRuleRow> listChannelRules(String channelId) {
        return jdbc.query(
                "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, actor, created_nanos"
                        + " FROM channel_rule WHERE channel_id = ? ORDER BY version",
                RULE_MAPPER, channelId);
    }

    /** 插入读数并返回主键。 */
    public long insertReading(String channelId, long observedNanos, BigDecimal cumulativeValue,
                              String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_reading (channel_id, observed_nanos, cumulative_value, actor,"
                            + " created_nanos) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, observedNanos);
            ps.setBigDecimal(3, cumulativeValue);
            ps.setString(4, actor);
            ps.setLong(5, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 渠道最新一条读数（按读数时刻、主键倒序），无则 null。 */
    public ReadingRow findLatestReading(String channelId) {
        List<ReadingRow> rows = jdbc.query(
                "SELECT id, channel_id, observed_nanos, cumulative_value, actor, created_nanos"
                        + " FROM meter_reading WHERE channel_id = ?"
                        + " ORDER BY observed_nanos DESC, id DESC LIMIT 1",
                READING_MAPPER, channelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 渠道全部读数，按读数时刻与主键稳定升序。 */
    public List<ReadingRow> listReadings(String channelId) {
        return jdbc.query(
                "SELECT id, channel_id, observed_nanos, cumulative_value, actor, created_nanos"
                        + " FROM meter_reading WHERE channel_id = ? ORDER BY observed_nanos, id",
                READING_MAPPER, channelId);
    }

    /** 插入异常并返回主键。 */
    public long insertAnomaly(String channelId, long ruleId, long readingId, String type,
                              BigDecimal actualValue, BigDecimal requiredValue, BigDecimal deltaValue,
                              long prevObservedNanos, BigDecimal prevValue, long readingObservedNanos,
                              BigDecimal readingValue, String readingActor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO anomaly (channel_id, rule_id, reading_id, type, actual_value,"
                            + " required_value, delta_value, prev_observed_nanos, prev_value,"
                            + " reading_observed_nanos, reading_value, reading_actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, ruleId);
            ps.setLong(3, readingId);
            ps.setString(4, type);
            ps.setBigDecimal(5, actualValue);
            ps.setBigDecimal(6, requiredValue);
            ps.setBigDecimal(7, deltaValue);
            ps.setLong(8, prevObservedNanos);
            ps.setBigDecimal(9, prevValue);
            ps.setLong(10, readingObservedNanos);
            ps.setBigDecimal(11, readingValue);
            ps.setString(12, readingActor);
            ps.setLong(13, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询异常（含规则版本号），不存在返回 null。 */
    public AnomalyRow findAnomalyById(long id) {
        try {
            return jdbc.queryForObject(ANOMALY_SELECT + " WHERE a.id = ?", ANOMALY_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部异常，按主键稳定升序。 */
    public List<AnomalyRow> listAnomalies(String channelId) {
        return jdbc.query(ANOMALY_SELECT + " WHERE a.channel_id = ? ORDER BY a.id",
                ANOMALY_MAPPER, channelId);
    }

    /** 插入冻结记录（一异常一冻结）并返回主键。 */
    public long insertFreeze(long anomalyId, String channelId, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO channel_freeze (anomaly_id, channel_id, created_nanos) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, anomalyId);
            ps.setString(2, channelId);
            ps.setLong(3, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 渠道当前未解除冻结数量；大于 0 表示渠道冻结，禁止新核销。 */
    public int countOpenFreezes(String channelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_freeze f WHERE f.channel_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM anomaly_release r WHERE r.anomaly_id = f.anomaly_id)",
                Integer.class, channelId);
        return count == null ? 0 : count;
    }

    /** 插入解除记录（一异常至多一次，唯一约束兜底）并返回主键。 */
    public long insertRelease(long anomalyId, String channelId, String diagnosis,
                              BigDecimal substituteValue, long substituteObservedNanos, String actor,
                              long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO anomaly_release (anomaly_id, channel_id, diagnosis, substitute_value,"
                            + " substitute_observed_nanos, actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, anomalyId);
            ps.setString(2, channelId);
            ps.setString(3, diagnosis);
            ps.setBigDecimal(4, substituteValue);
            ps.setLong(5, substituteObservedNanos);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按异常 ID 查询解除记录，未解除返回 null。 */
    public ReleaseRow findReleaseByAnomalyId(long anomalyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, anomaly_id, channel_id, diagnosis, substitute_value,"
                            + " substitute_observed_nanos, actor, created_nanos"
                            + " FROM anomaly_release WHERE anomaly_id = ?",
                    RELEASE_MAPPER, anomalyId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部解除记录，按主键升序。 */
    public List<ReleaseRow> listReleases(String channelId) {
        return jdbc.query(
                "SELECT id, anomaly_id, channel_id, diagnosis, substitute_value,"
                        + " substitute_observed_nanos, actor, created_nanos"
                        + " FROM anomaly_release WHERE channel_id = ? ORDER BY id",
                RELEASE_MAPPER, channelId);
    }

    /** 渠道最新一条解除替代值（按替代时刻、主键倒序），无则 null。 */
    public ReleaseRow findLatestRelease(String channelId) {
        List<ReleaseRow> rows = jdbc.query(
                "SELECT id, anomaly_id, channel_id, diagnosis, substitute_value,"
                        + " substitute_observed_nanos, actor, created_nanos"
                        + " FROM anomaly_release WHERE channel_id = ?"
                        + " ORDER BY substitute_observed_nanos DESC, id DESC LIMIT 1",
                RELEASE_MAPPER, channelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入核销流水并返回主键。 */
    public long insertSettlement(String settlementKey, String allocationKey, long windowId,
                                 String channelId, BigDecimal amount, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement (settlement_key, allocation_key, window_id, channel_id,"
                            + " amount, actor, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, settlementKey);
            ps.setString(2, allocationKey);
            ps.setLong(3, windowId);
            ps.setString(4, channelId);
            ps.setBigDecimal(5, amount);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public SettlementRow findSettlementByKey(String settlementKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, settlement_key, allocation_key, window_id, channel_id, amount, actor,"
                            + " created_nanos FROM settlement WHERE settlement_key = ?",
                    SETTLEMENT_MAPPER, settlementKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请全部核销流水，按主键稳定升序。 */
    public List<SettlementRow> listSettlements(String allocationKey) {
        return jdbc.query(
                "SELECT id, settlement_key, allocation_key, window_id, channel_id, amount, actor,"
                        + " created_nanos FROM settlement WHERE allocation_key = ? ORDER BY id",
                SETTLEMENT_MAPPER, allocationKey);
    }
}
