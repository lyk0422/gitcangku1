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
     * line_run 表行记录：生产线加工顺序登记，创建后不可改写。
     */
    public record LineRunRow(long id, String lineCode, int seq, String batchKey,
                             String prevBatchKey, String createdAt) {
    }

    /**
     * cleaning_record 表行记录：批间清洗，状态单向迁移 PENDING→CONFIRMED/REJECTED。
     */
    public record CleaningRow(long id, String prevBatchKey, String nextBatchKey, String residual,
                              String submittedBy, String status, int version, String confirmedBy,
                              String createdAt, String confirmedAt) {
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
            rs.getInt("seq"), rs.getString("created_at"));
    private static final RowMapper<LineRunRow> LINE_RUN_MAPPER = (rs, n) -> new LineRunRow(
            rs.getLong("id"), rs.getString("line_code"), rs.getInt("seq"),
            rs.getString("batch_key"), rs.getString("prev_batch_key"), rs.getString("created_at"));

    private static final RowMapper<CleaningRow> CLEANING_MAPPER = (rs, n) -> new CleaningRow(
            rs.getLong("id"), rs.getString("prev_batch_key"), rs.getString("next_batch_key"),
            rs.getString("residual"), rs.getString("submitted_by"), rs.getString("status"),
            rs.getInt("version"), rs.getString("confirmed_by"),
            rs.getString("created_at"), rs.getString("confirmed_at"));

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
     * 某生产线当前最后一笔登记并加行锁，用于串行化同线登记；无登记时返回空。
     */
    public Optional<LineRunRow> findLastLineRunForUpdate(String lineCode) {
        return jdbc.query("SELECT * FROM line_run WHERE line_code = ? ORDER BY seq DESC LIMIT 1 FOR UPDATE",
                        LINE_RUN_MAPPER, lineCode)
                .stream().findFirst();
    }

    /**
     * 某批次的唯一上机登记；一批仅可上机一次。
     */
    public Optional<LineRunRow> findLineRunByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM line_run WHERE batch_key = ?", LINE_RUN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertLineRun(LineRunRow row) {
        jdbc.update("INSERT INTO line_run (line_code, seq, batch_key, prev_batch_key, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.lineCode(), row.seq(), row.batchKey(), row.prevBatchKey(), row.createdAt());
    }

    /**
     * 某生产线的全部加工顺序登记，按顺序号升序。
     */
    public List<LineRunRow> findLineRuns(String lineCode) {
        return jdbc.query("SELECT * FROM line_run WHERE line_code = ? ORDER BY seq",
                LINE_RUN_MAPPER, lineCode);
    }

    public void insertCleaning(CleaningRow row) {
        jdbc.update("INSERT INTO cleaning_record (prev_batch_key, next_batch_key, residual,"
                        + " submitted_by, status, version, confirmed_by, created_at, confirmed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.prevBatchKey(), row.nextBatchKey(), row.residual(), row.submittedBy(),
                row.status(), row.version(), row.confirmedBy(), row.createdAt(), row.confirmedAt());
    }

    public Optional<CleaningRow> findCleaning(long cleaningId) {
        return jdbc.query("SELECT * FROM cleaning_record WHERE id = ?", CLEANING_MAPPER, cleaningId)
                .stream().findFirst();
    }

    /**
     * 行锁读取清洗记录，串行化同一记录上的并发确认。
     */
    public Optional<CleaningRow> findCleaningForUpdate(long cleaningId) {
        return jdbc.query("SELECT * FROM cleaning_record WHERE id = ? FOR UPDATE",
                        CLEANING_MAPPER, cleaningId)
                .stream().findFirst();
    }

    /**
     * 状态迁移：仅当记录仍处于 PENDING 且版本匹配时更新，返回是否生效。
     */
    public boolean transitionCleaning(long cleaningId, int expectedVersion, String newStatus,
                                      String confirmedBy, String confirmedAt) {
        return jdbc.update("UPDATE cleaning_record SET status = ?, version = version + 1,"
                        + " confirmed_by = ?, confirmed_at = ?"
                        + " WHERE id = ? AND status = 'PENDING' AND version = ?",
                newStatus, confirmedBy, confirmedAt, cleaningId, expectedVersion) == 1;
    }

    /**
     * 两批之间的全部清洗记录（按提交顺序）；清洗只增不改写历史。
     */
    public List<CleaningRow> findCleaningsBetween(String prevBatchKey, String nextBatchKey) {
        return jdbc.query("SELECT * FROM cleaning_record WHERE prev_batch_key = ?"
                        + " AND next_batch_key = ? ORDER BY id",
                CLEANING_MAPPER, prevBatchKey, nextBatchKey);
    }

    /**
     * 两批之间已确认合格的清洗记录并加行锁，用于放行时冻结合格清洗。
     */
    public Optional<CleaningRow> findConfirmedCleaningForUpdate(String prevBatchKey, String nextBatchKey) {
        return jdbc.query("SELECT * FROM cleaning_record WHERE prev_batch_key = ?"
                        + " AND next_batch_key = ? AND status = 'CONFIRMED'"
                        + " ORDER BY id DESC LIMIT 1 FOR UPDATE",
                CLEANING_MAPPER, prevBatchKey, nextBatchKey)
                .stream().findFirst();
    }

    /**
     * 自增主键查询：按清洗记录全部业务字段反查其 ID（插入后同事务内可见）。
     */
    public long findCleaningId(String prevBatchKey, String nextBatchKey, String residual,
                               String submittedBy, String createdAt) {
        return jdbc.queryForObject("SELECT id FROM cleaning_record WHERE prev_batch_key = ?"
                        + " AND next_batch_key = ? AND residual = ? AND submitted_by = ?"
                        + " AND created_at = ? ORDER BY id DESC LIMIT 1",
                Long.class, prevBatchKey, nextBatchKey, residual, submittedBy, createdAt);
    }
}
