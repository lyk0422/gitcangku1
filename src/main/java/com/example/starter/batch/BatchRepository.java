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
     * minStorageTempC/maxStorageTempC 为储运温度规格（摄氏度，含边界），允许为 null（沿用默认规格）。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String createdAt, long version,
                           Double minStorageTempC, Double maxStorageTempC) {
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
     * batch_lineage 表行记录：拆分或返工父子关系，创建后不可改写。
     * relationType 为 SPLIT（拆分血缘）或 REWORK（MAJOR 偏差返工链）。
     */
    public record LineageRow(long id, String parentKey, String childKey, int seq,
                             String relationType, String createdAt) {
    }

    /**
     * storage_excursion 表行记录：温度偏差登记，登记后区间/温度/级别不可改写。
     */
    public record ExcursionRow(long id, String batchKey, String excursionKey, String startAt,
                               String endAt, double measuredMinTempC, double measuredMaxTempC,
                               String severity, String status, long batchVersion, String registeredAt) {
    }

    /**
     * excursion_adjudication 表行记录：裁决不可变快照。
     */
    public record AdjudicationRow(long id, String batchKey, String excursionKey, String disposition,
                                  String actorId, String reason, String reworkBatchKey,
                                  String adjudicatedAt) {
    }

    /**
     * batch_risk_event 表行记录：风险只增不改。
     */
    public record RiskEventRow(long id, String batchKey, String riskType, String detail,
                               String actorId, String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> {
        Double min = rs.getObject("min_storage_temp_c", Double.class);
        Double max = rs.getObject("max_storage_temp_c", Double.class);
        return new BatchRow(
                rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
                rs.getString("batch_no"), rs.getString("produced_at"),
                rs.getString("status"), rs.getString("created_at"),
                rs.getLong("version"), min, max);
    };

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
            rs.getInt("seq"), rs.getString("relation_type"), rs.getString("created_at"));

    private static final RowMapper<ExcursionRow> EXCURSION_MAPPER = (rs, n) -> new ExcursionRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("excursion_key"),
            rs.getString("start_at"), rs.getString("end_at"),
            rs.getDouble("measured_min_temp_c"), rs.getDouble("measured_max_temp_c"),
            rs.getString("severity"), rs.getString("status"),
            rs.getLong("batch_version"), rs.getString("registered_at"));

    private static final RowMapper<AdjudicationRow> ADJUDICATION_MAPPER = (rs, n) -> new AdjudicationRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("excursion_key"),
            rs.getString("disposition"), rs.getString("actor_id"), rs.getString("reason"),
            rs.getString("rework_batch_key"), rs.getString("adjudicated_at"));

    private static final RowMapper<RiskEventRow> RISK_MAPPER = (rs, n) -> new RiskEventRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("risk_type"),
            rs.getString("detail"), rs.getString("actor_id"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRow> findBatch(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/偏差事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at,"
                        + " version, min_storage_temp_c, max_storage_temp_c)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.createdAt(), row.version(),
                row.minStorageTempC(), row.maxStorageTempC());
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
     * 批次版本号 +1，每次储运偏差登记在同一事务内调用。
     */
    public void incrementVersion(String batchKey) {
        jdbc.update("UPDATE batch SET version = version + 1 WHERE batch_key = ?", batchKey);
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
        jdbc.update("INSERT INTO batch_lineage (parent_key, child_key, seq, relation_type, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.seq(), row.relationType(), row.createdAt());
    }

    /**
     * 全部血缘边（父→子），用于在内存中推导祖先链与后代集合；关系不可改写，只增不改。
     */
    public List<LineageRow> findAllLineage() {
        return jdbc.query("SELECT * FROM batch_lineage ORDER BY id", LINEAGE_MAPPER);
    }

    /**
     * 某批次的直接父批业务键；每个子批仅一个父批（拆分或返工）。
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
     * 全部存在 MAJOR 偏差 REJECT 裁决的批次业务键；这些批次及其后代按召回口径拦截。
     */
    public List<String> findExcursionRejectedKeys() {
        return jdbc.queryForList(
                "SELECT DISTINCT batch_key FROM excursion_adjudication WHERE disposition = 'REJECT'",
                String.class);
    }

    // ---------- 储运偏差 ----------

    public void insertExcursion(ExcursionRow row) {
        jdbc.update("INSERT INTO storage_excursion (batch_key, excursion_key, start_at, end_at,"
                        + " measured_min_temp_c, measured_max_temp_c, severity, status, batch_version,"
                        + " registered_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.excursionKey(), row.startAt(), row.endAt(),
                row.measuredMinTempC(), row.measuredMaxTempC(), row.severity(), row.status(),
                row.batchVersion(), row.registeredAt());
    }

    public Optional<ExcursionRow> findExcursion(String batchKey, String excursionKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE batch_key = ? AND excursion_key = ?",
                        EXCURSION_MAPPER, batchKey, excursionKey)
                .stream().findFirst();
    }

    public List<ExcursionRow> findExcursions(String batchKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE batch_key = ? ORDER BY id",
                EXCURSION_MAPPER, batchKey);
    }

    /**
     * 推进偏差裁决状态（OPEN→ADJUDICATED）；区间、温度与严重级别列不参与更新，保持不可变。
     */
    public void updateExcursionStatus(String batchKey, String excursionKey, String status) {
        jdbc.update("UPDATE storage_excursion SET status = ? WHERE batch_key = ? AND excursion_key = ?",
                status, batchKey, excursionKey);
    }

    public void insertAdjudication(AdjudicationRow row) {
        jdbc.update("INSERT INTO excursion_adjudication (batch_key, excursion_key, disposition,"
                        + " actor_id, reason, rework_batch_key, adjudicated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.excursionKey(), row.disposition(), row.actorId(),
                row.reason(), row.reworkBatchKey(), row.adjudicatedAt());
    }

    public Optional<AdjudicationRow> findAdjudication(String batchKey, String excursionKey) {
        return jdbc.query("SELECT * FROM excursion_adjudication WHERE batch_key = ? AND excursion_key = ?",
                        ADJUDICATION_MAPPER, batchKey, excursionKey)
                .stream().findFirst();
    }

    public List<AdjudicationRow> findAdjudications(String batchKey) {
        return jdbc.query("SELECT * FROM excursion_adjudication WHERE batch_key = ? ORDER BY id",
                ADJUDICATION_MAPPER, batchKey);
    }

    public void insertRiskEvent(RiskEventRow row) {
        jdbc.update("INSERT INTO batch_risk_event (batch_key, risk_type, detail, actor_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.riskType(), row.detail(), row.actorId(), row.createdAt());
    }

    public List<RiskEventRow> findRiskEvents(String batchKey) {
        return jdbc.query("SELECT * FROM batch_risk_event WHERE batch_key = ? ORDER BY id",
                RISK_MAPPER, batchKey);
    }
}
