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
     * label_plan 表行记录：每批次一份，登记后不可改写；号段左闭右开。
     */
    public record LabelPlanRow(long id, String batchKey, int plannedQuantity,
                               long labelStart, long labelEnd, String createdAt) {
    }

    /**
     * pack_case 表行记录：含已作废记录；voidReason/voidedAt 未作废时为 null。
     */
    public record PackCaseRow(long id, String batchKey, String caseKey, long labelNo,
                              int quantity, String status, int version, String voidReason,
                              String createdAt, String voidedAt) {
    }

    /**
     * release_label_snapshot 表行记录：放行时固化，之后不改写。
     */
    public record SnapshotRow(long id, String batchKey, int plannedQuantity, int sealedQuantity,
                              int caseCount, String labelDigest, String caseVersions,
                              String createdAt) {
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
            rs.getString("actor_id"), rs.getString("reason"), rs.getString("created_at"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_type"), rs.getString("command_key"), rs.getString("fingerprint"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private static final RowMapper<LineageRow> LINEAGE_MAPPER = (rs, n) -> new LineageRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<LabelPlanRow> LABEL_PLAN_MAPPER = (rs, n) -> new LabelPlanRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getLong("label_start"), rs.getLong("label_end"), rs.getString("created_at"));

    private static final RowMapper<PackCaseRow> PACK_CASE_MAPPER = (rs, n) -> new PackCaseRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("case_key"),
            rs.getLong("label_no"), rs.getInt("quantity"), rs.getString("status"),
            rs.getInt("version"), rs.getString("void_reason"),
            rs.getString("created_at"), rs.getString("voided_at"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getInt("sealed_quantity"), rs.getInt("case_count"),
            rs.getString("label_digest"), rs.getString("case_versions"),
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

    public void insertLabelPlan(LabelPlanRow row) {
        jdbc.update("INSERT INTO label_plan (batch_key, planned_quantity, label_start, label_end,"
                        + " created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.labelStart(), row.labelEnd(),
                row.createdAt());
    }

    public Optional<LabelPlanRow> findLabelPlan(String batchKey) {
        return jdbc.query("SELECT * FROM label_plan WHERE batch_key = ?", LABEL_PLAN_MAPPER,
                        batchKey)
                .stream().findFirst();
    }

    public void insertPackCase(PackCaseRow row) {
        jdbc.update("INSERT INTO pack_case (batch_key, case_key, label_no, quantity, status,"
                        + " version, void_reason, created_at, voided_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.caseKey(), row.labelNo(), row.quantity(), row.status(),
                row.version(), row.voidReason(), row.createdAt(), row.voidedAt());
    }

    public Optional<PackCaseRow> findPackCase(String batchKey, String caseKey) {
        return jdbc.query("SELECT * FROM pack_case WHERE batch_key = ? AND case_key = ?",
                        PACK_CASE_MAPPER, batchKey, caseKey)
                .stream().findFirst();
    }

    /**
     * 某批次全部封箱记录（含已作废），按提交顺序；历史记录不因作废删除。
     */
    public List<PackCaseRow> findPackCases(String batchKey) {
        return jdbc.query("SELECT * FROM pack_case WHERE batch_key = ? ORDER BY id",
                PACK_CASE_MAPPER, batchKey);
    }

    /**
     * 某批次当前有效（ACTIVE）封箱记录，按提交顺序。
     */
    public List<PackCaseRow> findActivePackCases(String batchKey) {
        return jdbc.query("SELECT * FROM pack_case WHERE batch_key = ? AND status = 'ACTIVE'"
                        + " ORDER BY id", PACK_CASE_MAPPER, batchKey);
    }

    /**
     * 作废封箱：状态置 VOIDED、版本 +1、写入原因与作废时间；原行保留不改写其他字段。
     */
    public void voidPackCase(String batchKey, String caseKey, int newVersion, String reason,
                             String voidedAt) {
        jdbc.update("UPDATE pack_case SET status = 'VOIDED', version = ?, void_reason = ?,"
                        + " voided_at = ? WHERE batch_key = ? AND case_key = ?",
                newVersion, reason, voidedAt, batchKey, caseKey);
    }

    /**
     * 占用标签号；主键冲突时抛 DuplicateKeyException，保证并发同标签最多一次成功。
     */
    public void insertLabelUsage(long labelNo, String batchKey, String caseKey, String createdAt) {
        jdbc.update("INSERT INTO label_usage (label_no, batch_key, case_key, created_at)"
                + " VALUES (?, ?, ?, ?)", labelNo, batchKey, caseKey, createdAt);
    }

    /**
     * 释放标签占用（作废时调用）。
     */
    public void deleteLabelUsage(long labelNo) {
        jdbc.update("DELETE FROM label_usage WHERE label_no = ?", labelNo);
    }

    /**
     * 某批次当前已占用的标签号，按数值升序（规范排序）。
     */
    public List<Long> findUsedLabels(String batchKey) {
        return jdbc.queryForList("SELECT label_no FROM label_usage WHERE batch_key = ?"
                + " ORDER BY label_no", Long.class, batchKey);
    }

    /**
     * 某批次当前有效封箱数量之和（件）；无有效封箱时为 0。
     */
    public int sumActiveQuantity(String batchKey) {
        Integer sum = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM pack_case"
                + " WHERE batch_key = ? AND status = 'ACTIVE'", Integer.class, batchKey);
        return sum == null ? 0 : sum;
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO release_label_snapshot (batch_key, planned_quantity,"
                        + " sealed_quantity, case_count, label_digest, case_versions, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.sealedQuantity(), row.caseCount(),
                row.labelDigest(), row.caseVersions(), row.createdAt());
    }

    public Optional<SnapshotRow> findSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_label_snapshot WHERE batch_key = ?",
                        SNAPSHOT_MAPPER, batchKey)
                .stream().findFirst();
    }
}
