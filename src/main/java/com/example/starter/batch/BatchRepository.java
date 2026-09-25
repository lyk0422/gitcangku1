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
     * test_result 表行记录；compositionVersion 为提交时批次当前成分版本号。
     */
    public record TestRow(long id, String batchKey, String testKey, String testItem,
                          String outcome, String inspector, int compositionVersion,
                          String createdAt) {
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
     * composition_version 表行记录：不可变成分版本。
     */
    public record CompositionRow(long id, String batchKey, int version, String allergenCodes,
                                 String segregationLevel, String createdAt) {
    }

    /**
     * merge_lineage 表行记录：合批来源关系，创建后不可改写。
     */
    public record MergeLineageRow(long id, String targetKey, String sourceKey,
                                  String containerKey, int seq, String createdAt) {
    }

    /**
     * allergen_risk 表行记录：过敏原风险与原放行快照。
     */
    public record RiskRow(long id, String batchKey, String enteredAt, long approvalMarker,
                          String snapshotJson, String clearedAt) {
    }

    /**
     * container_compatibility 表行记录：容器声明可共线合批的不同隔离级别无序对。
     */
    public record CompatRow(String containerKey, String levelA, String levelB) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<TestRow> TEST_MAPPER = (rs, n) -> new TestRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("test_key"),
            rs.getString("test_item"), rs.getString("outcome"),
            rs.getString("inspector"), rs.getInt("composition_version"),
            rs.getString("created_at"));

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

    private static final RowMapper<CompositionRow> COMPOSITION_MAPPER = (rs, n) -> new CompositionRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
            rs.getString("allergen_codes"), rs.getString("segregation_level"),
            rs.getString("created_at"));

    private static final RowMapper<MergeLineageRow> MERGE_LINEAGE_MAPPER = (rs, n) ->
            new MergeLineageRow(rs.getLong("id"), rs.getString("target_key"),
                    rs.getString("source_key"), rs.getString("container_key"),
                    rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<RiskRow> RISK_MAPPER = (rs, n) -> new RiskRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("entered_at"),
            rs.getLong("approval_marker"), rs.getString("snapshot_json"),
            rs.getString("cleared_at"));

    private static final RowMapper<CompatRow> COMPAT_MAPPER = (rs, n) -> new CompatRow(
            rs.getString("container_key"), rs.getString("level_a"), rs.getString("level_b"));

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
        jdbc.update("INSERT INTO test_result (batch_key, test_key, test_item, outcome, inspector,"
                        + " composition_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.testKey(), row.testItem(), row.outcome(),
                row.inspector(), row.compositionVersion(), row.createdAt());
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
     * 全部已知过敏原代码。
     */
    public List<String> findAllergenCodes() {
        return jdbc.queryForList("SELECT code FROM allergen_code", String.class);
    }

    /**
     * 容器是否存在。
     */
    public boolean containerExists(String containerKey) {
        return !jdbc.queryForList("SELECT container_key FROM container WHERE container_key = ?",
                String.class, containerKey).isEmpty();
    }

    /**
     * 容器声明的兼容级别对（level_a 序号小于 level_b）。
     */
    public List<CompatRow> findCompatibility(String containerKey) {
        return jdbc.query("SELECT * FROM container_compatibility WHERE container_key = ?",
                COMPAT_MAPPER, containerKey);
    }

    public void insertComposition(CompositionRow row) {
        jdbc.update("INSERT INTO composition_version (batch_key, version, allergen_codes,"
                        + " segregation_level, created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.allergenCodes(), row.segregationLevel(),
                row.createdAt());
    }

    /**
     * 批次当前（最大版本号）成分版本；批次创建时必有 v1。
     */
    public Optional<CompositionRow> findLatestComposition(String batchKey) {
        return jdbc.query("SELECT * FROM composition_version WHERE batch_key = ?"
                        + " ORDER BY version DESC LIMIT 1", COMPOSITION_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 批次全部成分版本，按版本号升序。
     */
    public List<CompositionRow> findCompositions(String batchKey) {
        return jdbc.query("SELECT * FROM composition_version WHERE batch_key = ? ORDER BY version",
                COMPOSITION_MAPPER, batchKey);
    }

    /**
     * 某成分版本是否已有 PASS 检验（放行门禁的"已检验"判定）。
     */
    public boolean existsPassTestOnVersion(String batchKey, int version) {
        return !jdbc.queryForList("SELECT id FROM test_result WHERE batch_key = ?"
                        + " AND composition_version = ? AND outcome = 'PASS' LIMIT 1",
                Long.class, batchKey, version).isEmpty();
    }

    /**
     * 某成分版本上已 PASS 的检验项集合（ALLERGEN_RISK 重新检验判定）。
     */
    public List<String> findPassedItemsOnVersion(String batchKey, int version) {
        return jdbc.queryForList("SELECT DISTINCT test_item FROM test_result WHERE batch_key = ?"
                        + " AND composition_version = ? AND outcome = 'PASS'",
                String.class, batchKey, version);
    }

    public void insertMergeLineage(MergeLineageRow row) {
        jdbc.update("INSERT INTO merge_lineage (target_key, source_key, container_key, seq,"
                        + " created_at) VALUES (?, ?, ?, ?, ?)",
                row.targetKey(), row.sourceKey(), row.containerKey(), row.seq(), row.createdAt());
    }

    /**
     * 全部合批血缘边（来源→目标），用于推导最终血缘集合；关系不可改写，只增不改。
     */
    public List<MergeLineageRow> findAllMergeLineage() {
        return jdbc.query("SELECT * FROM merge_lineage ORDER BY id", MERGE_LINEAGE_MAPPER);
    }

    /**
     * 某目标批次的直接合批来源，按合批顺序。
     */
    public List<MergeLineageRow> findMergeSources(String targetKey) {
        return jdbc.query("SELECT * FROM merge_lineage WHERE target_key = ? ORDER BY seq",
                MERGE_LINEAGE_MAPPER, targetKey);
    }

    public void insertRisk(RiskRow row) {
        jdbc.update("INSERT INTO allergen_risk (batch_key, entered_at, approval_marker,"
                        + " snapshot_json, cleared_at) VALUES (?, ?, ?, ?, NULL)",
                row.batchKey(), row.enteredAt(), row.approvalMarker(), row.snapshotJson());
    }

    /**
     * 批次当前未解除的过敏原风险。
     */
    public Optional<RiskRow> findActiveRisk(String batchKey) {
        return jdbc.query("SELECT * FROM allergen_risk WHERE batch_key = ? AND cleared_at IS NULL",
                        RISK_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 批次最近一次过敏原风险（含已解除），用于风险查询。
     */
    public Optional<RiskRow> findLatestRisk(String batchKey) {
        return jdbc.query("SELECT * FROM allergen_risk WHERE batch_key = ? ORDER BY id DESC LIMIT 1",
                        RISK_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void clearRisk(String batchKey, String clearedAt) {
        jdbc.update("UPDATE allergen_risk SET cleared_at = ? WHERE batch_key = ?"
                + " AND cleared_at IS NULL", clearedAt, batchKey);
    }

    /**
     * 批次当前最大批准记录 id；无批准时返回 0（作为风险进入时的批准水位线）。
     */
    public long maxApprovalId(String batchKey) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM approval WHERE batch_key = ?",
                Long.class, batchKey);
        return value == null ? 0L : value;
    }
}
