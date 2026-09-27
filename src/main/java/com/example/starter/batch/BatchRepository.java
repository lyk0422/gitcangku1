package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 批次域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取，数量/效价以 DECIMAL 存取。
 */
@Repository
public class BatchRepository {

    /**
     * batch 表行记录；id 同时作为同批次事件的提交顺序依据。
     * quantity/initialPotency/minReleasePotency/dailyDecayRate/potencyAnchorAt 为 null
     * 表示该批未登记数量与效价模型。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String createdAt,
                           BigDecimal quantity, BigDecimal initialPotency,
                           BigDecimal minReleasePotency, BigDecimal dailyDecayRate,
                           String potencyAnchorAt) {
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
     * batch_lineage 表行记录：拆分（一父多子）或合批（多父一子）关系，创建后不可改写。
     * quantity 为父批投入/子批分摊数量；未登记数量的旧路径为 null。
     */
    public record LineageRow(long id, String parentKey, String childKey, int seq,
                             BigDecimal quantity, String createdAt) {
    }

    /**
     * batch_potency_model 表行记录：每批效价模型的一个追加版本。
     */
    public record PotencyModelRow(long id, String batchKey, int version,
                                  BigDecimal initialPotency, String anchorAt,
                                  BigDecimal dailyDecayRate, BigDecimal minReleasePotency,
                                  String commandKey, String createdAt) {
    }

    /**
     * potency_calculation 表行记录：放行/拆分/合批固化计算。
     */
    public record PotencyCalculationRow(long id, String batchKey, String operationType,
                                        String commandKey, int modelVersion, String calculatedAt,
                                        String inputs, BigDecimal resultPotency,
                                        BigDecimal thresholdPotency, boolean eligible,
                                        String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("created_at"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("initial_potency"),
            rs.getBigDecimal("min_release_potency"), rs.getBigDecimal("daily_decay_rate"),
            rs.getString("potency_anchor_at"));

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
            rs.getInt("seq"), rs.getBigDecimal("quantity"), rs.getString("created_at"));

    private static final RowMapper<PotencyModelRow> POTENCY_MODEL_MAPPER = (rs, n) ->
            new PotencyModelRow(rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
                    rs.getBigDecimal("initial_potency"), rs.getString("anchor_at"),
                    rs.getBigDecimal("daily_decay_rate"), rs.getBigDecimal("min_release_potency"),
                    rs.getString("command_key"), rs.getString("created_at"));

    private static final RowMapper<PotencyCalculationRow> POTENCY_CALC_MAPPER = (rs, n) ->
            new PotencyCalculationRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("operation_type"), rs.getString("command_key"),
                    rs.getInt("model_version"), rs.getString("calculated_at"),
                    rs.getString("inputs"), rs.getBigDecimal("result_potency"),
                    rs.getBigDecimal("threshold_potency"), rs.getInt("eligible") == 1,
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
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/模型修订事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 按业务键排序后对多批逐行加锁（合批锁全部父批，确定锁顺序避免死锁）。
     */
    public List<BatchRow> findBatchesForUpdateOrdered(List<String> batchKeys) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key IN (" + placeholders(batchKeys.size())
                        + ") ORDER BY batch_key FOR UPDATE", BATCH_MAPPER, batchKeys.toArray());
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at,"
                        + " quantity, initial_potency, min_release_potency, daily_decay_rate, potency_anchor_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.createdAt(), row.quantity(), row.initialPotency(),
                row.minReleasePotency(), row.dailyDecayRate(), row.potencyAnchorAt());
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
        jdbc.update("INSERT INTO batch_lineage (parent_key, child_key, seq, quantity, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.seq(), row.quantity(), row.createdAt());
    }

    /**
     * 全部血缘边（父→子），用于在内存中推导祖先 DAG 与后代集合；关系不可改写，只增不改。
     */
    public List<LineageRow> findAllLineage() {
        return jdbc.query("SELECT * FROM batch_lineage ORDER BY id", LINEAGE_MAPPER);
    }

    /**
     * 某子批的全部直接父批业务键：拆分恰有一个，合批有多个。
     */
    public List<String> findParentKeys(String childKey) {
        return jdbc.queryForList("SELECT parent_key FROM batch_lineage WHERE child_key = ? ORDER BY seq",
                String.class, childKey);
    }

    /**
     * 全部被直接召回（RECALLED）的批次业务键。
     */
    public List<String> findRecalledKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch WHERE status = 'RECALLED'",
                String.class);
    }

    // ---------- 效价模型 ----------

    /**
     * 批次最新效价模型版本；未登记模型返回空。调用方须已持有 batch 行锁以保证与修订串行。
     */
    public Optional<PotencyModelRow> findLatestPotencyModel(String batchKey) {
        return jdbc.query("SELECT * FROM batch_potency_model WHERE batch_key = ?"
                        + " ORDER BY version DESC LIMIT 1",
                        POTENCY_MODEL_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 批次全部效价模型版本（版本升序），用于诊断查询。
     */
    public List<PotencyModelRow> findPotencyModels(String batchKey) {
        return jdbc.query("SELECT * FROM batch_potency_model WHERE batch_key = ? ORDER BY version",
                POTENCY_MODEL_MAPPER, batchKey);
    }

    public void insertPotencyModel(PotencyModelRow row) {
        jdbc.update("INSERT INTO batch_potency_model (batch_key, version, initial_potency, anchor_at,"
                        + " daily_decay_rate, min_release_potency, command_key, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.initialPotency(), row.anchorAt(),
                row.dailyDecayRate(), row.minReleasePotency(), row.commandKey(), row.createdAt());
    }

    public void insertPotencyCalculation(PotencyCalculationRow row) {
        jdbc.update("INSERT INTO potency_calculation (batch_key, operation_type, command_key,"
                        + " model_version, calculated_at, inputs, result_potency, threshold_potency,"
                        + " eligible, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.operationType(), row.commandKey(), row.modelVersion(),
                row.calculatedAt(), row.inputs(), row.resultPotency(), row.thresholdPotency(),
                row.eligible() ? 1 : 0, row.createdAt());
    }

    /**
     * 批次全部固化计算（id 升序）：放行/拆分/合批历史，只读且不随后续修订改写。
     */
    public List<PotencyCalculationRow> findPotencyCalculations(String batchKey) {
        return jdbc.query("SELECT * FROM potency_calculation WHERE batch_key = ? ORDER BY id",
                POTENCY_CALC_MAPPER, batchKey);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * 供需要 JDBC 原生时间的场景使用（当前时间均走 TimeSource，此方法保留给扩展）。
     */
    static Timestamp toTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
