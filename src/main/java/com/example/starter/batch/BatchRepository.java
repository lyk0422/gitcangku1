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
     * version 为乐观锁版本，处置二审落账时按 expected version CAS 递增。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, long version, String createdAt) {
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

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getLong("version"), rs.getString("created_at"));

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
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.version(), row.createdAt());
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

    /**
     * 状态流转：每次流转同步将版本 +1，供召回处置二审比对“提交后是否发生过状态变化”。
     */
    public void updateStatus(String batchKey, String status) {
        jdbc.update("UPDATE batch SET status = ?, version = version + 1 WHERE batch_key = ?",
                status, batchKey);
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
     * 处置二审落账的条件更新：仅当当前版本等于 expectedVersion 时，
     * 把状态改为 newStatus 并将版本 +1（HOLD 时状态不变但仍 +1）。
     * 返回受影响行数：0 表示期间版本已变化（拆分/召回/并发处置），调用方必须整体回滚。
     */
    public int applyDispositionStatus(String batchKey, long expectedVersion, String newStatus) {
        return jdbc.update("UPDATE batch SET status = ?, version = version + 1"
                        + " WHERE batch_key = ? AND version = ?",
                newStatus, batchKey, expectedVersion);
    }

    /**
     * disposition 表行记录：召回处置单主表。
     */
    public record DispositionRow(long id, String dispositionKey, String ancestorKey, String status,
                                 String submittedBy, int dispositionVersion, String confirmedBy,
                                 String rejectedBy, String rejectReason, String holdReason,
                                 String submittedAt, String confirmedAt,
                                 String rejectedAt, String cancelledAt) {
    }

    /**
     * disposition_batch 表行记录：冻结的批次版本/状态/路径与分类快照，创建后不可变。
     * path 为 JSON 字符串（祖先→…→该批次业务键有序数组）。
     */
    public record DispositionBatchRow(long id, String dispositionKey, String batchKey, String category,
                                      String frozenStatus, long frozenVersion, String path,
                                      int depth, int seq, String createdAt) {
    }

    /**
     * disposition_command_log 表行记录：处置命令幂等快照。
     */
    public record DispositionCommandRow(String commandType, String commandKey, String fingerprint,
                                        int responseStatus, String responseBody) {
    }

    private static final RowMapper<DispositionRow> DISPOSITION_MAPPER = (rs, n) -> new DispositionRow(
            rs.getLong("id"), rs.getString("disposition_key"), rs.getString("ancestor_key"),
            rs.getString("status"), rs.getString("submitted_by"), rs.getInt("disposition_version"),
            rs.getString("confirmed_by"), rs.getString("rejected_by"), rs.getString("reject_reason"),
            rs.getString("hold_reason"), rs.getString("submitted_at"), rs.getString("confirmed_at"),
            rs.getString("rejected_at"), rs.getString("cancelled_at"));

    private static final RowMapper<DispositionBatchRow> DISPOSITION_BATCH_MAPPER = (rs, n) ->
            new DispositionBatchRow(rs.getLong("id"), rs.getString("disposition_key"),
                    rs.getString("batch_key"), rs.getString("category"), rs.getString("frozen_status"),
                    rs.getLong("frozen_version"), rs.getString("path"), rs.getInt("depth"),
                    rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<DispositionCommandRow> DISPOSITION_COMMAND_MAPPER = (rs, n) ->
            new DispositionCommandRow(rs.getString("command_type"), rs.getString("command_key"),
                    rs.getString("fingerprint"), rs.getInt("response_status"),
                    rs.getString("response_body"));

    public void insertDisposition(DispositionRow row) {
        jdbc.update("INSERT INTO disposition (disposition_key, ancestor_key, status, submitted_by,"
                        + " disposition_version, confirmed_by, rejected_by, reject_reason, hold_reason,"
                        + " submitted_at, confirmed_at, rejected_at, cancelled_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.dispositionKey(), row.ancestorKey(), row.status(), row.submittedBy(),
                row.dispositionVersion(), row.confirmedBy(), row.rejectedBy(), row.rejectReason(),
                row.holdReason(), row.submittedAt(), row.confirmedAt(), row.rejectedAt(),
                row.cancelledAt());
    }

    public Optional<DispositionRow> findDisposition(String dispositionKey) {
        return jdbc.query("SELECT * FROM disposition WHERE disposition_key = ?",
                        DISPOSITION_MAPPER, dispositionKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取处置单，串行化同一处置单的确认/拒绝/取消及并发重放。
     */
    public Optional<DispositionRow> findDispositionForUpdate(String dispositionKey) {
        return jdbc.query("SELECT * FROM disposition WHERE disposition_key = ? FOR UPDATE",
                        DISPOSITION_MAPPER, dispositionKey)
                .stream().findFirst();
    }

    /**
     * 按祖先键行锁读取该祖先的任一处置单（含全部状态），用于互斥同一祖先上的并发提交。
     */
    public Optional<DispositionRow> findDispositionByAncestorForUpdate(String ancestorKey) {
        return jdbc.query("SELECT * FROM disposition WHERE ancestor_key = ? ORDER BY id FOR UPDATE",
                        DISPOSITION_MAPPER, ancestorKey)
                .stream().findFirst();
    }

    public void updateDispositionConfirmed(String dispositionKey, String confirmedBy, String confirmedAt) {
        jdbc.update("UPDATE disposition SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ?"
                        + " WHERE disposition_key = ?",
                confirmedBy, confirmedAt, dispositionKey);
    }

    public void updateDispositionRejected(String dispositionKey, String rejectedBy, String reason,
                                          String rejectedAt) {
        jdbc.update("UPDATE disposition SET status = 'REJECTED', rejected_by = ?, reject_reason = ?,"
                        + " rejected_at = ? WHERE disposition_key = ?",
                rejectedBy, reason, rejectedAt, dispositionKey);
    }

    public void updateDispositionCancelled(String dispositionKey, String cancelledAt) {
        jdbc.update("UPDATE disposition SET status = 'CANCELLED', cancelled_at = ?"
                        + " WHERE disposition_key = ?",
                cancelledAt, dispositionKey);
    }

    public void insertDispositionBatch(DispositionBatchRow row) {
        jdbc.update("INSERT INTO disposition_batch (disposition_key, batch_key, category, frozen_status,"
                        + " frozen_version, path, depth, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.dispositionKey(), row.batchKey(), row.category(), row.frozenStatus(),
                row.frozenVersion(), row.path(), row.depth(), row.seq(), row.createdAt());
    }

    public List<DispositionBatchRow> findDispositionBatches(String dispositionKey) {
        return jdbc.query("SELECT * FROM disposition_batch WHERE disposition_key = ? ORDER BY seq",
                DISPOSITION_BATCH_MAPPER, dispositionKey);
    }

    /**
     * HOLD 落账：批次状态保持不变，仅把版本 +1，同样走 expected version 条件更新。
     */
    public int bumpVersion(String batchKey, long expectedVersion) {
        return jdbc.update("UPDATE batch SET version = version + 1"
                        + " WHERE batch_key = ? AND version = ?",
                batchKey, expectedVersion);
    }

    /**
     * 某祖先下已存在的处置单数量，用于生成单调的 dispositionVersion。
     */
    public int countDispositionsByAncestor(String ancestorKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disposition WHERE ancestor_key = ?",
                Integer.class, ancestorKey);
        return count == null ? 0 : count;
    }

    public Optional<DispositionCommandRow> findDispositionCommand(String commandType, String commandKey) {
        return jdbc.query("SELECT * FROM disposition_command_log WHERE command_type = ? AND command_key = ?",
                        DISPOSITION_COMMAND_MAPPER, commandType, commandKey)
                .stream().findFirst();
    }

    public void insertDispositionCommand(DispositionCommandRow row, String createdAt) {
        jdbc.update("INSERT INTO disposition_command_log (command_type, command_key, fingerprint,"
                        + " response_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.commandType(), row.commandKey(), row.fingerprint(),
                row.responseStatus(), row.responseBody(), createdAt);
    }
}
