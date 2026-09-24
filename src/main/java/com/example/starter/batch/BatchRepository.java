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
     * shelfLifeMinutes 为不可改写的保质分钟；baseExpiresAt 为初始有效期；
     * expiresAt 为当前有效期（随延期顺延）。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String createdAt,
                           int shelfLifeMinutes, String baseExpiresAt, String expiresAt) {
    }

    /**
     * 复检延期申请行记录。status 为 SUBMITTED/CONFIRMED；
     * confirmCommandKey/confirmerId/confirmedAt 在确认生效前为 null，生效后不可改写。
     */
    public record ExtensionRow(long id, String extensionKey, String batchKey,
                               String submitCommandKey, String confirmCommandKey,
                               String recheckConclusion, int extendMinutes,
                               String reviewerId, String confirmerId, int seq,
                               String status, String submittedAt, String confirmedAt) {
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
            rs.getString("status"), rs.getString("created_at"),
            rs.getInt("shelf_life_minutes"), rs.getString("base_expires_at"),
            rs.getString("expires_at"));

    private static final RowMapper<ExtensionRow> EXTENSION_MAPPER = (rs, n) -> new ExtensionRow(
            rs.getLong("id"), rs.getString("extension_key"), rs.getString("batch_key"),
            rs.getString("submit_command_key"), rs.getString("confirm_command_key"),
            rs.getString("recheck_conclusion"), rs.getInt("extend_minutes"),
            rs.getString("reviewer_id"), rs.getString("confirmer_id"), rs.getInt("seq"),
            rs.getString("status"), rs.getString("submitted_at"), rs.getString("confirmed_at"));

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
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at,"
                        + " shelf_life_minutes, base_expires_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.createdAt(), row.shelfLifeMinutes(),
                row.baseExpiresAt(), row.expiresAt());
    }

    /**
     * 顺延有效期：只更新 expires_at，不改写状态、保质分钟与初始有效期。
     */
    public void updateExpiresAt(String batchKey, String expiresAt) {
        jdbc.update("UPDATE batch SET expires_at = ? WHERE batch_key = ?", expiresAt, batchKey);
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

    /**
     * 全部批次，按 id 稳定排序，用于到期清单等只读查询。
     */
    public List<BatchRow> findAllBatches() {
        return jdbc.query("SELECT * FROM batch ORDER BY id", BATCH_MAPPER);
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

    public Optional<ExtensionRow> findExtension(String extensionKey) {
        return jdbc.query("SELECT * FROM batch_extension WHERE extension_key = ?",
                        EXTENSION_MAPPER, extensionKey)
                .stream().findFirst();
    }

    public void insertExtension(ExtensionRow row) {
        jdbc.update("INSERT INTO batch_extension (extension_key, batch_key, submit_command_key,"
                        + " confirm_command_key, recheck_conclusion, extend_minutes, reviewer_id,"
                        + " confirmer_id, seq, status, submitted_at, confirmed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.extensionKey(), row.batchKey(), row.submitCommandKey(), row.confirmCommandKey(),
                row.recheckConclusion(), row.extendMinutes(), row.reviewerId(), row.confirmerId(),
                row.seq(), row.status(), row.submittedAt(), row.confirmedAt());
    }

    /**
     * 确认生效：仅当记录仍为 SUBMITTED 时更新为 CONFIRMED，返回受影响行数（0 表示已被并发确认）。
     */
    public int confirmExtension(String extensionKey, String confirmCommandKey, String confirmerId,
                                String confirmedAt) {
        return jdbc.update("UPDATE batch_extension SET confirm_command_key = ?, confirmer_id = ?,"
                        + " confirmed_at = ?, status = 'CONFIRMED'"
                        + " WHERE extension_key = ? AND status = 'SUBMITTED'",
                confirmCommandKey, confirmerId, confirmedAt, extensionKey);
    }

    /**
     * 某批次全部延期记录，按延期序号（生效顺序）稳定排序。
     */
    public List<ExtensionRow> findExtensions(String batchKey) {
        return jdbc.query("SELECT * FROM batch_extension WHERE batch_key = ? ORDER BY seq",
                EXTENSION_MAPPER, batchKey);
    }

    /**
     * 某批次已确认生效的延期记录，按延期序号稳定排序。
     */
    public List<ExtensionRow> findConfirmedExtensions(String batchKey) {
        return jdbc.query(
                "SELECT * FROM batch_extension WHERE batch_key = ? AND status = 'CONFIRMED' ORDER BY seq",
                EXTENSION_MAPPER, batchKey);
    }
}
