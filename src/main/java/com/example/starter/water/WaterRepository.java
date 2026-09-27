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

    /** 渠道计量规则版本行，创建后不可变。 */
    public record ChannelConfigRow(long id, String channelId, int version, BigDecimal maxFlowRate,
                                   BigDecimal maxIntervalMinutes, long effectiveFromNanos,
                                   String actor, long createdNanos) {
    }

    /** 渠道累计读数行，提交后不可变（异常读数同样保留）。 */
    public record ReadingRow(long id, String channelId, long readingAtNanos, BigDecimal cumulativeReading,
                             int ruleVersion, String reporter, long createdNanos) {
    }

    /** 异常行；status 只允许 OPEN -> RESOLVED 一次状态翻转，证据字段不再改写。 */
    public record AnomalyRow(long id, String channelId, long readingId, Long prevReadingId, String type,
                             int ruleVersion, Long windowStartNanos, long windowEndNanos,
                             Long prevReadingAtNanos, BigDecimal prevValue,
                             long readingAtNanos, BigDecimal readingValue,
                             BigDecimal actualValue, BigDecimal requiredValue, BigDecimal differenceValue,
                             String metric, String reporter, String status, Long resolutionId,
                             long createdNanos) {
    }

    /** 异常解除行，创建后不可变。 */
    public record ResolutionRow(long id, long anomalyId, String resolver, String diagnosisNote,
                                long replacementAtNanos, BigDecimal replacementValue, long createdNanos) {
    }

    /** 实际核销流水行，创建后不可变；heldAfter 为核销后持有额度快照。 */
    public record WriteoffRow(long id, String writeoffKey, long windowId, String channelId,
                              String allocationKey, BigDecimal amount, BigDecimal heldAfter, String actor,
                              long atNanos, long createdNanos) {
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

    private static final RowMapper<ChannelConfigRow> CHANNEL_CONFIG_MAPPER = (rs, n) -> new ChannelConfigRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getInt("version"),
            rs.getBigDecimal("max_flow_rate"), rs.getBigDecimal("max_interval_minutes"),
            rs.getLong("effective_from_nanos"), rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getLong("reading_at_nanos"),
            rs.getBigDecimal("cumulative_reading"), rs.getInt("rule_version"),
            rs.getString("reporter"), rs.getLong("created_nanos"));

    private static final String ANOMALY_COLUMNS =
            "id, channel_id, reading_id, prev_reading_id, type, rule_version, window_start_nanos,"
                    + " window_end_nanos, prev_reading_at_nanos, prev_value, reading_at_nanos, reading_value,"
                    + " actual_value, required_value, difference_value, metric, reporter, status,"
                    + " resolution_id, created_nanos";

    private static final RowMapper<AnomalyRow> ANOMALY_MAPPER = (rs, n) -> new AnomalyRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getLong("reading_id"),
            rs.getObject("prev_reading_id") == null ? null : rs.getLong("prev_reading_id"),
            rs.getString("type"), rs.getInt("rule_version"),
            rs.getObject("window_start_nanos") == null ? null : rs.getLong("window_start_nanos"),
            rs.getLong("window_end_nanos"),
            rs.getObject("prev_reading_at_nanos") == null ? null : rs.getLong("prev_reading_at_nanos"),
            rs.getBigDecimal("prev_value"), rs.getLong("reading_at_nanos"),
            rs.getBigDecimal("reading_value"), rs.getBigDecimal("actual_value"),
            rs.getBigDecimal("required_value"), rs.getBigDecimal("difference_value"),
            rs.getString("metric"), rs.getString("reporter"), rs.getString("status"),
            rs.getObject("resolution_id") == null ? null : rs.getLong("resolution_id"),
            rs.getLong("created_nanos"));

    private static final RowMapper<ResolutionRow> RESOLUTION_MAPPER = (rs, n) -> new ResolutionRow(
            rs.getLong("id"), rs.getLong("anomaly_id"), rs.getString("resolver"),
            rs.getString("diagnosis_note"), rs.getLong("replacement_at_nanos"),
            rs.getBigDecimal("replacement_value"), rs.getLong("created_nanos"));

    private static final RowMapper<WriteoffRow> WRITEOFF_MAPPER = (rs, n) -> new WriteoffRow(
            rs.getLong("id"), rs.getString("writeoff_key"), rs.getLong("window_id"),
            rs.getString("channel_id"), rs.getString("allocation_key"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("held_after"), rs.getString("actor"),
            rs.getLong("at_nanos"), rs.getLong("created_nanos"));

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
    // 渠道主数据与计量规则版本
    // ------------------------------------------------------------------

    /** 首次配置规则时登记渠道（幂等插入，已存在忽略）。 */
    public void insertChannelIfAbsent(String channelId, long createdNanos, String createdBy) {
        jdbc.update("MERGE INTO channel (channel_id, created_nanos, created_by) KEY (channel_id)"
                + " VALUES (?, ?, ?)", channelId, createdNanos, createdBy);
    }

    /** 锁定渠道主数据行（FOR UPDATE），串行化该渠道的配置、读数、解除与核销，不存在返回 false。 */
    public boolean lockChannel(String channelId) {
        List<String> locked = jdbc.queryForList(
                "SELECT channel_id FROM channel WHERE channel_id = ? FOR UPDATE", String.class, channelId);
        return !locked.isEmpty();
    }

    /** 插入规则新版本并返回主键；版本号由服务层在渠道锁内分配。 */
    public long insertChannelConfig(String channelId, int version, BigDecimal maxFlowRate,
                                    BigDecimal maxIntervalMinutes, long effectiveFromNanos,
                                    String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO channel_config (channel_id, version, max_flow_rate, max_interval_minutes,"
                            + " effective_from_nanos, actor, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setInt(2, version);
            ps.setBigDecimal(3, maxFlowRate);
            ps.setBigDecimal(4, maxIntervalMinutes);
            ps.setLong(5, effectiveFromNanos);
            ps.setString(6, actor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 渠道最新规则版本，不存在返回 null。 */
    public ChannelConfigRow findLatestChannelConfig(String channelId) {
        List<ChannelConfigRow> rows = jdbc.query(
                "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, effective_from_nanos,"
                        + " actor, created_nanos FROM channel_config WHERE channel_id = ?"
                        + " ORDER BY version DESC LIMIT 1", CHANNEL_CONFIG_MAPPER, channelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁定渠道最新规则版本行（FOR UPDATE），用于串行化该渠道的读数判定、配置与核销裁决。 */
    public ChannelConfigRow lockLatestChannelConfig(String channelId) {
        List<ChannelConfigRow> rows = jdbc.query(
                "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, effective_from_nanos,"
                        + " actor, created_nanos FROM channel_config WHERE channel_id = ?"
                        + " ORDER BY version DESC LIMIT 1 FOR UPDATE", CHANNEL_CONFIG_MAPPER, channelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 渠道全部规则版本，按版本号升序（不可变历史）。 */
    public List<ChannelConfigRow> listChannelConfigs(String channelId) {
        return jdbc.query(
                "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, effective_from_nanos,"
                        + " actor, created_nanos FROM channel_config WHERE channel_id = ? ORDER BY version",
                CHANNEL_CONFIG_MAPPER, channelId);
    }

    /** 渠道是否存在任何规则版本。 */
    public boolean existsChannelConfig(String channelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_config WHERE channel_id = ?", Integer.class, channelId);
        return count != null && count > 0;
    }

    /** 查询读数时刻适用的规则版本：effective_from_nanos 不晚于该时刻的最新版本，无则 null。 */
    public ChannelConfigRow findEffectiveChannelConfigAt(String channelId, long readingAtNanos) {
        List<ChannelConfigRow> rows = jdbc.query(
                "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, effective_from_nanos,"
                        + " actor, created_nanos FROM channel_config WHERE channel_id = ?"
                        + " AND effective_from_nanos <= ? ORDER BY version DESC LIMIT 1",
                CHANNEL_CONFIG_MAPPER, channelId, readingAtNanos);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------
    // 渠道累计读数
    // ------------------------------------------------------------------

    /** 插入读数并返回主键；同一渠道 reading_at_nanos 唯一，重复由唯一约束拒绝。 */
    public long insertReading(String channelId, long readingAtNanos, BigDecimal cumulativeReading,
                              int ruleVersion, String reporter, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sensor_reading (channel_id, reading_at_nanos, cumulative_reading,"
                            + " rule_version, reporter, created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, readingAtNanos);
            ps.setBigDecimal(3, cumulativeReading);
            ps.setInt(4, ruleVersion);
            ps.setString(5, reporter);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 渠道最新一条读数（按采样时刻，其次主键），不存在返回 null。 */
    public ReadingRow findLatestReading(String channelId) {
        List<ReadingRow> rows = jdbc.query(
                "SELECT id, channel_id, reading_at_nanos, cumulative_reading, rule_version, reporter,"
                        + " created_nanos FROM sensor_reading WHERE channel_id = ?"
                        + " ORDER BY reading_at_nanos DESC, id DESC LIMIT 1", READING_MAPPER, channelId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 渠道全部读数，按采样时刻升序（同时刻按主键），历史不可变。 */
    public List<ReadingRow> listReadings(String channelId) {
        return jdbc.query(
                "SELECT id, channel_id, reading_at_nanos, cumulative_reading, rule_version, reporter,"
                        + " created_nanos FROM sensor_reading WHERE channel_id = ?"
                        + " ORDER BY reading_at_nanos, id", READING_MAPPER, channelId);
    }

    /** 按主键查询读数，不存在返回 null。 */
    public ReadingRow findReadingById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, channel_id, reading_at_nanos, cumulative_reading, rule_version, reporter,"
                            + " created_nanos FROM sensor_reading WHERE id = ?", READING_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 传感异常与解除
    // ------------------------------------------------------------------

    /** 插入异常（OPEN）并返回主键。 */
    public long insertAnomaly(String channelId, long readingId, Long prevReadingId, String type,
                              int ruleVersion, Long windowStartNanos, long windowEndNanos,
                              Long prevReadingAtNanos, BigDecimal prevValue,
                              long readingAtNanos, BigDecimal readingValue,
                              BigDecimal actualValue, BigDecimal requiredValue, BigDecimal differenceValue,
                              String metric, String reporter, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO anomaly (channel_id, reading_id, prev_reading_id, type, rule_version,"
                            + " window_start_nanos, window_end_nanos, prev_reading_at_nanos, prev_value,"
                            + " reading_at_nanos, reading_value, actual_value, required_value,"
                            + " difference_value, metric, reporter, status, resolution_id, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, readingId);
            if (prevReadingId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, prevReadingId);
            }
            ps.setString(4, type);
            ps.setInt(5, ruleVersion);
            if (windowStartNanos == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, windowStartNanos);
            }
            ps.setLong(7, windowEndNanos);
            if (prevReadingAtNanos == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, prevReadingAtNanos);
            }
            ps.setBigDecimal(9, prevValue);
            ps.setLong(10, readingAtNanos);
            ps.setBigDecimal(11, readingValue);
            ps.setBigDecimal(12, actualValue);
            ps.setBigDecimal(13, requiredValue);
            ps.setBigDecimal(14, differenceValue);
            ps.setString(15, metric);
            ps.setString(16, reporter);
            ps.setLong(17, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询异常，不存在返回 null。 */
    public AnomalyRow findAnomalyById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + ANOMALY_COLUMNS + " FROM anomaly WHERE id = ?", ANOMALY_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定异常行（FOR UPDATE），用于串行化解除操作，不存在返回 null。 */
    public AnomalyRow lockAnomalyById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + ANOMALY_COLUMNS + " FROM anomaly WHERE id = ? FOR UPDATE",
                    ANOMALY_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部异常，按主键升序（历史不可变）。 */
    public List<AnomalyRow> listAnomalies(String channelId) {
        return jdbc.query("SELECT " + ANOMALY_COLUMNS + " FROM anomaly WHERE channel_id = ? ORDER BY id",
                ANOMALY_MAPPER, channelId);
    }

    /** 渠道当前未解除异常数量；大于 0 即冻结该渠道新核销。 */
    public int countOpenAnomalies(String channelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM anomaly WHERE channel_id = ? AND status = 'OPEN'",
                Integer.class, channelId);
        return count == null ? 0 : count;
    }

    /** 渠道当前未解除异常，按主键升序。 */
    public List<AnomalyRow> listOpenAnomalies(String channelId) {
        return jdbc.query(
                "SELECT " + ANOMALY_COLUMNS + " FROM anomaly WHERE channel_id = ? AND status = 'OPEN'"
                        + " ORDER BY id", ANOMALY_MAPPER, channelId);
    }

    /** 解除异常：仅当仍为 OPEN 时置为 RESOLVED 并关联解除记录；返回是否成功翻转。 */
    public boolean resolveAnomaly(long anomalyId, long resolutionId) {
        int updated = jdbc.update(
                "UPDATE anomaly SET status = 'RESOLVED', resolution_id = ?"
                        + " WHERE id = ? AND status = 'OPEN'", resolutionId, anomalyId);
        return updated == 1;
    }

    /** 插入解除记录并返回主键；同一异常至多一条，由唯一约束兜底。 */
    public long insertResolution(long anomalyId, String resolver, String diagnosisNote,
                                 long replacementAtNanos, BigDecimal replacementValue, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO anomaly_resolution (anomaly_id, resolver, diagnosis_note,"
                            + " replacement_at_nanos, replacement_value, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, anomalyId);
            ps.setString(2, resolver);
            ps.setString(3, diagnosisNote);
            ps.setLong(4, replacementAtNanos);
            ps.setBigDecimal(5, replacementValue);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按异常 ID 查询解除记录，未解除返回 null。 */
    public ResolutionRow findResolutionByAnomalyId(long anomalyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, anomaly_id, resolver, diagnosis_note, replacement_at_nanos,"
                            + " replacement_value, created_nanos FROM anomaly_resolution WHERE anomaly_id = ?",
                    RESOLUTION_MAPPER, anomalyId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 实际核销
    // ------------------------------------------------------------------

    /** 插入核销流水并返回主键；writeoff_key 全局唯一。 */
    public long insertWriteoff(String writeoffKey, long windowId, String channelId, String allocationKey,
                               BigDecimal amount, BigDecimal heldAfter, String actor, long atNanos,
                               long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO writeoff (writeoff_key, window_id, channel_id, allocation_key, amount,"
                            + " held_after, actor, at_nanos, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeoffKey);
            ps.setLong(2, windowId);
            ps.setString(3, channelId);
            ps.setString(4, allocationKey);
            ps.setBigDecimal(5, amount);
            ps.setBigDecimal(6, heldAfter);
            ps.setString(7, actor);
            ps.setLong(8, atNanos);
            ps.setLong(9, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，不存在返回 null。 */
    public WriteoffRow findWriteoffByKey(String writeoffKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, writeoff_key, window_id, channel_id, allocation_key, amount, held_after,"
                            + " actor, at_nanos, created_nanos FROM writeoff WHERE writeoff_key = ?",
                    WRITEOFF_MAPPER, writeoffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 窗口全部核销流水，按主键升序（历史不可变）。 */
    public List<WriteoffRow> listWriteoffs(long windowId) {
        return jdbc.query(
                "SELECT id, writeoff_key, window_id, channel_id, allocation_key, amount, held_after, actor,"
                        + " at_nanos, created_nanos FROM writeoff WHERE window_id = ? ORDER BY id",
                WRITEOFF_MAPPER, windowId);
    }
}
