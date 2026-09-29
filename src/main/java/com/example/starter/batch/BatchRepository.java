package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
                           String producedAt, String status, BigDecimal toleranceLimit,
                           String createdAt) {
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
            rs.getString("status"), rs.getBigDecimal("tolerance_limit"),
            rs.getString("created_at"));

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
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status,"
                        + " tolerance_limit, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(), row.status(),
                row.toleranceLimit(), row.createdAt());
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
     * yield_reconciliation 表行记录：一个不可变核算版本，数量与冻结上限不回写。
     */
    public record ReconciliationRow(long id, String batchKey, int version, String requestId,
                                    BigDecimal inputQuantity, BigDecimal qualifiedQuantity,
                                    BigDecimal lossQuantity, BigDecimal scrapQuantity,
                                    BigDecimal deviation, BigDecimal toleranceLimit,
                                    String status, String submittedAt) {
    }

    /**
     * yield_disposition 表行记录：某超限版本的一名处置人确认。
     */
    public record DispositionRow(long id, String batchKey, int version, String commandKey,
                                 String handlerId, int seq, String createdAt) {
    }

    /**
     * yield_release 表行记录：批次产率门禁放行裁决。
     */
    public record ReleaseRow(long id, String batchKey, int version, String commandKey,
                             String actorId, String releasedAt) {
    }

    private static final RowMapper<ReconciliationRow> RECONCILIATION_MAPPER = (rs, n) ->
            new ReconciliationRow(
                    rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
                    rs.getString("request_id"), rs.getBigDecimal("input_quantity"),
                    rs.getBigDecimal("qualified_quantity"), rs.getBigDecimal("loss_quantity"),
                    rs.getBigDecimal("scrap_quantity"), rs.getBigDecimal("deviation"),
                    rs.getBigDecimal("tolerance_limit"), rs.getString("status"),
                    rs.getString("submitted_at"));

    private static final RowMapper<DispositionRow> DISPOSITION_MAPPER = (rs, n) ->
            new DispositionRow(
                    rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
                    rs.getString("command_key"), rs.getString("handler_id"),
                    rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, n) -> new ReleaseRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
            rs.getString("command_key"), rs.getString("actor_id"), rs.getString("released_at"));

    public Optional<ReconciliationRow> findReconciliation(String batchKey, String requestId) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE batch_key = ? AND request_id = ?",
                        RECONCILIATION_MAPPER, batchKey, requestId)
                .stream().findFirst();
    }

    /**
     * 批次最新核算版本；无核算时返回空。
     */
    public Optional<ReconciliationRow> findLatestReconciliation(String batchKey) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE batch_key = ?"
                        + " ORDER BY version DESC LIMIT 1",
                        RECONCILIATION_MAPPER, batchKey)
                .stream().findFirst();
    }

    public Optional<ReconciliationRow> findReconciliationByVersion(String batchKey, int version) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE batch_key = ? AND version = ?",
                        RECONCILIATION_MAPPER, batchKey, version)
                .stream().findFirst();
    }

    /**
     * 批次全部核算版本，按版本号升序（历史查询）。
     */
    public List<ReconciliationRow> findReconciliations(String batchKey) {
        return jdbc.query("SELECT * FROM yield_reconciliation WHERE batch_key = ? ORDER BY version",
                RECONCILIATION_MAPPER, batchKey);
    }

    /**
     * 批次当前最大版本号；无核算返回 0。
     */
    public int maxYieldVersion(String batchKey) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM yield_reconciliation WHERE batch_key = ?",
                Integer.class, batchKey);
        return max == null ? 0 : max;
    }

    public void insertReconciliation(ReconciliationRow row) {
        jdbc.update("INSERT INTO yield_reconciliation (batch_key, version, request_id,"
                        + " input_quantity, qualified_quantity, loss_quantity, scrap_quantity,"
                        + " deviation, tolerance_limit, status, submitted_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.requestId(), row.inputQuantity(),
                row.qualifiedQuantity(), row.lossQuantity(), row.scrapQuantity(), row.deviation(),
                row.toleranceLimit(), row.status(), row.submittedAt());
    }

    /**
     * 仅处置状态随双人确认流转（PENDING_DISPOSITION→DISPOSITION_CLEARED）；数量与上限不改写。
     */
    public void updateYieldStatus(String batchKey, int version, String status) {
        jdbc.update("UPDATE yield_reconciliation SET status = ? WHERE batch_key = ? AND version = ?",
                status, batchKey, version);
    }

    /**
     * 某版本的全部处置确认，按确认序号升序。
     */
    public List<DispositionRow> findDispositions(String batchKey, int version) {
        return jdbc.query("SELECT * FROM yield_disposition WHERE batch_key = ? AND version = ?"
                        + " ORDER BY seq", DISPOSITION_MAPPER, batchKey, version);
    }

    public Optional<DispositionRow> findDisposition(String batchKey, int version, String handlerId) {
        return jdbc.query("SELECT * FROM yield_disposition WHERE batch_key = ? AND version = ?"
                        + " AND handler_id = ?",
                        DISPOSITION_MAPPER, batchKey, version, handlerId)
                .stream().findFirst();
    }

    public void insertDisposition(DispositionRow row) {
        jdbc.update("INSERT INTO yield_disposition (batch_key, version, command_key,"
                        + " handler_id, seq, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.commandKey(), row.handlerId(),
                row.seq(), row.createdAt());
    }

    public Optional<ReleaseRow> findYieldRelease(String batchKey) {
        return jdbc.query("SELECT * FROM yield_release WHERE batch_key = ?",
                        RELEASE_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertYieldRelease(ReleaseRow row) {
        jdbc.update("INSERT INTO yield_release (batch_key, version, command_key, actor_id, released_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.commandKey(), row.actorId(), row.releasedAt());
    }
}
