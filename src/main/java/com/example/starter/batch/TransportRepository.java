package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 运输温控域持久化层：所有 SQL 参数化；时间与温度均以字符串存取，比较在服务层完成。
 */
@Repository
public class TransportRepository {

    /**
     * transport_segment 表行记录；温度以十进制字符串保存原始精度。
     */
    public record SegmentRow(long id, String batchKey, String segmentKey, String startAt, String endAt,
                             String minTemp, String maxTemp, String recordedBy, String status,
                             String createdAt) {
    }

    /**
     * temperature_reading 表行记录。
     */
    public record ReadingRow(long id, String batchKey, String segmentKey, String recordedAt,
                             String temperature, boolean inRange, String createdAt) {
    }

    /**
     * temperature_hold 表行记录；releasedAt 为 null 表示当前仍在冻结中。
     */
    public record HoldRow(long id, String batchKey, String preStatus, String createdAt,
                          String releasedAt, String releaseActor, String releaseNote) {
    }

    /**
     * excursion_disposition 表行记录：异常段逐段处置，写入后不可改写。
     */
    public record DispositionRow(long id, String batchKey, String segmentKey, String disposition,
                                 String actorId, String createdAt) {
    }

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("segment_key"),
            rs.getString("start_at"), rs.getString("end_at"), rs.getString("min_temp"),
            rs.getString("max_temp"), rs.getString("recorded_by"), rs.getString("status"),
            rs.getString("created_at"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("segment_key"),
            rs.getString("recorded_at"), rs.getString("temperature"), rs.getBoolean("in_range"),
            rs.getString("created_at"));

    private static final RowMapper<HoldRow> HOLD_MAPPER = (rs, n) -> new HoldRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("pre_status"),
            rs.getString("created_at"), rs.getString("released_at"),
            rs.getString("release_actor"), rs.getString("release_note"));

    private static final RowMapper<DispositionRow> DISPOSITION_MAPPER = (rs, n) -> new DispositionRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("segment_key"),
            rs.getString("disposition"), rs.getString("actor_id"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public TransportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SegmentRow> findSegment(String batchKey, String segmentKey) {
        return jdbc.query("SELECT * FROM transport_segment WHERE batch_key = ? AND segment_key = ?",
                        SEGMENT_MAPPER, batchKey, segmentKey)
                .stream().findFirst();
    }

    /**
     * 某批次全部运输段，按登记顺序返回。
     */
    public List<SegmentRow> findSegments(String batchKey) {
        return jdbc.query("SELECT * FROM transport_segment WHERE batch_key = ? ORDER BY id",
                SEGMENT_MAPPER, batchKey);
    }

    public void insertSegment(SegmentRow row) {
        jdbc.update("INSERT INTO transport_segment (batch_key, segment_key, start_at, end_at,"
                        + " min_temp, max_temp, recorded_by, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentKey(), row.startAt(), row.endAt(), row.minTemp(),
                row.maxTemp(), row.recordedBy(), row.status(), row.createdAt());
    }

    public void markSegmentExcursion(String batchKey, String segmentKey) {
        jdbc.update("UPDATE transport_segment SET status = 'EXCURSION'"
                        + " WHERE batch_key = ? AND segment_key = ?",
                batchKey, segmentKey);
    }

    /**
     * 某运输段全部读数，按采集时刻（上传顺序与采集时刻一致，服务层保证严格递增）返回。
     */
    public List<ReadingRow> findReadings(String batchKey, String segmentKey) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE batch_key = ? AND segment_key = ?"
                        + " ORDER BY id", READING_MAPPER,
                batchKey, segmentKey);
    }

    public void insertReading(ReadingRow row) {
        jdbc.update("INSERT INTO temperature_reading (batch_key, segment_key, recorded_at,"
                        + " temperature, in_range, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentKey(), row.recordedAt(), row.temperature(),
                row.inRange(), row.createdAt());
    }

    /**
     * 当前未解除（released_at 为 NULL）的温控冻结；一个批次同一时刻最多一行。
     */
    public Optional<HoldRow> findActiveHold(String batchKey) {
        return jdbc.query("SELECT * FROM temperature_hold WHERE batch_key = ? AND released_at IS NULL"
                        + " ORDER BY id", HOLD_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 某批次全部温控冻结记录（含已解除），按冻结顺序返回。
     */
    public List<HoldRow> findHolds(String batchKey) {
        return jdbc.query("SELECT * FROM temperature_hold WHERE batch_key = ? ORDER BY id",
                HOLD_MAPPER, batchKey);
    }

    public void insertHold(HoldRow row) {
        jdbc.update("INSERT INTO temperature_hold (batch_key, pre_status, created_at)"
                        + " VALUES (?, ?, ?)",
                row.batchKey(), row.preStatus(), row.createdAt());
    }

    public void releaseHold(long holdId, String releasedAt, String releaseActor, String releaseNote) {
        jdbc.update("UPDATE temperature_hold SET released_at = ?, release_actor = ?,"
                        + " release_note = ? WHERE id = ?",
                releasedAt, releaseActor, releaseNote, holdId);
    }

    public List<DispositionRow> findDispositions(String batchKey) {
        return jdbc.query("SELECT * FROM excursion_disposition WHERE batch_key = ? ORDER BY id",
                DISPOSITION_MAPPER, batchKey);
    }

    public void insertDisposition(DispositionRow row) {
        jdbc.update("INSERT INTO excursion_disposition (batch_key, segment_key, disposition,"
                        + " actor_id, created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentKey(), row.disposition(), row.actorId(), row.createdAt());
    }
}
