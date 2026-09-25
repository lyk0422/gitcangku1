package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 批次域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class BatchRepository {

    /**
     * batch 表行记录；id 同时作为同批次事件的提交顺序依据。
     * temperatureHold 为温控冻结门禁标记，独立于批次主状态。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, boolean temperatureHold, String createdAt) {
    }

    /**
     * test_result 表行记录。
     */
    public record TestRow(long id, String batchKey, String testKey, String testItem,
                          String outcome, String inspector, String createdAt) {
    }

    /**
     * approval 表行记录。
     */
    public record ApprovalRow(long id, String batchKey, String commandKey, String actorId,
                              String role, int seq, String createdAt) {
    }

    /**
     * recall 表行记录。
     */
    public record RecallRow(long id, String batchKey, String commandKey, String actorId,
                            String reason, String createdAt) {
    }

    /**
     * command_log 表行记录：幂等命令快照。
     */
    public record CommandRow(String commandType, String commandKey, String fingerprint,
                             int responseStatus, String responseBody) {
    }

    /**
     * batch_lineage 表行记录：拆分父子关系，创建后不可改写。
     */
    public record LineageRow(long id, String parentKey, String childKey, int seq, String createdAt) {
    }

    /**
     * transport_segment 表行记录：运输温控段，创建后不可改写。
     */
    public record SegmentRow(long id, String segmentKey, String batchKey, String startAt, String endAt,
                             String minTemp, String maxTemp, String recorderId, String status,
                             String createdAt) {
    }

    /**
     * transport_reading 表行记录：按时序上传的温度读数，创建后不可改写。
     */
    public record ReadingRow(long id, String segmentKey, String readAt, String temperature,
                             int seq, String createdAt) {
    }

    /**
     * excursion_disposition 表行记录：EXCURSION 段的逐段处置。
     */
    public record DispositionRow(long id, String segmentKey, String actionNote, String actorId,
                                 String createdAt) {
    }

    /**
     * temperature_release 表行记录：温控冻结解除事实。
     */
    public record ReleaseRow(long id, String batchKey, String commandKey, String investigatorId,
                             String investigationNote, String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getBoolean("temperature_hold"), rs.getString("created_at"));

    private static final RowMapper<TestRow> TEST_MAPPER = (rs, n) -> new TestRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("test_key"),
            rs.getString("test_item"), rs.getString("outcome"),
            rs.getString("inspector"), rs.getString("created_at"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("actor_id"), rs.getString("role"),
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<RecallRow> RECALL_MAPPER = (rs, n) -> new RecallRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("actor_id"), rs.getString("reason"), rs.getString("created_at"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_type"), rs.getString("command_key"), rs.getString("fingerprint"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private static final RowMapper<LineageRow> LINEAGE_MAPPER = (rs, n) -> new LineageRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getString("segment_key"), rs.getString("batch_key"),
            rs.getString("start_at"), rs.getString("end_at"),
            rs.getString("min_temp"), rs.getString("max_temp"), rs.getString("recorder_id"),
            rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getString("segment_key"), rs.getString("read_at"),
            rs.getString("temperature"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<DispositionRow> DISPOSITION_MAPPER = (rs, n) -> new DispositionRow(
            rs.getLong("id"), rs.getString("segment_key"), rs.getString("action_note"),
            rs.getString("actor_id"), rs.getString("created_at"));

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, n) -> new ReleaseRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("investigator_id"), rs.getString("investigation_note"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRow> findBatch(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取批次，串行化同一批次上的检验/批准/召回事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status,"
                        + " temperature_hold, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.temperatureHold(), row.createdAt());
    }

    public void insertRequiredTest(String batchKey, String testItem, int seq) {
        jdbc.update("INSERT INTO batch_required_test (batch_key, test_item, seq) VALUES (?, ?, ?)",
                batchKey, testItem, seq);
    }

    public List<String> findRequiredTests(String batchKey) {
        return jdbc.queryForList(
                "SELECT test_item FROM batch_required_test WHERE batch_key = ? ORDER BY seq",
                String.class, batchKey);
    }

    public void updateStatus(String batchKey, String status) {
        jdbc.update("UPDATE batch SET status = ? WHERE batch_key = ?", status, batchKey);
    }

    public List<BatchRow> findAvailableBatches() {
        return jdbc.query("SELECT * FROM batch WHERE status <> 'RECALLED' ORDER BY id", BATCH_MAPPER);
    }

    public Optional<TestRow> findTest(String batchKey, String testKey) {
        return jdbc.query("SELECT * FROM test_result WHERE batch_key = ? AND test_key = ?",
                        TEST_MAPPER, batchKey, testKey)
                .stream().findFirst();
    }

    public void insertTest(TestRow row) {
        jdbc.update("INSERT INTO test_result (batch_key, test_key, test_item, outcome, inspector, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.testKey(), row.testItem(), row.outcome(),
                row.inspector(), row.createdAt());
    }

    public List<TestRow> findTests(String batchKey) {
        return jdbc.query("SELECT * FROM test_result WHERE batch_key = ? ORDER BY id",
                TEST_MAPPER, batchKey);
    }

    public List<ApprovalRow> findApprovals(String batchKey) {
        return jdbc.query("SELECT * FROM approval WHERE batch_key = ? ORDER BY seq",
                APPROVAL_MAPPER, batchKey);
    }

    public void insertApproval(ApprovalRow row) {
        jdbc.update("INSERT INTO approval (batch_key, command_key, actor_id, role, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.commandKey(), row.actorId(), row.role(),
                row.seq(), row.createdAt());
    }

    public Optional<RecallRow> findRecall(String batchKey) {
        return jdbc.query("SELECT * FROM recall WHERE batch_key = ?", RECALL_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertRecall(RecallRow row) {
        jdbc.update("INSERT INTO recall (batch_key, command_key, actor_id, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.commandKey(), row.actorId(), row.reason(), row.createdAt());
    }

    public Optional<CommandRow> findCommand(String commandType, String commandKey) {
        return jdbc.query("SELECT * FROM command_log WHERE command_type = ? AND command_key = ?",
                        COMMAND_MAPPER, commandType, commandKey)
                .stream().findFirst();
    }

    public void insertCommand(CommandRow row, String createdAt) {
        jdbc.update("INSERT INTO command_log (command_type, command_key, fingerprint,"
                        + " response_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.commandType(), row.commandKey(), row.fingerprint(),
                row.responseStatus(), row.responseBody(), createdAt);
    }

    public void insertLineage(LineageRow row) {
        jdbc.update("INSERT INTO batch_lineage (parent_key, child_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.seq(), row.createdAt());
    }

    /**
     * 全部血缘边（父→子），用于在内存中推导祖先链与后代集合；关系不可改写，只增不改。
     */
    public List<LineageRow> findAllLineage() {
        return jdbc.query("SELECT * FROM batch_lineage ORDER BY id", LINEAGE_MAPPER);
    }

    /**
     * 某批次的直接父批业务键；每个子批仅一个父批。
     */
    public Optional<String> findParentKey(String childKey) {
        return jdbc.queryForList("SELECT parent_key FROM batch_lineage WHERE child_key = ?",
                        String.class, childKey)
                .stream().findFirst();
    }

    /**
     * 全部被直接召回（RECALLED）的批次业务键。
     */
    public List<String> findRecalledKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch WHERE status = 'RECALLED'",
                String.class);
    }

    /**
     * 置位/清除批次温控冻结门禁；解除只清除门禁，不改写批次主状态与异常历史。
     */
    public void updateTemperatureHold(String batchKey, boolean hold) {
        jdbc.update("UPDATE batch SET temperature_hold = ? WHERE batch_key = ?",
                hold ? 1 : 0, batchKey);
    }

    public void insertSegment(SegmentRow row) {
        jdbc.update("INSERT INTO transport_segment (segment_key, batch_key, start_at, end_at,"
                        + " min_temp, max_temp, recorder_id, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.segmentKey(), row.batchKey(), row.startAt(), row.endAt(),
                row.minTemp(), row.maxTemp(), row.recorderId(), row.status(), row.createdAt());
    }

    public Optional<SegmentRow> findSegment(String segmentKey) {
        return jdbc.query("SELECT * FROM transport_segment WHERE segment_key = ?",
                        SEGMENT_MAPPER, segmentKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取运输段，串行化同段读数并发上传。
     */
    public Optional<SegmentRow> findSegmentForUpdate(String segmentKey) {
        return jdbc.query("SELECT * FROM transport_segment WHERE segment_key = ? FOR UPDATE",
                        SEGMENT_MAPPER, segmentKey)
                .stream().findFirst();
    }

    /**
     * 同批次全部运输段，按开始时刻排序；左闭右开、互不重叠的约束在服务层校验。
     */
    public List<SegmentRow> findSegmentsByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM transport_segment WHERE batch_key = ? ORDER BY start_at, id",
                SEGMENT_MAPPER, batchKey);
    }

    /**
     * 行锁读取同批次全部运输段，用于段登记的重叠校验，按开始时刻排序。
     */
    public List<SegmentRow> findSegmentsByBatchForUpdate(String batchKey) {
        return jdbc.query(
                "SELECT * FROM transport_segment WHERE batch_key = ? ORDER BY start_at, id FOR UPDATE",
                SEGMENT_MAPPER, batchKey);
    }

    public void updateSegmentStatus(String segmentKey, String status) {
        jdbc.update("UPDATE transport_segment SET status = ? WHERE segment_key = ?",
                status, segmentKey);
    }

    public void insertReading(ReadingRow row) {
        jdbc.update("INSERT INTO transport_reading (segment_key, read_at, temperature, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.segmentKey(), row.readAt(), row.temperature(), row.seq(), row.createdAt());
    }

    /**
     * 同段全部读数，按上传顺序（seq/id）返回，用于区间与间隔判定。
     */
    public List<ReadingRow> findReadings(String segmentKey) {
        return jdbc.query("SELECT * FROM transport_reading WHERE segment_key = ? ORDER BY id",
                READING_MAPPER, segmentKey);
    }

    public void insertDisposition(DispositionRow row) {
        jdbc.update("INSERT INTO excursion_disposition (segment_key, action_note, actor_id, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.segmentKey(), row.actionNote(), row.actorId(), row.createdAt());
    }

    public Optional<DispositionRow> findDisposition(String segmentKey) {
        return jdbc.query("SELECT * FROM excursion_disposition WHERE segment_key = ?",
                        DISPOSITION_MAPPER, segmentKey)
                .stream().findFirst();
    }

    public void insertRelease(ReleaseRow row, String createdAt) {
        jdbc.update("INSERT INTO temperature_release (batch_key, command_key, investigator_id,"
                        + " investigation_note, created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.commandKey(), row.investigatorId(),
                row.investigationNote(), createdAt);
    }

    public Optional<ReleaseRow> findRelease(String batchKey) {
        // 同一批次可能经历多次冻结-解除 episode，返回最近一次解除记录
        return jdbc.query("SELECT * FROM temperature_release WHERE batch_key = ? ORDER BY id DESC LIMIT 1",
                        RELEASE_MAPPER, batchKey)
                .stream().findFirst();
    }
}
