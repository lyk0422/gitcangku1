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
     * recall 表行记录；version 为召回代次，recallStatus 为 ACTIVE/RELEASED，
     * priorStatus 为召回前批次状态（解除时恢复），releasedAt 未解除时为 null。
     */
    public record RecallRow(long id, String batchKey, String commandKey, String actorId,
                            String reason, int version, String recallStatus, String priorStatus,
                            String createdAt, String releasedAt) {
    }

    /**
     * retest 表行记录：召回影响范围内的复检结果。
     */
    public record RetestRow(long id, String batchKey, String retestKey, String outcome,
                            String inspector, String createdAt) {
    }

    /**
     * recall_release 表行记录：召回解除申请；decidedAt 未批准时为 null。
     */
    public record ReleaseRow(long id, String releaseKey, String batchKey, int recallVersion,
                             String correctiveMeasures, String retestBatches, String approver,
                             String applicant, String status, String createdAt, String decidedAt) {
    }

    /**
     * recall_release_snapshot 表行记录：批准时写入的不可变评审快照。
     */
    public record SnapshotRow(long id, String releaseKey, String batchKey, int recallVersion,
                              String closureBatches, String correctiveMeasures, String approver,
                              String createdAt) {
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
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<RecallRow> RECALL_MAPPER = (rs, n) -> new RecallRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("actor_id"), rs.getString("reason"), rs.getInt("version"),
            rs.getString("recall_status"), rs.getString("prior_status"),
            rs.getString("created_at"), rs.getString("released_at"));

    private static final RowMapper<RetestRow> RETEST_MAPPER = (rs, n) -> new RetestRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("retest_key"),
            rs.getString("outcome"), rs.getString("inspector"), rs.getString("created_at"));

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, n) -> new ReleaseRow(
            rs.getLong("id"), rs.getString("release_key"), rs.getString("batch_key"),
            rs.getInt("recall_version"), rs.getString("corrective_measures"),
            rs.getString("retest_batches"), rs.getString("approver"), rs.getString("applicant"),
            rs.getString("status"), rs.getString("created_at"), rs.getString("decided_at"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("release_key"), rs.getString("batch_key"),
            rs.getInt("recall_version"), rs.getString("closure_batches"),
            rs.getString("corrective_measures"), rs.getString("approver"),
            rs.getString("created_at"));

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

    /**
     * 某批次最新一代召回记录（可能为 ACTIVE 或 RELEASED）；无召回记录时为空。
     */
    public Optional<RecallRow> findRecall(String batchKey) {
        return jdbc.query("SELECT * FROM recall WHERE batch_key = ? ORDER BY id DESC",
                        RECALL_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertRecall(RecallRow row) {
        jdbc.update("INSERT INTO recall (batch_key, command_key, actor_id, reason, version,"
                        + " recall_status, prior_status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.commandKey(), row.actorId(), row.reason(), row.version(),
                row.recallStatus(), row.priorStatus(), row.createdAt());
    }

    /**
     * 将指定召回代次置为已解除：记录不删除，仅更新状态与解除时间。
     */
    public void updateRecallReleased(long id, String releasedAt) {
        jdbc.update("UPDATE recall SET recall_status = 'RELEASED', released_at = ? WHERE id = ?",
                releasedAt, id);
    }

    public Optional<RetestRow> findRetest(String batchKey, String retestKey) {
        return jdbc.query("SELECT * FROM retest WHERE batch_key = ? AND retest_key = ?",
                        RETEST_MAPPER, batchKey, retestKey)
                .stream().findFirst();
    }

    public void insertRetest(RetestRow row) {
        jdbc.update("INSERT INTO retest (batch_key, retest_key, outcome, inspector, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.retestKey(), row.outcome(), row.inspector(), row.createdAt());
    }

    /**
     * 某批次全部复检结果，按提交顺序排列；最新一条决定是否合格。
     */
    public List<RetestRow> findRetests(String batchKey) {
        return jdbc.query("SELECT * FROM retest WHERE batch_key = ? ORDER BY id",
                RETEST_MAPPER, batchKey);
    }

    public void insertRelease(ReleaseRow row) {
        jdbc.update("INSERT INTO recall_release (release_key, batch_key, recall_version,"
                        + " corrective_measures, retest_batches, approver, applicant, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.releaseKey(), row.batchKey(), row.recallVersion(), row.correctiveMeasures(),
                row.retestBatches(), row.approver(), row.applicant(), row.status(), row.createdAt());
    }

    public Optional<ReleaseRow> findRelease(String releaseKey) {
        return jdbc.query("SELECT * FROM recall_release WHERE release_key = ?",
                        RELEASE_MAPPER, releaseKey)
                .stream().findFirst();
    }

    /**
     * 批准解除申请：置为 APPROVED 并记录批准时间。
     */
    public void updateReleaseApproved(String releaseKey, String decidedAt) {
        jdbc.update("UPDATE recall_release SET status = 'APPROVED', decided_at = ?"
                + " WHERE release_key = ?", decidedAt, releaseKey);
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO recall_release_snapshot (release_key, batch_key, recall_version,"
                        + " closure_batches, corrective_measures, approver, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.releaseKey(), row.batchKey(), row.recallVersion(), row.closureBatches(),
                row.correctiveMeasures(), row.approver(), row.createdAt());
    }

    public Optional<SnapshotRow> findSnapshot(String releaseKey) {
        return jdbc.query("SELECT * FROM recall_release_snapshot WHERE release_key = ?",
                        SNAPSHOT_MAPPER, releaseKey)
                .stream().findFirst();
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
}
