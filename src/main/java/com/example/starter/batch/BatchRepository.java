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
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String createdAt) {
    }

    /**
     * test_result 表行记录。
     */
    public record TestRow(long id, String batchKey, String testKey, String testItem,
                          String outcome, String inspector, String createdAt) {
    }

    /**
     * approval 表行记录。assaySnapshot 为批准时平行测定冻结快照（无平行项时为 null）；
     * valid 为 1 有效、0 已失效（检验项开启新轮次使旧批准失效）。
     */
    public record ApprovalRow(long id, String batchKey, String commandKey, String actorId,
                              String role, int seq, String assaySnapshot, int valid, String createdAt) {
    }

    /**
     * assay_round 表行记录：平行测定轮次；version 等于已写入读数条数。
     */
    public record AssayRoundRow(long id, String batchKey, String testItem, int roundNo,
                                String meanMin, String meanMax, String maxRange, String status,
                                int version, String meanValue, String rangeValue,
                                String createdBy, String createdAt, String sealedAt) {
    }

    /**
     * assay_reading 表行记录：轮次内按 seq 1～3 唯一的读数原值。
     */
    public record AssayReadingRow(long id, long roundId, String batchKey, String testItem,
                                  int seq, String value, String createdBy, String createdAt) {
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

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<TestRow> TEST_MAPPER = (rs, n) -> new TestRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("test_key"),
            rs.getString("test_item"), rs.getString("outcome"),
            rs.getString("inspector"), rs.getString("created_at"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("actor_id"), rs.getString("role"),
            rs.getInt("seq"), rs.getString("assay_snapshot"), rs.getInt("valid"),
            rs.getString("created_at"));

    private static final RowMapper<AssayRoundRow> ASSAY_ROUND_MAPPER = (rs, n) -> new AssayRoundRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("test_item"),
            rs.getInt("round_no"), rs.getString("mean_min"), rs.getString("mean_max"),
            rs.getString("max_range"), rs.getString("status"), rs.getInt("version"),
            rs.getString("mean_value"), rs.getString("range_value"),
            rs.getString("created_by"), rs.getString("created_at"), rs.getString("sealed_at"));

    private static final RowMapper<AssayReadingRow> ASSAY_READING_MAPPER = (rs, n) -> new AssayReadingRow(
            rs.getLong("id"), rs.getLong("round_id"), rs.getString("batch_key"),
            rs.getString("test_item"), rs.getInt("seq"), rs.getString("reading_value"),
            rs.getString("created_by"), rs.getString("created_at"));

    private static final RowMapper<RecallRow> RECALL_MAPPER = (rs, n) -> new RecallRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("actor_id"), rs.getString("reason"), rs.getString("created_at"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_type"), rs.getString("command_key"), rs.getString("fingerprint"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private static final RowMapper<LineageRow> LINEAGE_MAPPER = (rs, n) -> new LineageRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getInt("seq"), rs.getString("created_at"));

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
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.createdAt());
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
        return jdbc.query("SELECT * FROM approval WHERE batch_key = ? ORDER BY id",
                APPROVAL_MAPPER, batchKey);
    }

    /**
     * 当前有效批准（未因新轮次开启而失效），按提交顺序。
     */
    public List<ApprovalRow> findValidApprovals(String batchKey) {
        return jdbc.query("SELECT * FROM approval WHERE batch_key = ? AND valid = 1 ORDER BY id",
                APPROVAL_MAPPER, batchKey);
    }

    public void insertApproval(ApprovalRow row) {
        jdbc.update("INSERT INTO approval (batch_key, command_key, actor_id, role, seq,"
                        + " assay_snapshot, valid, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.commandKey(), row.actorId(), row.role(),
                row.seq(), row.assaySnapshot(), row.valid(), row.createdAt());
    }

    /**
     * 使该批次当前全部有效批准失效（开启新平行测定轮次时调用）；历史行保留不改写。
     */
    public void invalidateApprovals(String batchKey) {
        jdbc.update("UPDATE approval SET valid = 0 WHERE batch_key = ? AND valid = 1", batchKey);
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
     * 新增平行测定轮次；返回自增主键。
     */
    public long insertAssayRound(AssayRoundRow row) {
        jdbc.update("INSERT INTO assay_round (batch_key, test_item, round_no, mean_min, mean_max,"
                        + " max_range, status, version, mean_value, range_value, created_by,"
                        + " created_at, sealed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.testItem(), row.roundNo(), row.meanMin(), row.meanMax(),
                row.maxRange(), row.status(), row.version(), row.meanValue(), row.rangeValue(),
                row.createdBy(), row.createdAt(), row.sealedAt());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM assay_round WHERE batch_key = ?"
                + " AND test_item = ? AND round_no = ?", Long.class,
                row.batchKey(), row.testItem(), row.roundNo());
        return id == null ? 0L : id;
    }

    /**
     * 某检验项的全部轮次，按轮次序号升序；最后一行即当前轮。
     */
    public List<AssayRoundRow> findAssayRounds(String batchKey, String testItem) {
        return jdbc.query("SELECT * FROM assay_round WHERE batch_key = ? AND test_item = ?"
                + " ORDER BY round_no", ASSAY_ROUND_MAPPER, batchKey, testItem);
    }

    /**
     * 某批次的全部轮次，按检验项与轮次序号排序。
     */
    public List<AssayRoundRow> findAllAssayRounds(String batchKey) {
        return jdbc.query("SELECT * FROM assay_round WHERE batch_key = ?"
                + " ORDER BY test_item, round_no", ASSAY_ROUND_MAPPER, batchKey);
    }

    /**
     * 轮次版本推进（写入一条读数后 +1）。
     */
    public void bumpAssayRoundVersion(long roundId, int version) {
        jdbc.update("UPDATE assay_round SET version = ? WHERE id = ?", version, roundId);
    }

    /**
     * 封结轮次：写入判定状态、均值、极差与封结时间；封结后不再修改。
     */
    public void sealAssayRound(long roundId, String status, int version,
                               String meanValue, String rangeValue, String sealedAt) {
        jdbc.update("UPDATE assay_round SET status = ?, version = ?, mean_value = ?,"
                        + " range_value = ?, sealed_at = ? WHERE id = ?",
                status, version, meanValue, rangeValue, sealedAt, roundId);
    }

    public void insertAssayReading(AssayReadingRow row) {
        jdbc.update("INSERT INTO assay_reading (round_id, batch_key, test_item, seq, reading_value,"
                        + " created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.roundId(), row.batchKey(), row.testItem(), row.seq(), row.value(),
                row.createdBy(), row.createdAt());
    }

    /**
     * 某轮次的全部读数，按序号升序。
     */
    public List<AssayReadingRow> findAssayReadings(long roundId) {
        return jdbc.query("SELECT * FROM assay_reading WHERE round_id = ? ORDER BY seq",
                ASSAY_READING_MAPPER, roundId);
    }
}
