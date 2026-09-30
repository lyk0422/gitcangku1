package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 仓储温度域持久化层：仓储段、限值版本、温度读数、偏差事件与处置确认、合批单。
 * 所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取，温度以 DECIMAL 摄氏度存取。
 */
@Repository
public class StorageRepository {

    /**
     * storage_segment 表行记录。
     */
    public record SegmentRow(long id, String batchKey, String segmentKey,
                             BigDecimal lowerLimit, BigDecimal upperLimit, int version,
                             String createdAt, String updatedAt) {
    }

    /**
     * storage_segment_limit 限值版本行记录。
     */
    public record LimitVersionRow(long id, long segmentId, int version,
                                  BigDecimal lowerLimit, BigDecimal upperLimit, String createdAt) {
    }

    /**
     * temperature_reading 表行记录；限值为入库时生效版本的快照。
     */
    public record ReadingRow(long id, String batchKey, long segmentId, String requestId,
                             String readAt, BigDecimal temperature,
                             BigDecimal lowerLimit, BigDecimal upperLimit, String createdAt) {
    }

    /**
     * storage_excursion 表行记录；prevReadingId/intervalFrom/intervalTo 仅缺测偏差有值。
     */
    public record ExcursionRow(long id, String batchKey, long segmentId, String excursionKey,
                               String eventType, int limitVersion, long readingId,
                               Long prevReadingId, String intervalFrom, String intervalTo,
                               String detail, String status, String createdAt, String resolvedAt) {
    }

    /**
     * excursion_confirmation 表行记录。
     */
    public record ConfirmationRow(long id, long excursionId, String commandKey, String actorId,
                                  String role, int limitVersion, int seq, String createdAt) {
    }

    /**
     * batch_merge 合批单行记录。
     */
    public record MergeRow(long id, String mergeKey, String childKey, String createdAt) {
    }

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("segment_key"),
            rs.getBigDecimal("lower_limit"), rs.getBigDecimal("upper_limit"),
            rs.getInt("version"), rs.getString("created_at"), rs.getString("updated_at"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getLong("segment_id"),
            rs.getString("request_id"), rs.getString("read_at"), rs.getBigDecimal("temperature"),
            rs.getBigDecimal("lower_limit"), rs.getBigDecimal("upper_limit"),
            rs.getString("created_at"));

    private static final RowMapper<ExcursionRow> EXCURSION_MAPPER = (rs, n) -> new ExcursionRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getLong("segment_id"),
            rs.getString("excursion_key"), rs.getString("event_type"),
            rs.getInt("limit_version"), rs.getLong("reading_id"),
            (Long) rs.getObject("prev_reading_id"),
            rs.getString("interval_from"), rs.getString("interval_to"),
            rs.getString("detail"), rs.getString("status"),
            rs.getString("created_at"), rs.getString("resolved_at"));

    private static final RowMapper<ConfirmationRow> CONFIRMATION_MAPPER = (rs, n) ->
            new ConfirmationRow(rs.getLong("id"), rs.getLong("excursion_id"),
                    rs.getString("command_key"), rs.getString("actor_id"), rs.getString("role"),
                    rs.getInt("limit_version"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<MergeRow> MERGE_MAPPER = (rs, n) -> new MergeRow(
            rs.getLong("id"), rs.getString("merge_key"), rs.getString("child_key"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public StorageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 仓储段与限值版本 ----------

    public void insertSegment(SegmentRow row) {
        jdbc.update("INSERT INTO storage_segment (batch_key, segment_key, lower_limit, upper_limit,"
                        + " version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentKey(), row.lowerLimit(), row.upperLimit(),
                row.version(), row.createdAt(), row.updatedAt());
    }

    public Optional<SegmentRow> findSegment(String batchKey, String segmentKey) {
        return jdbc.query("SELECT * FROM storage_segment WHERE batch_key = ? AND segment_key = ?",
                        SEGMENT_MAPPER, batchKey, segmentKey)
                .stream().findFirst();
    }

    public Optional<SegmentRow> findSegmentById(long segmentId) {
        return jdbc.query("SELECT * FROM storage_segment WHERE id = ?", SEGMENT_MAPPER, segmentId)
                .stream().findFirst();
    }

    public List<SegmentRow> findSegments(String batchKey) {
        return jdbc.query("SELECT * FROM storage_segment WHERE batch_key = ? ORDER BY id",
                SEGMENT_MAPPER, batchKey);
    }

    public void insertLimitVersion(long segmentId, int version, BigDecimal lowerLimit,
                                   BigDecimal upperLimit, String createdAt) {
        jdbc.update("INSERT INTO storage_segment_limit (segment_id, version, lower_limit, upper_limit,"
                        + " created_at) VALUES (?, ?, ?, ?, ?)",
                segmentId, version, lowerLimit, upperLimit, createdAt);
    }

    public void updateSegmentLimits(long segmentId, BigDecimal lowerLimit, BigDecimal upperLimit,
                                    int version, String updatedAt) {
        jdbc.update("UPDATE storage_segment SET lower_limit = ?, upper_limit = ?, version = ?,"
                        + " updated_at = ? WHERE id = ?",
                lowerLimit, upperLimit, version, updatedAt, segmentId);
    }

    // ---------- 温度读数 ----------

    public void insertReading(ReadingRow row) {
        jdbc.update("INSERT INTO temperature_reading (batch_key, segment_id, request_id, read_at,"
                        + " temperature, lower_limit, upper_limit, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentId(), row.requestId(), row.readAt(),
                row.temperature(), row.lowerLimit(), row.upperLimit(), row.createdAt());
    }

    public Optional<ReadingRow> findReadingByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE request_id = ?",
                        READING_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 同段采集时刻严格早于给定时刻的最后一条读数（间隔判定的“前一读数”）。
     */
    public Optional<ReadingRow> findPreviousReading(long segmentId, String readAt) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE segment_id = ? AND read_at < ?"
                        + " ORDER BY read_at DESC, id DESC LIMIT 1",
                        READING_MAPPER, segmentId, readAt)
                .stream().findFirst();
    }

    public List<ReadingRow> findReadings(long segmentId, String upToReadAt) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE segment_id = ? AND read_at <= ?"
                        + " ORDER BY read_at, id",
                READING_MAPPER, segmentId, upToReadAt);
    }

    // ---------- 偏差事件 ----------

    public void insertExcursion(ExcursionRow row) {
        jdbc.update("INSERT INTO storage_excursion (batch_key, segment_id, excursion_key, event_type,"
                        + " limit_version, reading_id, prev_reading_id, interval_from, interval_to,"
                        + " detail, status, created_at, resolved_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.segmentId(), row.excursionKey(), row.eventType(),
                row.limitVersion(), row.readingId(), row.prevReadingId(),
                row.intervalFrom(), row.intervalTo(), row.detail(),
                row.status(), row.createdAt(), row.resolvedAt());
    }

    public Optional<StorageRepository.ExcursionRow> findExcursion(String excursionKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE excursion_key = ?",
                        EXCURSION_MAPPER, excursionKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取偏差，串行化同一偏差上的双人确认。
     */
    public Optional<ExcursionRow> findExcursionForUpdate(String excursionKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE excursion_key = ? FOR UPDATE",
                        EXCURSION_MAPPER, excursionKey)
                .stream().findFirst();
    }

    public List<ExcursionRow> findExcursionsBySegment(long segmentId, String upToCreatedAt) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE segment_id = ? AND created_at <= ?"
                        + " ORDER BY id",
                EXCURSION_MAPPER, segmentId, upToCreatedAt);
    }

    /**
     * 批次下全部未处置偏差（跨全部仓储段）；存在即门禁 BLOCKED。
     */
    public List<ExcursionRow> findOpenExcursions(String batchKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE batch_key = ? AND status = 'OPEN'"
                + " ORDER BY id", EXCURSION_MAPPER, batchKey);
    }

    public void resolveExcursion(long excursionId, String resolvedAt) {
        jdbc.update("UPDATE storage_excursion SET status = 'RESOLVED', resolved_at = ? WHERE id = ?",
                resolvedAt, excursionId);
    }

    // ---------- 处置确认 ----------

    public void insertConfirmation(ConfirmationRow row) {
        jdbc.update("INSERT INTO excursion_confirmation (excursion_id, command_key, actor_id, role,"
                        + " limit_version, seq, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.excursionId(), row.commandKey(), row.actorId(), row.role(),
                row.limitVersion(), row.seq(), row.createdAt());
    }

    public List<ConfirmationRow> findConfirmations(long excursionId) {
        return jdbc.query("SELECT * FROM excursion_confirmation WHERE excursion_id = ? ORDER BY seq",
                CONFIRMATION_MAPPER, excursionId);
    }

    // ---------- 合批 ----------

    public Optional<MergeRow> findMerge(String mergeKey) {
        return jdbc.query("SELECT * FROM batch_merge WHERE merge_key = ?", MERGE_MAPPER, mergeKey)
                .stream().findFirst();
    }

    public Optional<MergeRow> findMergeByChild(String childKey) {
        return jdbc.query("SELECT * FROM batch_merge WHERE child_key = ?", MERGE_MAPPER, childKey)
                .stream().findFirst();
    }

    public void insertMerge(MergeRow row) {
        jdbc.update("INSERT INTO batch_merge (merge_key, child_key, created_at) VALUES (?, ?, ?)",
                row.mergeKey(), row.childKey(), row.createdAt());
    }

    public long findMergeId(String mergeKey) {
        return jdbc.queryForObject("SELECT id FROM batch_merge WHERE merge_key = ?",
                Long.class, mergeKey);
    }

    public void insertMergeParent(long mergeId, String parentKey, int seq, String createdAt) {
        jdbc.update("INSERT INTO batch_merge_parent (merge_id, parent_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                mergeId, parentKey, seq, createdAt);
    }

    /**
     * 某子批的全部合批父批业务键（按请求顺序）。
     */
    public List<String> findMergeParents(String childKey) {
        return jdbc.queryForList(
                "SELECT p.parent_key FROM batch_merge_parent p"
                        + " JOIN batch_merge m ON m.id = p.merge_id WHERE m.child_key = ? ORDER BY p.seq",
                String.class, childKey);
    }

    /**
     * 全部合批边（父→子），用于与拆分边合并推导祖先链与后代集合。
     */
    public List<ParentChild> findAllMergeEdges() {
        return jdbc.query("SELECT p.parent_key AS parent_key, m.child_key AS child_key"
                        + " FROM batch_merge_parent p JOIN batch_merge m ON m.id = p.merge_id"
                        + " ORDER BY p.id",
                (rs, n) -> new ParentChild(rs.getString("parent_key"), rs.getString("child_key")));
    }

    /**
     * 通用父子边。
     */
    public record ParentChild(String parentKey, String childKey) {
    }
}
