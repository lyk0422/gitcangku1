package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 批次域持久化层：所有 SQL 参数化；时间与效价数值分别以 ISO-8601 UTC 字符串、
 * BigDecimal 纯数字符串存取，避免精度在存储层丢失。
 */
@Repository
public class BatchRepository {

    /**
     * batch 表行记录；id 同时作为同批次事件的提交顺序依据。
     * quantity/initialPotency/minReleasePotency/decayRatePerDay/potencyEpochAt 未登记效价时为 null。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String createdAt,
                           String quantity, String initialPotency, String minReleasePotency,
                           String decayRatePerDay, String potencyEpochAt) {
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
     * batch_merge_lineage 表行记录：合批父子关系与并入权重，创建后不可改写。
     */
    public record MergeLineageRow(long id, String parentKey, String childKey, String weight,
                                  int seq, String createdAt) {
    }

    /**
     * potency_model 表行记录：效价模型版本，只增不改。
     */
    public record ModelRow(long id, int version, int scale, String roundingMode, String commandKey,
                           String createdAt) {
    }

    /**
     * potency_evidence 表行记录：效价计算证据，只增不改。
     */
    public record EvidenceRow(long id, String batchKey, String operation, String computedAt,
                              int modelVersion, String inputs, String rawPotency,
                              String roundedPotency, String threshold, String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("created_at"),
            rs.getString("quantity"), rs.getString("initial_potency"),
            rs.getString("min_release_potency"), rs.getString("decay_rate_per_day"),
            rs.getString("potency_epoch_at"));

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

    private static final RowMapper<MergeLineageRow> MERGE_LINEAGE_MAPPER = (rs, n) -> new MergeLineageRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getString("weight"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<ModelRow> MODEL_MAPPER = (rs, n) -> new ModelRow(
            rs.getLong("id"), rs.getInt("version"), rs.getInt("scale"),
            rs.getString("rounding_mode"), rs.getString("command_key"),
            rs.getString("created_at"));

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, n) -> new EvidenceRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("operation"),
            rs.getString("computed_at"), rs.getInt("model_version"), rs.getString("inputs"),
            rs.getString("raw_potency"), rs.getString("rounded_potency"),
            rs.getString("threshold"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRow> findBatch(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/拆分/合批事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status,"
                        + " created_at, quantity, initial_potency, min_release_potency,"
                        + " decay_rate_per_day, potency_epoch_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.createdAt(), row.quantity(), row.initialPotency(),
                row.minReleasePotency(), row.decayRatePerDay(), row.potencyEpochAt());
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
     * 全部拆分血缘边（父→子），用于在内存中推导祖先链与后代集合；关系不可改写，只增不改。
     */
    public List<LineageRow> findAllLineage() {
        return jdbc.query("SELECT * FROM batch_lineage ORDER BY id", LINEAGE_MAPPER);
    }

    /**
     * 全部合批血缘边（父→子），与拆分血缘共同构成追溯图；关系不可改写，只增不改。
     */
    public List<MergeLineageRow> findAllMergeLineage() {
        return jdbc.query("SELECT * FROM batch_merge_lineage ORDER BY id", MERGE_LINEAGE_MAPPER);
    }

    public void insertMergeLineage(MergeLineageRow row) {
        jdbc.update("INSERT INTO batch_merge_lineage (parent_key, child_key, weight, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.weight(), row.seq(), row.createdAt());
    }

    /**
     * 某批次的直接父批业务键；拆分场景每个子批仅一个父批。
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
     * 当前最新效价模型版本。
     */
    public Optional<ModelRow> findLatestModel() {
        return jdbc.query("SELECT * FROM potency_model ORDER BY version DESC LIMIT 1",
                        MODEL_MAPPER)
                .stream().findFirst();
    }

    /**
     * 全部效价模型版本，按版本升序，排序稳定。
     */
    public List<ModelRow> findModels() {
        return jdbc.query("SELECT * FROM potency_model ORDER BY version", MODEL_MAPPER);
    }

    public void insertModel(ModelRow row) {
        jdbc.update("INSERT INTO potency_model (version, scale, rounding_mode, command_key, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.version(), row.scale(), row.roundingMode(), row.commandKey(),
                row.createdAt());
    }

    /**
     * 获取效价模型单行互斥锁：序列化模型修订与放行/拆分/合批的效价计算，按提交顺序裁决。
     */
    public void lockPotencyModel() {
        jdbc.queryForList("SELECT id FROM potency_model_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public void insertEvidence(EvidenceRow row) {
        jdbc.update("INSERT INTO potency_evidence (batch_key, operation, computed_at, model_version,"
                        + " inputs, raw_potency, rounded_potency, threshold, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.operation(), row.computedAt(), row.modelVersion(),
                row.inputs(), row.rawPotency(), row.roundedPotency(), row.threshold(),
                row.createdAt());
    }

    /**
     * 某批次的全部效价证据，按写入顺序（id）升序，排序稳定；证据只增不改。
     */
    public List<EvidenceRow> findEvidence(String batchKey) {
        return jdbc.query("SELECT * FROM potency_evidence WHERE batch_key = ? ORDER BY id",
                EVIDENCE_MAPPER, batchKey);
    }
}
