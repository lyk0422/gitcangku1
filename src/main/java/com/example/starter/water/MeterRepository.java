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
 * 配水传感计量数据访问层（JdbcTemplate，参数化 SQL）。
 * 时间一律为 UTC 纳秒时间戳；累计水量 DECIMAL(19,3) 立方米，流量 DECIMAL(25,9) 立方米/分钟。
 * 读数、规则版本、异常、解除均为只追加证据，本类不提供任何更新/删除方法。
 */
@Repository
public class MeterRepository {

    /** 渠道计量点行。 */
    public record ChannelRow(String channelId, String createdBy, long createdNanos) {
    }

    /** 规则版本行，只追加。 */
    public record RuleVersionRow(long id, String channelId, int version, BigDecimal maxFlowRate,
                                 int maxIntervalMinutes, String createdBy, long createdNanos) {
    }

    /** 读数行，提交后不可变。 */
    public record ReadingRow(long id, String channelId, long readingUtcNanos, BigDecimal cumulativeVolume,
                             int batchSeq, String submitter, int ruleVersion, String commandKey,
                             long createdNanos) {
    }

    /** 异常原始证据行，只追加；字段含义见 schema.sql 中文注释。 */
    public record AnomalyRow(long id, String channelId, String anomalyType, int ruleVersion,
                             long previousReadingId, long readingId, long previousUtcNanos, long readingUtcNanos,
                             BigDecimal previousCumulative, BigDecimal readingCumulative, BigDecimal volumeDelta,
                             BigDecimal durationMinutes, BigDecimal actualFlowRate, BigDecimal requiredMaxFlowRate,
                             int requiredMaxIntervalMinutes, BigDecimal regressionAmount,
                             BigDecimal flowRateExcess, BigDecimal intervalExcessMinutes,
                             String submitter, String commandKey, long createdNanos) {
    }

    /** 异常解除证据行，只追加。 */
    public record ResolutionRow(long id, long anomalyId, String channelId, String resolver, String diagnosisNote,
                                long replacementUtcNanos, BigDecimal replacementCumulative, String commandKey,
                                long createdNanos) {
    }

    private static final RowMapper<ChannelRow> CHANNEL_MAPPER = (rs, n) -> new ChannelRow(
            rs.getString("channel_id"), rs.getString("created_by"), rs.getLong("created_nanos"));

    private static final RowMapper<RuleVersionRow> RULE_MAPPER = (rs, n) -> new RuleVersionRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getInt("version"),
            rs.getBigDecimal("max_flow_rate"), rs.getInt("max_interval_minutes"),
            rs.getString("created_by"), rs.getLong("created_nanos"));

    private static final String READING_COLUMNS =
            "SELECT id, channel_id, reading_utc_nanos, cumulative_volume, batch_seq, submitter, rule_version,"
                    + " command_key, created_nanos";

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getLong("reading_utc_nanos"),
            rs.getBigDecimal("cumulative_volume"), rs.getInt("batch_seq"), rs.getString("submitter"),
            rs.getInt("rule_version"), rs.getString("command_key"), rs.getLong("created_nanos"));

    private static final String ANOMALY_COLUMNS =
            "SELECT id, channel_id, anomaly_type, rule_version, previous_reading_id, reading_id,"
                    + " previous_utc_nanos, reading_utc_nanos, previous_cumulative, reading_cumulative,"
                    + " volume_delta, duration_minutes, actual_flow_rate, required_max_flow_rate,"
                    + " required_max_interval_minutes, regression_amount, flow_rate_excess,"
                    + " interval_excess_minutes, submitter, command_key, created_nanos";

    private static final RowMapper<AnomalyRow> ANOMALY_MAPPER = (rs, n) -> new AnomalyRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getString("anomaly_type"),
            rs.getInt("rule_version"), rs.getLong("previous_reading_id"), rs.getLong("reading_id"),
            rs.getLong("previous_utc_nanos"), rs.getLong("reading_utc_nanos"),
            rs.getBigDecimal("previous_cumulative"), rs.getBigDecimal("reading_cumulative"),
            rs.getBigDecimal("volume_delta"), rs.getBigDecimal("duration_minutes"),
            rs.getBigDecimal("actual_flow_rate"), rs.getBigDecimal("required_max_flow_rate"),
            rs.getInt("required_max_interval_minutes"),
            rs.getObject("regression_amount") == null ? null : rs.getBigDecimal("regression_amount"),
            rs.getObject("flow_rate_excess") == null ? null : rs.getBigDecimal("flow_rate_excess"),
            rs.getObject("interval_excess_minutes") == null ? null : rs.getBigDecimal("interval_excess_minutes"),
            rs.getString("submitter"), rs.getString("command_key"), rs.getLong("created_nanos"));

    private static final RowMapper<ResolutionRow> RESOLUTION_MAPPER = (rs, n) -> new ResolutionRow(
            rs.getLong("id"), rs.getLong("anomaly_id"), rs.getString("channel_id"),
            rs.getString("resolver"), rs.getString("diagnosis_note"),
            rs.getLong("replacement_utc_nanos"), rs.getBigDecimal("replacement_cumulative"),
            rs.getString("command_key"), rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public MeterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 注册渠道计量点（已存在则忽略），返回是否为本次新建。 */
    public boolean registerChannelIfAbsent(String channelId, String createdBy, long createdNanos) {
        int affected = jdbc.update(
                "INSERT INTO meter_channel (channel_id, created_by, created_nanos) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE channel_id = channel_id",
                channelId, createdBy, createdNanos);
        return affected > 0;
    }

    /** 查询渠道，不存在返回 null。 */
    public ChannelRow findChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT channel_id, created_by, created_nanos FROM meter_channel WHERE channel_id = ?",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定渠道行（FOR UPDATE）串行化读数、解除、核销与配置，不存在返回 null。 */
    public ChannelRow lockChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT channel_id, created_by, created_nanos FROM meter_channel WHERE channel_id = ? FOR UPDATE",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 追加规则版本并返回带主键的新版本行。 */
    public RuleVersionRow insertRuleVersion(String channelId, int version, BigDecimal maxFlowRate,
                                            int maxIntervalMinutes, String createdBy, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO channel_rule_version (channel_id, version, max_flow_rate,"
                            + " max_interval_minutes, created_by, created_nanos) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setInt(2, version);
            ps.setBigDecimal(3, maxFlowRate);
            ps.setInt(4, maxIntervalMinutes);
            ps.setString(5, createdBy);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return new RuleVersionRow(Objects.requireNonNull(keys.getKey()).longValue(), channelId, version,
                maxFlowRate, maxIntervalMinutes, createdBy, createdNanos);
    }

    /** 渠道最新规则版本，无则 null。 */
    public RuleVersionRow findLatestRuleVersion(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, created_by, created_nanos"
                            + " FROM channel_rule_version WHERE channel_id = ? ORDER BY version DESC LIMIT 1",
                    RULE_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按版本号查询规则版本，无则 null。 */
    public RuleVersionRow findRuleVersion(String channelId, int version) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, created_by, created_nanos"
                            + " FROM channel_rule_version WHERE channel_id = ? AND version = ?",
                    RULE_MAPPER, channelId, version);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部规则版本，按版本号升序。 */
    public List<RuleVersionRow> listRuleVersions(String channelId) {
        return jdbc.query(
                "SELECT id, channel_id, version, max_flow_rate, max_interval_minutes, created_by, created_nanos"
                        + " FROM channel_rule_version WHERE channel_id = ? ORDER BY version",
                RULE_MAPPER, channelId);
    }

    /** 插入不可变读数并返回带主键的行。 */
    public ReadingRow insertReading(String channelId, long readingUtcNanos, BigDecimal cumulativeVolume,
                                    int batchSeq, String submitter, int ruleVersion, String commandKey,
                                    long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_reading (channel_id, reading_utc_nanos, cumulative_volume, batch_seq,"
                            + " submitter, rule_version, command_key, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, readingUtcNanos);
            ps.setBigDecimal(3, cumulativeVolume);
            ps.setInt(4, batchSeq);
            ps.setString(5, submitter);
            ps.setInt(6, ruleVersion);
            ps.setString(7, commandKey);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        return new ReadingRow(id, channelId, readingUtcNanos, cumulativeVolume, batchSeq, submitter,
                ruleVersion, commandKey, createdNanos);
    }

    /**
     * 渠道最近一条读数（测量时刻与主键最大者），无则 null。
     * 调用方须已持有该渠道行锁（读数提交的串行化点），故此处不再对读数行加锁。
     */
    public ReadingRow findLatestReading(String channelId) {
        try {
            return jdbc.queryForObject(READING_COLUMNS + " FROM meter_reading WHERE channel_id = ?"
                    + " ORDER BY reading_utc_nanos DESC, id DESC LIMIT 1", READING_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查读数，无则 null。 */
    public ReadingRow findReadingById(long id) {
        try {
            return jdbc.queryForObject(READING_COLUMNS + " FROM meter_reading WHERE id = ?",
                    READING_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 渠道内时刻严格小于 beforeNanos 的最近读数，用于定位异常前序读数；无则 null。
     * 调用方（异常解除）须已持有渠道行锁。
     */
    public ReadingRow findLatestReadingBefore(String channelId, long beforeNanos) {
        try {
            return jdbc.queryForObject(READING_COLUMNS + " FROM meter_reading WHERE channel_id = ?"
                    + " AND reading_utc_nanos < ? ORDER BY reading_utc_nanos DESC, id DESC LIMIT 1",
                    READING_MAPPER, channelId, beforeNanos);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道内时刻严格大于 afterNanos 的最早读数，用于解除时定位后继读数；无则 null。 */
    public ReadingRow findFirstReadingAfter(String channelId, long afterNanos) {
        try {
            return jdbc.queryForObject(READING_COLUMNS + " FROM meter_reading WHERE channel_id = ?"
                    + " AND reading_utc_nanos > ? ORDER BY reading_utc_nanos ASC, id ASC LIMIT 1",
                    READING_MAPPER, channelId, afterNanos);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部读数，按测量时刻、主键升序（稳定排序）。 */
    public List<ReadingRow> listReadings(String channelId) {
        return jdbc.query(READING_COLUMNS + " FROM meter_reading WHERE channel_id = ?"
                + " ORDER BY reading_utc_nanos, id", READING_MAPPER, channelId);
    }

    /** 插入异常原始证据并返回带主键的行。 */
    public AnomalyRow insertAnomaly(AnomalyRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_anomaly (channel_id, anomaly_type, rule_version, previous_reading_id,"
                            + " reading_id, previous_utc_nanos, reading_utc_nanos, previous_cumulative,"
                            + " reading_cumulative, volume_delta, duration_minutes, actual_flow_rate,"
                            + " required_max_flow_rate, required_max_interval_minutes, regression_amount,"
                            + " flow_rate_excess, interval_excess_minutes, submitter, command_key, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.channelId());
            ps.setString(2, row.anomalyType());
            ps.setInt(3, row.ruleVersion());
            ps.setLong(4, row.previousReadingId());
            ps.setLong(5, row.readingId());
            ps.setLong(6, row.previousUtcNanos());
            ps.setLong(7, row.readingUtcNanos());
            ps.setBigDecimal(8, row.previousCumulative());
            ps.setBigDecimal(9, row.readingCumulative());
            ps.setBigDecimal(10, row.volumeDelta());
            ps.setBigDecimal(11, row.durationMinutes());
            ps.setBigDecimal(12, row.actualFlowRate());
            ps.setBigDecimal(13, row.requiredMaxFlowRate());
            ps.setInt(14, row.requiredMaxIntervalMinutes());
            ps.setBigDecimal(15, row.regressionAmount());
            ps.setBigDecimal(16, row.flowRateExcess());
            ps.setBigDecimal(17, row.intervalExcessMinutes());
            ps.setString(18, row.submitter());
            ps.setString(19, row.commandKey());
            ps.setLong(20, row.createdNanos());
            return ps;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        return new AnomalyRow(id, row.channelId(), row.anomalyType(), row.ruleVersion(),
                row.previousReadingId(), row.readingId(), row.previousUtcNanos(), row.readingUtcNanos(),
                row.previousCumulative(), row.readingCumulative(), row.volumeDelta(), row.durationMinutes(),
                row.actualFlowRate(), row.requiredMaxFlowRate(), row.requiredMaxIntervalMinutes(),
                row.regressionAmount(), row.flowRateExcess(), row.intervalExcessMinutes(),
                row.submitter(), row.commandKey(), row.createdNanos());
    }

    /** 按主键查异常，无则 null。 */
    public AnomalyRow findAnomalyById(long id) {
        try {
            return jdbc.queryForObject(ANOMALY_COLUMNS + " FROM meter_anomaly WHERE id = ?",
                    ANOMALY_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定异常行（FOR UPDATE），无则 null。 */
    public AnomalyRow lockAnomalyById(long id) {
        try {
            return jdbc.queryForObject(ANOMALY_COLUMNS + " FROM meter_anomaly WHERE id = ? FOR UPDATE",
                    ANOMALY_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 统计渠道未解除异常数量（存在未解除异常即冻结）。 */
    public int countActiveAnomalies(String channelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter_anomaly a WHERE a.channel_id = ? AND NOT EXISTS ("
                        + "SELECT 1 FROM meter_anomaly_resolution r WHERE r.anomaly_id = a.id)",
                Integer.class, channelId);
        return count == null ? 0 : count;
    }

    /** 渠道是否存在未解除异常。 */
    public boolean channelFrozen(String channelId) {
        return countActiveAnomalies(channelId) > 0;
    }

    /** 渠道全部异常，按触发读数时刻、异常主键升序（稳定排序）。 */
    public List<AnomalyRow> listAnomalies(String channelId) {
        return jdbc.query(ANOMALY_COLUMNS + " FROM meter_anomaly WHERE channel_id = ?"
                + " ORDER BY reading_utc_nanos, id", ANOMALY_MAPPER, channelId);
    }

    /** 插入解除证据并返回带主键的行。 */
    public ResolutionRow insertResolution(ResolutionRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO meter_anomaly_resolution (anomaly_id, channel_id, resolver, diagnosis_note,"
                            + " replacement_utc_nanos, replacement_cumulative, command_key, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, row.anomalyId());
            ps.setString(2, row.channelId());
            ps.setString(3, row.resolver());
            ps.setString(4, row.diagnosisNote());
            ps.setLong(5, row.replacementUtcNanos());
            ps.setBigDecimal(6, row.replacementCumulative());
            ps.setString(7, row.commandKey());
            ps.setLong(8, row.createdNanos());
            return ps;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        return new ResolutionRow(id, row.anomalyId(), row.channelId(), row.resolver(), row.diagnosisNote(),
                row.replacementUtcNanos(), row.replacementCumulative(), row.commandKey(), row.createdNanos());
    }

    /** 按异常主键查解除证据，无则 null。 */
    public ResolutionRow findResolutionByAnomaly(long anomalyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, anomaly_id, channel_id, resolver, diagnosis_note, replacement_utc_nanos,"
                            + " replacement_cumulative, command_key, created_nanos"
                            + " FROM meter_anomaly_resolution WHERE anomaly_id = ?",
                    RESOLUTION_MAPPER, anomalyId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
