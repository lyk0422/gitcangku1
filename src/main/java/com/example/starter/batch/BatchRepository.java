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
                           String producedAt, String status, BigDecimal quantity, String createdAt) {
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
     * batch_merge_parent 表行记录：合批父贡献，创建后不可改写。
     */
    public record MergeParentRow(long id, String childKey, String parentKey, int seq,
                                 BigDecimal quantity, BigDecimal weight, BigDecimal potency,
                                 String createdAt) {
    }

    /**
     * potency_model 表行记录：版本化效价模型，只增不改。
     */
    public record PotencyModelRow(long id, String batchKey, int version, BigDecimal initialPotency,
                                  BigDecimal minPotency, BigDecimal decayRate, String actorId,
                                  String commandKey, String effectiveAt, String createdAt) {
    }

    /**
     * potency_evidence 表行记录：固化的效价业务证据，只增不改；id 为 null 时表示待插入。
     */
    public record EvidenceRow(Long id, String batchKey, String eventType, String commandType,
                              String commandKey, String occurredAt, Integer modelVersion,
                              BigDecimal initialPotency, BigDecimal minPotency, BigDecimal decayRate,
                              BigDecimal elapsedDays, BigDecimal decayedPotency, BigDecimal currentPotency,
                              BigDecimal thresholdPotency, BigDecimal difference, String decision,
                              String inputsJson, String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getBigDecimal("quantity"), rs.getString("created_at"));

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

    private static final RowMapper<MergeParentRow> MERGE_PARENT_MAPPER = (rs, n) -> new MergeParentRow(
            rs.getLong("id"), rs.getString("child_key"), rs.getString("parent_key"),
            rs.getInt("seq"), rs.getBigDecimal("quantity"), rs.getBigDecimal("weight"),
            rs.getBigDecimal("potency"), rs.getString("created_at"));

    private static final RowMapper<PotencyModelRow> MODEL_MAPPER = (rs, n) -> new PotencyModelRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
            rs.getBigDecimal("initial_potency"), rs.getBigDecimal("min_potency"),
            rs.getBigDecimal("decay_rate"), rs.getString("actor_id"), rs.getString("command_key"),
            rs.getString("effective_at"), rs.getString("created_at"));

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, n) -> new EvidenceRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("event_type"),
            rs.getString("command_type"), rs.getString("command_key"), rs.getString("occurred_at"),
            (Integer) rs.getObject("model_version"),
            rs.getBigDecimal("initial_potency"), rs.getBigDecimal("min_potency"),
            rs.getBigDecimal("decay_rate"), rs.getBigDecimal("elapsed_days"),
            rs.getBigDecimal("decayed_potency"), rs.getBigDecimal("current_potency"),
            rs.getBigDecimal("threshold_potency"), rs.getBigDecimal("difference"),
            rs.getString("decision"), rs.getString("inputs_json"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRow> findBatch(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/模型修订事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, quantity, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.quantity(), row.createdAt());
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
     * 全部拆分层血缘边（父→子），用于在内存中推导祖先链与后代集合；关系不可改写，只增不改。
     */
    public List<LineageRow> findAllLineage() {
        return jdbc.query("SELECT * FROM batch_lineage ORDER BY id", LINEAGE_MAPPER);
    }

    /**
     * 全部合批层血缘边，按固化顺序返回；与拆分层共同构成完整祖先/后代图。
     */
    public List<MergeParentRow> findAllMergeEdges() {
        return jdbc.query("SELECT * FROM batch_merge_parent ORDER BY id", MERGE_PARENT_MAPPER);
    }

    /**
     * 某合批子批的全部父贡献，按 seq（规范化 batchKey 顺序）稳定排序。
     */
    public List<MergeParentRow> findMergeParents(String childKey) {
        return jdbc.query("SELECT * FROM batch_merge_parent WHERE child_key = ? ORDER BY seq",
                MERGE_PARENT_MAPPER, childKey);
    }

    public void insertMergeParent(MergeParentRow row) {
        jdbc.update("INSERT INTO batch_merge_parent (child_key, parent_key, seq, quantity, weight,"
                        + " potency, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.childKey(), row.parentKey(), row.seq(), row.quantity(), row.weight(),
                row.potency(), row.createdAt());
    }

    /**
     * 某批次的最新效价模型版本；版本只增不改，调用方须已持有批次行锁以保证与修订命令按提交顺序互斥。
     */
    public Optional<PotencyModelRow> findLatestModel(String batchKey) {
        return jdbc.query("SELECT * FROM potency_model WHERE batch_key = ? ORDER BY version DESC LIMIT 1",
                        MODEL_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertModel(PotencyModelRow row) {
        jdbc.update("INSERT INTO potency_model (batch_key, version, initial_potency, min_potency,"
                        + " decay_rate, actor_id, command_key, effective_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.initialPotency(), row.minPotency(),
                row.decayRate(), row.actorId(), row.commandKey(), row.effectiveAt(), row.createdAt());
    }

    public void insertEvidence(EvidenceRow row) {
        jdbc.update("INSERT INTO potency_evidence (batch_key, event_type, command_type, command_key,"
                        + " occurred_at, model_version, initial_potency, min_potency, decay_rate,"
                        + " elapsed_days, decayed_potency, current_potency, threshold_potency,"
                        + " difference, decision, inputs_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.eventType(), row.commandType(), row.commandKey(),
                row.occurredAt(), row.modelVersion(), row.initialPotency(), row.minPotency(),
                row.decayRate(), row.elapsedDays(), row.decayedPotency(), row.currentPotency(),
                row.thresholdPotency(), row.difference(), row.decision(), row.inputsJson(),
                row.createdAt());
    }

    /**
     * 某批次全部效价证据，按固化 id 稳定排序，只增不改。
     */
    public List<EvidenceRow> findEvidence(String batchKey) {
        return jdbc.query("SELECT * FROM potency_evidence WHERE batch_key = ? ORDER BY id",
                EVIDENCE_MAPPER, batchKey);
    }

    /**
     * 全部被直接召回（RECALLED）的批次业务键。
     */
    public List<String> findRecalledKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch WHERE status = 'RECALLED'",
                String.class);
    }
}
