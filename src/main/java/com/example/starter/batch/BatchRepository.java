package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 批次域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 * 过敏原与父批快照逐行结构化存储，比较与固化均基于行集合，不依赖字符串拼接。
 */
@Repository
public class BatchRepository {

    /**
     * batch 表行记录；id 同时作为同批次事件的提交顺序依据。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, int cleaningGeneration, String createdAt) {
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
     * batch_lineage 表行记录：拆分/合批父子关系，创建后不可改写。
     * relation 为 SPLIT（拆分，一个父批多个子批）或 MERGE（合批，一个子批多个父批）。
     */
    public record LineageRow(long id, String parentKey, String childKey, String relation,
                             int seq, String createdAt) {
    }

    /**
     * batch_merge 表行记录：成功合批单头。
     */
    public record MergeRow(String requestId, String childKey, String childBatchNo,
                           String productCode, int cleaningGeneration, String createdAt) {
    }

    /**
     * batch_merge_parent 表行记录：成功合批父批快照头。
     */
    public record MergeParentRow(long id, String requestId, String parentKey, String parentBatchNo,
                                 String productCode, int cleaningGeneration, String status,
                                 int seq, String createdAt) {
    }

    /**
     * batch_merge_parent_allergen 表行记录：成功合批父批过敏原快照明细。
     */
    public record MergeParentAllergenRow(long id, String requestId, String parentKey,
                                         String allergen, int seq) {
    }

    /**
     * batch_merge_rejection 表行记录：合批 422 拒绝原因。
     */
    public record MergeRejectionRow(long id, String requestId, String reasonCode,
                                    String reasonDetail, String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getInt("cleaning_generation"), rs.getString("created_at"));

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
            rs.getString("relation"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<MergeRow> MERGE_MAPPER = (rs, n) -> new MergeRow(
            rs.getString("request_id"), rs.getString("child_key"), rs.getString("child_batch_no"),
            rs.getString("product_code"), rs.getInt("cleaning_generation"),
            rs.getString("created_at"));

    private static final RowMapper<MergeParentRow> MERGE_PARENT_MAPPER = (rs, n) -> new MergeParentRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("parent_key"),
            rs.getString("parent_batch_no"), rs.getString("product_code"),
            rs.getInt("cleaning_generation"), rs.getString("status"),
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<MergeParentAllergenRow> MERGE_PARENT_ALLERGEN_MAPPER =
            (rs, n) -> new MergeParentAllergenRow(rs.getLong("id"), rs.getString("request_id"),
                    rs.getString("parent_key"), rs.getString("allergen"), rs.getInt("seq"));

    private static final RowMapper<MergeRejectionRow> MERGE_REJECTION_MAPPER =
            (rs, n) -> new MergeRejectionRow(rs.getLong("id"), rs.getString("request_id"),
                    rs.getString("reason_code"), rs.getString("reason_detail"),
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
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/清洁/合批事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status,"
                        + " cleaning_generation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.cleaningGeneration(), row.createdAt());
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

    /**
     * 推进清洁代次（仅向前），与合批在同一行锁上串行，杜绝读到混合版本。
     */
    public void updateCleaningGeneration(String batchKey, int generation) {
        jdbc.update("UPDATE batch SET cleaning_generation = ? WHERE batch_key = ?",
                generation, batchKey);
    }

    public List<BatchRow> findAvailableBatches() {
        return jdbc.query("SELECT * FROM batch WHERE status <> 'RECALLED' ORDER BY id", BATCH_MAPPER);
    }

    // ---------- 过敏原 ----------

    public void insertAllergen(String batchKey, String allergen, int seq) {
        jdbc.update("INSERT INTO batch_allergen (batch_key, allergen, seq) VALUES (?, ?, ?)",
                batchKey, allergen, seq);
    }

    /**
     * 读取规范化过敏原集合（字典序）；空结果表示该批次不含登记过敏原。
     */
    public List<String> findAllergens(String batchKey) {
        return jdbc.queryForList(
                "SELECT allergen FROM batch_allergen WHERE batch_key = ? ORDER BY seq, allergen",
                String.class, batchKey);
    }

    public void deleteAllergens(String batchKey) {
        jdbc.update("DELETE FROM batch_allergen WHERE batch_key = ?", batchKey);
    }

    // ---------- 检验/批准/召回 ----------

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

    // ---------- 幂等命令 ----------

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

    // ---------- 血缘 ----------

    public void insertLineage(LineageRow row) {
        jdbc.update("INSERT INTO batch_lineage (parent_key, child_key, relation, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.relation(), row.seq(), row.createdAt());
    }

    /**
     * 全部血缘边（父→子），用于在内存中推导祖先链与后代集合；关系不可改写，只增不改。
     */
    public List<LineageRow> findAllLineage() {
        return jdbc.query("SELECT * FROM batch_lineage ORDER BY id", LINEAGE_MAPPER);
    }

    /**
     * 某批次的直接父批业务键；拆分子批一个父批，合批子批可能有多个父批。
     */
    public List<String> findParentKeys(String childKey) {
        return jdbc.queryForList(
                "SELECT parent_key FROM batch_lineage WHERE child_key = ? ORDER BY seq, id",
                String.class, childKey);
    }

    /**
     * 全部被直接召回（RECALLED）的批次业务键。
     */
    public List<String> findRecalledKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch WHERE status = 'RECALLED'",
                String.class);
    }

    // ---------- 合批 ----------

    public void insertMerge(MergeRow row) {
        jdbc.update("INSERT INTO batch_merge (request_id, child_key, child_batch_no, product_code,"
                        + " cleaning_generation, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.requestId(), row.childKey(), row.childBatchNo(), row.productCode(),
                row.cleaningGeneration(), row.createdAt());
    }

    public Optional<MergeRow> findMerge(String requestId) {
        return jdbc.query("SELECT * FROM batch_merge WHERE request_id = ?",
                        MERGE_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按子批业务键查合批单头（历史展示冻结依据用）。
     */
    public Optional<MergeRow> findMergeByChild(String childKey) {
        return jdbc.query("SELECT * FROM batch_merge WHERE child_key = ?",
                        MERGE_MAPPER, childKey)
                .stream().findFirst();
    }

    public void insertMergeParent(MergeParentRow row) {
        jdbc.update("INSERT INTO batch_merge_parent (request_id, parent_key, parent_batch_no,"
                        + " product_code, cleaning_generation, status, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.parentKey(), row.parentBatchNo(), row.productCode(),
                row.cleaningGeneration(), row.status(), row.seq(), row.createdAt());
    }

    public List<MergeParentRow> findMergeParents(String requestId) {
        return jdbc.query("SELECT * FROM batch_merge_parent WHERE request_id = ? ORDER BY seq",
                MERGE_PARENT_MAPPER, requestId);
    }

    public void insertMergeParentAllergen(MergeParentAllergenRow row) {
        jdbc.update("INSERT INTO batch_merge_parent_allergen (request_id, parent_key, allergen, seq)"
                        + " VALUES (?, ?, ?, ?)",
                row.requestId(), row.parentKey(), row.allergen(), row.seq());
    }

    public List<MergeParentAllergenRow> findMergeParentAllergens(String requestId) {
        return jdbc.query(
                "SELECT * FROM batch_merge_parent_allergen WHERE request_id = ? ORDER BY parent_key, seq",
                MERGE_PARENT_ALLERGEN_MAPPER, requestId);
    }

    /**
     * 追加一条合批拒绝原因；拒绝不占用 requestId，不去重、不更新既有行。
     */
    public void insertMergeRejection(MergeRejectionRow row) {
        jdbc.update("INSERT INTO batch_merge_rejection (request_id, reason_code, reason_detail, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.requestId(), row.reasonCode(), row.reasonDetail(), row.createdAt());
    }

    public List<MergeRejectionRow> findMergeRejections(String requestId) {
        return jdbc.query(
                "SELECT * FROM batch_merge_rejection WHERE request_id = ? ORDER BY id",
                MERGE_REJECTION_MAPPER, requestId);
    }
}
