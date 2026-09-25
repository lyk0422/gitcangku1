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
     * shelfLifeMinutes 为创建后不可改写的保质分钟；validUntil 为当前有效期（随确认延期整体顺延）。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, long shelfLifeMinutes,
                           String validUntil, String createdAt) {
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
     * extension_request 表行记录：待确认/已确认的复检延期请求。
     */
    public record ExtensionRequestRow(long id, String extensionKey, String batchKey,
                                      String commandKey, String inspectorId,
                                      String reinspectionConclusion, int extendMinutes,
                                      String status, String confirmerId, String confirmerRole,
                                      String confirmCommandKey, String createdAt,
                                      String confirmedAt) {
    }

    /**
     * shelf_life_extension 表行记录：确认生效后追加的不可变延期记录。
     */
    public record ExtensionRecordRow(long id, String extensionKey, String batchKey,
                                     int extendMinutes, String inspectorId,
                                     String reinspectionConclusion, String confirmerId,
                                     String confirmerRole, String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getLong("shelf_life_minutes"),
            rs.getString("valid_until"), rs.getString("created_at"));

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

    private static final RowMapper<ExtensionRequestRow> EXTENSION_REQUEST_MAPPER = (rs, n) ->
            new ExtensionRequestRow(
                    rs.getLong("id"), rs.getString("extension_key"), rs.getString("batch_key"),
                    rs.getString("command_key"), rs.getString("inspector_id"),
                    rs.getString("reinspection_conclusion"), rs.getInt("extend_minutes"),
                    rs.getString("status"), rs.getString("confirmer_id"),
                    rs.getString("confirmer_role"), rs.getString("confirm_command_key"),
                    rs.getString("created_at"), rs.getString("confirmed_at"));

    private static final RowMapper<ExtensionRecordRow> EXTENSION_RECORD_MAPPER = (rs, n) ->
            new ExtensionRecordRow(
                    rs.getLong("id"), rs.getString("extension_key"), rs.getString("batch_key"),
                    rs.getInt("extend_minutes"), rs.getString("inspector_id"),
                    rs.getString("reinspection_conclusion"), rs.getString("confirmer_id"),
                    rs.getString("confirmer_role"), rs.getString("created_at"));

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
                        + " shelf_life_minutes, valid_until, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.shelfLifeMinutes(), row.validUntil(), row.createdAt());
    }

    /**
     * 整体顺延有效期（仅复检延期确认事务内调用）；批次状态不改写。
     */
    public void updateValidUntil(String batchKey, String validUntil) {
        jdbc.update("UPDATE batch SET valid_until = ? WHERE batch_key = ?", validUntil, batchKey);
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
     * 全部批次（含已召回/已拆分），按 id 稳定升序；到期清单据此在内存按有效期判定。
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

    /**
     * 按业务键查询复检延期请求（任意状态）。
     */
    public Optional<ExtensionRequestRow> findExtensionRequest(String extensionKey) {
        return jdbc.query("SELECT * FROM extension_request WHERE extension_key = ?",
                        EXTENSION_REQUEST_MAPPER, extensionKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取复检延期请求，串行化确认与重复提交。
     */
    public Optional<ExtensionRequestRow> findExtensionRequestForUpdate(String extensionKey) {
        return jdbc.query("SELECT * FROM extension_request WHERE extension_key = ? FOR UPDATE",
                        EXTENSION_REQUEST_MAPPER, extensionKey)
                .stream().findFirst();
    }

    public void insertExtensionRequest(ExtensionRequestRow row) {
        jdbc.update("INSERT INTO extension_request (extension_key, batch_key, command_key,"
                        + " inspector_id, reinspection_conclusion, extend_minutes, status,"
                        + " confirmer_id, confirmer_role, confirm_command_key, created_at,"
                        + " confirmed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.extensionKey(), row.batchKey(), row.commandKey(), row.inspectorId(),
                row.reinspectionConclusion(), row.extendMinutes(), row.status(),
                row.confirmerId(), row.confirmerRole(), row.confirmCommandKey(),
                row.createdAt(), row.confirmedAt());
    }

    /**
     * 确认生效：更新请求为 CONFIRMED 并落定确认人/角色/命令/时间。
     */
    public void confirmExtensionRequest(String extensionKey, String confirmerId,
                                        String confirmerRole, String confirmCommandKey,
                                        String confirmedAt) {
        jdbc.update("UPDATE extension_request SET status = 'CONFIRMED', confirmer_id = ?,"
                        + " confirmer_role = ?, confirm_command_key = ?, confirmed_at = ?"
                        + " WHERE extension_key = ?",
                confirmerId, confirmerRole, confirmCommandKey, confirmedAt, extensionKey);
    }

    /**
     * 追加不可变的已生效延期记录。
     */
    public void insertExtensionRecord(ExtensionRecordRow row) {
        jdbc.update("INSERT INTO shelf_life_extension (extension_key, batch_key, extend_minutes,"
                        + " inspector_id, reinspection_conclusion, confirmer_id, confirmer_role,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.extensionKey(), row.batchKey(), row.extendMinutes(), row.inspectorId(),
                row.reinspectionConclusion(), row.confirmerId(), row.confirmerRole(),
                row.createdAt());
    }

    /**
     * 某批次全部已生效延期记录，按生效顺序（id）排列。
     */
    public List<ExtensionRecordRow> findExtensionRecords(String batchKey) {
        return jdbc.query("SELECT * FROM shelf_life_extension WHERE batch_key = ? ORDER BY id",
                EXTENSION_RECORD_MAPPER, batchKey);
    }
}
