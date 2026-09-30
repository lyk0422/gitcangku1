package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 仓储温度偏差域持久化层：所有 SQL 参数化；时刻以 ISO-8601 UTC 字符串存取，温度与限值单位℃。
 */
@Repository
public class StorageRepository {

    /**
     * storage_segment 表行记录。
     */
    public record SegmentRow(long id, String batchKey, String segmentKey,
                             BigDecimal upperLimit, BigDecimal lowerLimit, String createdAt) {
    }

    /**
     * temperature_reading 表行记录；id 同时作为同段读数先后顺序依据。
     */
    public record ReadingRow(long id, String batchKey, String segmentKey, String requestId,
                             String readingTime, BigDecimal temperatureCelsius, String createdAt) {
    }

    /**
     * temperature_deviation 表行记录；限值/温度快照不随后续限值修改回写。
     */
    public record DeviationRow(long id, String deviationKey, String batchKey, String segmentKey,
                               long readingId, String type, String status, int version,
                               BigDecimal observedTemperatureCelsius,
                               BigDecimal upperLimitSnapshot, BigDecimal lowerLimitSnapshot,
                               String gapStart, String gapEnd, String createdAt, String disposedAt) {
    }

    /**
     * deviation_confirmation 表行记录。
     */
    public record ConfirmationRow(long id, long deviationId, int version, String actorId,
                                  String role, String note, String commandKey, String createdAt) {
    }

    /**
     * reading_request 表行记录：读数首次成功登记的响应快照，用于 requestId 重放/判异。
     */
    public record ReadingRequestRow(String requestId, String batchKey, String segmentKey,
                                    String fingerprint, int responseStatus,
                                    String responseBody, String createdAt) {
    }

    /**
     * merge_order 表行记录。
     */
    public record MergeRow(long id, String childKey, String createdAt) {
    }

    /**
     * merge_parent 表行记录。
     */
    public record MergeParentRow(long id, long mergeId, String parentKey, int seq, String createdAt) {
    }

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("segment_key"),
            rs.getBigDecimal("upper_limit"), rs.getBigDecimal("lower_limit"),
            rs.getString("created_at"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("segment_key"),
            rs.getString("request_id"), rs.getString("reading_time"),
            rs.getBigDecimal("temperature_celsius"), rs.getString("created_at"));

    private static final RowMapper<DeviationRow> DEVIATION_MAPPER = (rs, n) -> new DeviationRow(
            rs.getLong("id"), rs.getString("deviation_key"), rs.getString("batch_key"),
            rs.getString("segment_key"), rs.getLong("reading_id"), rs.getString("type"),
            rs.getString("status"), rs.getInt("version"),
            rs.getBigDecimal("observed_temperature_celsius"),
            rs.getBigDecimal("upper_limit_snapshot"), rs.getBigDecimal("lower_limit_snapshot"),
            rs.getString("gap_start"), rs.getString("gap_end"),
            rs.getString("created_at"), rs.getString("disposed_at"));

    private static final RowMapper<ConfirmationRow> CONFIRMATION_MAPPER = (rs, n) ->
            new ConfirmationRow(rs.getLong("id"), rs.getLong("deviation_id"), rs.getInt("version"),
                    rs.getString("actor_id"), rs.getString("role"), rs.getString("note"),
                    rs.getString("command_key"), rs.getString("created_at"));

    private static final RowMapper<ReadingRequestRow> READING_REQUEST_MAPPER = (rs, n) ->
            new ReadingRequestRow(rs.getString("request_id"), rs.getString("batch_key"),
                    rs.getString("segment_key"), rs.getString("fingerprint"),
                    rs.getInt("response_status"), rs.getString("response_body"),
                    rs.getString("created_at"));

    private static final RowMapper<MergeRow> MERGE_MAPPER = (rs, n) -> new MergeRow(
            rs.getLong("id"), rs.getString("child_key"), rs.getString("created_at"));

    private static final RowMapper<MergeParentRow> MERGE_PARENT_MAPPER = (rs, n) ->
            new MergeParentRow(rs.getLong("id"), rs.getLong("merge_id"),
                    rs.getString("parent_key"), rs.getInt("seq"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public StorageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 仓储段 ----------

    public Optional<SegmentRow> findSegment(String batchKey, String segmentKey) {
        return jdbc.query("SELECT * FROM storage_segment WHERE batch_key = ? AND segment_key = ?",
                        SEGMENT_MAPPER, batchKey, segmentKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取仓储段；读数登记事务先锁批行再锁段行，避免同段并发读数产生重复缺测。
     */
    public Optional<SegmentRow> findSegmentForUpdate(String batchKey, String segmentKey) {
        return jdbc.query(
                        "SELECT * FROM storage_segment WHERE batch_key = ? AND segment_key = ? FOR UPDATE",
                        SEGMENT_MAPPER, batchKey, segmentKey)
                .stream().findFirst();
    }

    public List<SegmentRow> findSegmentsByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM storage_segment WHERE batch_key = ? ORDER BY id",
                SEGMENT_MAPPER, batchKey);
    }

    public void insertSegment(SegmentRow row) {
        jdbc.update("INSERT INTO storage_segment (batch_key, segment_key, upper_limit, lower_limit, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentKey(), row.upperLimit(), row.lowerLimit(), row.createdAt());
    }

    public void updateSegmentLimits(String batchKey, String segmentKey,
                                    BigDecimal upperLimit, BigDecimal lowerLimit) {
        jdbc.update("UPDATE storage_segment SET upper_limit = ?, lower_limit = ?"
                        + " WHERE batch_key = ? AND segment_key = ?",
                upperLimit, lowerLimit, batchKey, segmentKey);
    }

    // ---------- 读数 ----------

    public void insertReading(ReadingRow row) {
        jdbc.update("INSERT INTO temperature_reading (batch_key, segment_key, request_id,"
                        + " reading_time, temperature_celsius, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentKey(), row.requestId(), row.readingTime(),
                row.temperatureCelsius(), row.createdAt());
    }

    public Optional<ReadingRow> findReadingByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE request_id = ?",
                        READING_MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<ReadingRow> findReadingById(long id) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE id = ?", READING_MAPPER, id)
                .stream().findFirst();
    }

    public List<ReadingRow> findReadings(String segmentKey) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE segment_key = ?"
                        + " ORDER BY reading_time, id", READING_MAPPER, segmentKey);
    }

    // ---------- 偏差 ----------

    public void insertDeviation(DeviationRow row) {
        jdbc.update("INSERT INTO temperature_deviation (deviation_key, batch_key, segment_key, reading_id,"
                        + " type, status, version, observed_temperature_celsius,"
                        + " upper_limit_snapshot, lower_limit_snapshot, gap_start, gap_end,"
                        + " created_at, disposed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.deviationKey(), row.batchKey(), row.segmentKey(), row.readingId(),
                row.type(), row.status(), row.version(), row.observedTemperatureCelsius(),
                row.upperLimitSnapshot(), row.lowerLimitSnapshot(),
                row.gapStart(), row.gapEnd(), row.createdAt(), row.disposedAt());
    }

    public Optional<DeviationRow> findDeviationByKey(String deviationKey) {
        return jdbc.query("SELECT * FROM temperature_deviation WHERE deviation_key = ?",
                        DEVIATION_MAPPER, deviationKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取偏差，串行化同一偏差上的双人确认。
     */
    public Optional<DeviationRow> findDeviationByKeyForUpdate(String deviationKey) {
        return jdbc.query("SELECT * FROM temperature_deviation WHERE deviation_key = ? FOR UPDATE",
                        DEVIATION_MAPPER, deviationKey)
                .stream().findFirst();
    }

    public List<DeviationRow> findDeviationsBySegment(String batchKey, String segmentKey) {
        return jdbc.query("SELECT * FROM temperature_deviation WHERE batch_key = ? AND segment_key = ?"
                        + " ORDER BY id", DEVIATION_MAPPER, batchKey, segmentKey);
    }

    public List<DeviationRow> findDeviationsByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM temperature_deviation WHERE batch_key = ? ORDER BY id",
                DEVIATION_MAPPER, batchKey);
    }

    /**
     * 批次下全部未处置偏差；批行锁保护的事务内调用，用于放行/拆分/合批门禁。
     */
    public List<DeviationRow> findOpenDeviationsByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM temperature_deviation WHERE batch_key = ? AND status = 'OPEN'"
                        + " ORDER BY id", DEVIATION_MAPPER, batchKey);
    }

    public int countOpenDeviations(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_deviation WHERE batch_key = ? AND status = 'OPEN'",
                Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    /**
     * 全部存在未处置偏差的批次业务键（去重），用于可用列表门禁过滤。
     */
    public List<String> findBatchesWithOpenDeviations() {
        return jdbc.queryForList(
                "SELECT DISTINCT batch_key FROM temperature_deviation WHERE status = 'OPEN'",
                String.class);
    }

    public void markDeviationDisposed(long deviationId, String disposedAt) {
        jdbc.update("UPDATE temperature_deviation SET status = 'DISPOSED', disposed_at = ?"
                + " WHERE id = ?", disposedAt, deviationId);
    }

    // ---------- 处置确认 ----------

    public void insertConfirmation(ConfirmationRow row) {
        jdbc.update("INSERT INTO deviation_confirmation (deviation_id, version, actor_id, role, note,"
                        + " command_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.deviationId(), row.version(), row.actorId(), row.role(), row.note(),
                row.commandKey(), row.createdAt());
    }

    public List<ConfirmationRow> findConfirmations(long deviationId) {
        return jdbc.query("SELECT * FROM deviation_confirmation WHERE deviation_id = ? ORDER BY id",
                CONFIRMATION_MAPPER, deviationId);
    }

    // ---------- 读数 requestId 快照 ----------

    public Optional<ReadingRequestRow> findReadingRequest(String requestId) {
        return jdbc.query("SELECT * FROM reading_request WHERE request_id = ?",
                        READING_REQUEST_MAPPER, requestId)
                .stream().findFirst();
    }

    public void insertReadingRequest(ReadingRequestRow row) {
        jdbc.update("INSERT INTO reading_request (request_id, batch_key, segment_key, fingerprint,"
                        + " response_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.batchKey(), row.segmentKey(), row.fingerprint(),
                row.responseStatus(), row.responseBody(), row.createdAt());
    }

    // ---------- 合批 ----------

    public long insertMergeOrder(MergeRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO merge_order (child_key, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.childKey());
            ps.setString(2, row.createdAt());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertMergeParent(MergeParentRow row) {
        jdbc.update("INSERT INTO merge_parent (merge_id, parent_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.mergeId(), row.parentKey(), row.seq(), row.createdAt());
    }

    public Optional<MergeRow> findMergeByChild(String childKey) {
        return jdbc.query("SELECT * FROM merge_order WHERE child_key = ?", MERGE_MAPPER, childKey)
                .stream().findFirst();
    }

    public List<MergeParentRow> findMergeParents(long mergeId) {
        return jdbc.query("SELECT * FROM merge_parent WHERE merge_id = ? ORDER BY seq",
                MERGE_PARENT_MAPPER, mergeId);
    }
}
