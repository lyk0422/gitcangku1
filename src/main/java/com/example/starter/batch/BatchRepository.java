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
     * batch_lineage 表行记录：拆分/合批父子关系，创建后不可改写。
     */
    public record LineageRow(long id, String parentKey, String childKey, int seq, String createdAt) {
    }

    /**
     * batch_potency_model 表行记录：效价衰减模型某一版本；历史版本保留不改写。
     */
    public record PotencyModelRow(long id, String batchKey, int version,
                                  java.math.BigDecimal initialPotency,
                                  java.math.BigDecimal minReleasePotency,
                                  java.math.BigDecimal decayRatePerDay,
                                  String registeredAt, String createdAt) {
    }

    /**
     * potency_evaluation 表行记录：放行/拆分/合批时固化的效价计算结果，不可改写。
     */
    public record PotencyEvalRow(long id, String batchKey, String operation, String relatedKey,
                                 String evaluatedAt, int modelVersion,
                                 java.math.BigDecimal initialPotency,
                                 java.math.BigDecimal decayRatePerDay,
                                 java.math.BigDecimal minReleasePotency,
                                 long elapsedDays, java.math.BigDecimal currentPotency,
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

    private static final RowMapper<PotencyModelRow> POTENCY_MODEL_MAPPER = (rs, n) ->
            new PotencyModelRow(rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
                    rs.getBigDecimal("initial_potency"), rs.getBigDecimal("min_release_potency"),
                    rs.getBigDecimal("decay_rate_per_day"), rs.getString("registered_at"),
                    rs.getString("created_at"));

    private static final RowMapper<PotencyEvalRow> POTENCY_EVAL_MAPPER = (rs, n) ->
            new PotencyEvalRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("operation"), rs.getString("related_key"),
                    rs.getString("evaluated_at"), rs.getInt("model_version"),
                    rs.getBigDecimal("initial_potency"), rs.getBigDecimal("decay_rate_per_day"),
                    rs.getBigDecimal("min_release_potency"), rs.getLong("elapsed_days"),
                    rs.getBigDecimal("current_potency"), rs.getString("created_at"));

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
     * 某批次的直接父批业务键，按血缘创建顺序；拆分子批至多一个，合批子批可有多个。
     */
    public List<String> findParentKeys(String childKey) {
        return jdbc.queryForList(
                "SELECT parent_key FROM batch_lineage WHERE child_key = ? ORDER BY id",
                String.class, childKey);
    }

    /**
     * 全部被直接召回（RECALLED）的批次业务键。
     */
    public List<String> findRecalledKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch WHERE status = 'RECALLED'",
                String.class);
    }

    /**
     * 追加效价模型新版本；版本号由调用方按 当前最大版本+1 计算（同事务行锁保证）。
     */
    public void insertPotencyModel(PotencyModelRow row) {
        jdbc.update("INSERT INTO batch_potency_model (batch_key, version, initial_potency,"
                        + " min_release_potency, decay_rate_per_day, registered_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.initialPotency(), row.minReleasePotency(),
                row.decayRatePerDay(), row.registeredAt(), row.createdAt());
    }

    /**
     * 批次当前最新版本效价模型；未登记时为空。
     */
    public Optional<PotencyModelRow> findLatestPotencyModel(String batchKey) {
        return jdbc.query("SELECT * FROM batch_potency_model WHERE batch_key = ?"
                        + " ORDER BY version DESC LIMIT 1", POTENCY_MODEL_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 固化一条效价计算记录（放行/拆分/合批）。
     */
    public void insertPotencyEvaluation(PotencyEvalRow row) {
        jdbc.update("INSERT INTO potency_evaluation (batch_key, operation, related_key,"
                        + " evaluated_at, model_version, initial_potency, decay_rate_per_day,"
                        + " min_release_potency, elapsed_days, current_potency, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.operation(), row.relatedKey(), row.evaluatedAt(),
                row.modelVersion(), row.initialPotency(), row.decayRatePerDay(),
                row.minReleasePotency(), row.elapsedDays(), row.currentPotency(), row.createdAt());
    }

    /**
     * 批次相关的全部固化效价计算记录：自身被评估的记录 + 以其为关联子批的记录，
     * 按写入顺序（id）稳定排序。
     */
    public List<PotencyEvalRow> findPotencyEvaluations(String batchKey) {
        return jdbc.query("SELECT * FROM potency_evaluation WHERE batch_key = ? OR related_key = ?"
                        + " ORDER BY id", POTENCY_EVAL_MAPPER, batchKey, batchKey);
    }
}
