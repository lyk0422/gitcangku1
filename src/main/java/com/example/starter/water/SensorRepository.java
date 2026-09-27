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
 * 配水计量传感异常与核销数据访问层（JdbcTemplate，参数化 SQL）。
 * 时间一律为 UTC 纳秒时间戳；水量 DECIMAL(19,3)，瞬时流量 DECIMAL(19,6) 立方米/分钟。
 *
 * <p>渠道相关事务先对 channel 行 SELECT ... FOR UPDATE，读数、核销、冻结解除、规则版本按
 * 事务提交顺序串行裁决。</p>
 */
@Repository
public class SensorRepository {

    /** 渠道锚点行，首次配置规则时创建。 */
    public record ChannelRow(long id, String channelKey, long createdNanos) {
    }

    /** 传感规则版本行，提交后不可变。 */
    public record SensorRuleRow(long id, String channelId, int ruleVersion, BigDecimal maxFlowRate,
                                int maxGapMinutes, String actor, long createdNanos) {
    }

    /** 传感读数行，写入后不可变。 */
    public record ReadingRow(long id, String batchKey, String channelId, int seqNo, long readingNanos,
                             BigDecimal cumulative, int ruleVersion, String actor, long createdNanos) {
    }

    /** 传感异常行，生成后不可变，解除不改写本行。 */
    public record AnomalyRow(long id, String channelId, String batchKey, int ruleVersion,
                             long fromReadingId, long toReadingId, long fromReadingNanos, long toReadingNanos,
                             BigDecimal fromCumulative, BigDecimal toCumulative, String reasons,
                             BigDecimal actualDelta, BigDecimal actualGapMinutes, BigDecimal actualFlowRate,
                             int maxGapMinutes, BigDecimal maxFlowRate, String detectorActor,
                             long createdNanos) {
    }

    /** 渠道冻结行，创建后不可变；是否解除由 anomaly_resolution 是否存在推导。 */
    public record FreezeRow(long id, String channelId, long anomalyId, long frozenNanos) {
    }

    /** 异常解除行，插入后不可变。 */
    public record ResolutionRow(long id, long anomalyId, String channelId, String diagnosisNote,
                                BigDecimal replacementCumulative, long replacementNanos, String resolverActor,
                                long createdNanos) {
    }

    /** 核销流水行，写入后不可变。 */
    public record WriteOffRow(long id, String writeOffKey, String allocationKey, long windowId,
                              String channelId, BigDecimal amount, long atNanos, String actor,
                              long createdNanos) {
    }

    private static final String CHANNEL_COLS = "id, channel_key, created_nanos";
    private static final String RULE_COLS =
            "id, channel_id, rule_version, max_flow_rate, max_gap_minutes, actor, created_nanos";
    private static final String READING_COLS =
            "id, batch_key, channel_id, seq_no, reading_nanos, cumulative, rule_version, actor, created_nanos";
    private static final String ANOMALY_COLS = "id, channel_id, batch_key, rule_version, from_reading_id,"
            + " to_reading_id, from_reading_nanos, to_reading_nanos, from_cumulative, to_cumulative, reasons,"
            + " actual_delta, actual_gap_minutes, actual_flow_rate, max_gap_minutes, max_flow_rate,"
            + " detector_actor, created_nanos";
    private static final String RESOLUTION_COLS = "id, anomaly_id, channel_id, diagnosis_note,"
            + " replacement_cumulative, replacement_nanos, resolver_actor, created_nanos";
    private static final String WRITE_OFF_COLS = "id, write_off_key, allocation_key, window_id, channel_id,"
            + " amount, at_nanos, actor, created_nanos";

    private static final RowMapper<ChannelRow> CHANNEL_MAPPER = (rs, n) -> new ChannelRow(
            rs.getLong("id"), rs.getString("channel_key"), rs.getLong("created_nanos"));

    private static final RowMapper<SensorRuleRow> RULE_MAPPER = (rs, n) -> new SensorRuleRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getInt("rule_version"),
            rs.getBigDecimal("max_flow_rate"), rs.getInt("max_gap_minutes"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("channel_id"), rs.getInt("seq_no"),
            rs.getLong("reading_nanos"), rs.getBigDecimal("cumulative"), rs.getInt("rule_version"),
            rs.getString("actor"), rs.getLong("created_nanos"));

    private static final RowMapper<AnomalyRow> ANOMALY_MAPPER = (rs, n) -> new AnomalyRow(
            rs.getLong("id"), rs.getString("channel_id"), rs.getString("batch_key"), rs.getInt("rule_version"),
            rs.getLong("from_reading_id"), rs.getLong("to_reading_id"),
            rs.getLong("from_reading_nanos"), rs.getLong("to_reading_nanos"),
            rs.getBigDecimal("from_cumulative"), rs.getBigDecimal("to_cumulative"), rs.getString("reasons"),
            rs.getBigDecimal("actual_delta"), rs.getBigDecimal("actual_gap_minutes"),
            rs.getBigDecimal("actual_flow_rate"), rs.getInt("max_gap_minutes"),
            rs.getBigDecimal("max_flow_rate"), rs.getString("detector_actor"), rs.getLong("created_nanos"));

    private static final RowMapper<ResolutionRow> RESOLUTION_MAPPER = (rs, n) -> new ResolutionRow(
            rs.getLong("id"), rs.getLong("anomaly_id"), rs.getString("channel_id"),
            rs.getString("diagnosis_note"), rs.getBigDecimal("replacement_cumulative"),
            rs.getLong("replacement_nanos"), rs.getString("resolver_actor"), rs.getLong("created_nanos"));

    private static final RowMapper<WriteOffRow> WRITE_OFF_MAPPER = (rs, n) -> new WriteOffRow(
            rs.getLong("id"), rs.getString("write_off_key"), rs.getString("allocation_key"),
            rs.getLong("window_id"), rs.getString("channel_id"), rs.getBigDecimal("amount"),
            rs.getLong("at_nanos"), rs.getString("actor"), rs.getLong("created_nanos"));

    private final JdbcTemplate jdbc;

    public SensorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 锁定渠道锚点行（FOR UPDATE），不存在返回 null。 */
    public ChannelRow lockChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + CHANNEL_COLS + " FROM channel WHERE channel_key = ? FOR UPDATE",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按业务键查询渠道锚点行（不加锁，供只读接口），不存在返回 null。 */
    public ChannelRow findChannel(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + CHANNEL_COLS + " FROM channel WHERE channel_key = ?",
                    CHANNEL_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定渠道锚点行（FOR UPDATE），不存在抛 404。 */
    public ChannelRow lockChannelOrThrow(String channelId) {
        ChannelRow channel = lockChannel(channelId);
        if (channel == null) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在或尚未配置传感规则: " + channelId);
        }
        return channel;
    }

    /** 创建渠道锚点行并返回主键。 */
    public long insertChannel(String channelId, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO channel (channel_key, created_nanos) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 插入规则版本并返回主键。 */
    public long insertSensorRule(String channelId, int ruleVersion, BigDecimal maxFlowRate, int maxGapMinutes,
                                 String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sensor_rule (channel_id, rule_version, max_flow_rate, max_gap_minutes, actor,"
                            + " created_nanos) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setInt(2, ruleVersion);
            ps.setBigDecimal(3, maxFlowRate);
            ps.setInt(4, maxGapMinutes);
            ps.setString(5, actor);
            ps.setLong(6, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 查询渠道最新规则版本，无则 null。 */
    public SensorRuleRow findLatestSensorRule(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + RULE_COLS + " FROM sensor_rule WHERE channel_id = ?"
                            + " ORDER BY rule_version DESC LIMIT 1", RULE_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 按主键查询规则版本，无则 null。 */
    public SensorRuleRow findSensorRuleById(long id) {
        try {
            return jdbc.queryForObject("SELECT " + RULE_COLS + " FROM sensor_rule WHERE id = ?",
                    RULE_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入读数并返回主键。 */
    public long insertReading(String batchKey, String channelId, int seqNo, long readingNanos,
                              BigDecimal cumulative, int ruleVersion, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sensor_reading (batch_key, channel_id, seq_no, reading_nanos, cumulative,"
                            + " rule_version, actor, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchKey);
            ps.setString(2, channelId);
            ps.setInt(3, seqNo);
            ps.setLong(4, readingNanos);
            ps.setBigDecimal(5, cumulative);
            ps.setInt(6, ruleVersion);
            ps.setString(7, actor);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询读数，无则 null。 */
    public ReadingRow findReadingById(long id) {
        try {
            return jdbc.queryForObject("SELECT " + READING_COLS + " FROM sensor_reading WHERE id = ?",
                    READING_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部读数，按读数时刻升序、同刻按主键升序（稳定排序）。 */
    public List<ReadingRow> listReadingsByChannel(String channelId) {
        return jdbc.query(
                "SELECT " + READING_COLS + " FROM sensor_reading WHERE channel_id = ?"
                        + " ORDER BY reading_nanos, id", READING_MAPPER, channelId);
    }

    /** 批次全部读数，按批内序号升序。 */
    public List<ReadingRow> listReadingsByBatch(String batchKey) {
        return jdbc.query(
                "SELECT " + READING_COLS + " FROM sensor_reading WHERE batch_key = ? ORDER BY seq_no",
                READING_MAPPER, batchKey);
    }

    /**
     * 渠道时刻最新的一条已存在读数，无则 null。
     * 调用方须已持有渠道行锁，读数提交由渠道行锁串行，故此处不再 FOR UPDATE。
     */
    public ReadingRow findLatestReading(String channelId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + READING_COLS + " FROM sensor_reading WHERE channel_id = ?"
                            + " ORDER BY reading_nanos DESC, id DESC LIMIT 1",
                    READING_MAPPER, channelId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入异常并返回主键。 */
    public long insertAnomaly(AnomalyRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sensor_anomaly (channel_id, batch_key, rule_version, from_reading_id,"
                            + " to_reading_id, from_reading_nanos, to_reading_nanos, from_cumulative,"
                            + " to_cumulative, reasons, actual_delta, actual_gap_minutes, actual_flow_rate,"
                            + " max_gap_minutes, max_flow_rate, detector_actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.channelId());
            ps.setString(2, row.batchKey());
            ps.setInt(3, row.ruleVersion());
            ps.setLong(4, row.fromReadingId());
            ps.setLong(5, row.toReadingId());
            ps.setLong(6, row.fromReadingNanos());
            ps.setLong(7, row.toReadingNanos());
            ps.setBigDecimal(8, row.fromCumulative());
            ps.setBigDecimal(9, row.toCumulative());
            ps.setString(10, row.reasons());
            ps.setBigDecimal(11, row.actualDelta());
            ps.setBigDecimal(12, row.actualGapMinutes());
            ps.setBigDecimal(13, row.actualFlowRate());
            ps.setInt(14, row.maxGapMinutes());
            ps.setBigDecimal(15, row.maxFlowRate());
            ps.setString(16, row.detectorActor());
            ps.setLong(17, row.createdNanos());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按主键查询异常，无则 null。 */
    public AnomalyRow findAnomalyById(long id) {
        try {
            return jdbc.queryForObject("SELECT " + ANOMALY_COLS + " FROM sensor_anomaly WHERE id = ?",
                    ANOMALY_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 锁定异常行（FOR UPDATE），用于串行化解除，无则 null。 */
    public AnomalyRow lockAnomalyById(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + ANOMALY_COLS + " FROM sensor_anomaly WHERE id = ? FOR UPDATE",
                    ANOMALY_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 渠道全部异常，按主键升序（稳定排序）。 */
    public List<AnomalyRow> listAnomaliesByChannel(String channelId) {
        return jdbc.query(
                "SELECT " + ANOMALY_COLS + " FROM sensor_anomaly WHERE channel_id = ? ORDER BY id",
                ANOMALY_MAPPER, channelId);
    }

    /** 插入渠道冻结并返回主键。 */
    public long insertFreeze(String channelId, long anomalyId, long frozenNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO channel_freeze (channel_id, anomaly_id, frozen_nanos) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, channelId);
            ps.setLong(2, anomalyId);
            ps.setLong(3, frozenNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 渠道是否存在未解除的异常（异常存在且无解除记录）。 */
    public boolean hasOpenAnomaly(String channelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sensor_anomaly a WHERE a.channel_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM anomaly_resolution r WHERE r.anomaly_id = a.id)",
                Integer.class, channelId);
        return count != null && count > 0;
    }

    /** 插入异常解除并返回主键。 */
    public long insertResolution(long anomalyId, String channelId, String diagnosisNote,
                                 BigDecimal replacementCumulative, long replacementNanos, String resolverActor,
                                 long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO anomaly_resolution (anomaly_id, channel_id, diagnosis_note,"
                            + " replacement_cumulative, replacement_nanos, resolver_actor, created_nanos)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, anomalyId);
            ps.setString(2, channelId);
            ps.setString(3, diagnosisNote);
            ps.setBigDecimal(4, replacementCumulative);
            ps.setLong(5, replacementNanos);
            ps.setString(6, resolverActor);
            ps.setLong(7, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按异常主键查询解除记录，无则 null。 */
    public ResolutionRow findResolutionByAnomaly(long anomalyId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + RESOLUTION_COLS + " FROM anomaly_resolution WHERE anomaly_id = ?",
                    RESOLUTION_MAPPER, anomalyId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 插入核销流水并返回主键。 */
    public long insertWriteOff(String writeOffKey, String allocationKey, long windowId, String channelId,
                               BigDecimal amount, long atNanos, String actor, long createdNanos) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO write_off (write_off_key, allocation_key, window_id, channel_id, amount,"
                            + " at_nanos, actor, created_nanos) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, writeOffKey);
            ps.setString(2, allocationKey);
            ps.setLong(3, windowId);
            ps.setString(4, channelId);
            ps.setBigDecimal(5, amount);
            ps.setLong(6, atNanos);
            ps.setString(7, actor);
            ps.setLong(8, createdNanos);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按业务键查询核销流水，无则 null。 */
    public WriteOffRow findWriteOffByKey(String writeOffKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + WRITE_OFF_COLS + " FROM write_off WHERE write_off_key = ?",
                    WRITE_OFF_MAPPER, writeOffKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 申请全部核销流水，按发生时刻升序、同刻按主键升序（稳定排序）。 */
    public List<WriteOffRow> listWriteOffsByAllocation(String allocationKey) {
        return jdbc.query(
                "SELECT " + WRITE_OFF_COLS + " FROM write_off WHERE allocation_key = ? ORDER BY at_nanos, id",
                WRITE_OFF_MAPPER, allocationKey);
    }

    /** 申请已核销总量，无则 0。 */
    public BigDecimal sumWriteOffs(String allocationKey) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM write_off WHERE allocation_key = ?",
                BigDecimal.class, allocationKey);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
