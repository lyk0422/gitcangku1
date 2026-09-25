package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 批次域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存储。
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
                          String outcome, String inspector, int componentVersion, String createdAt) {
    }

    /**
     * approval 表行记录。
     */
    public record ApprovalRow(long id, String batchKey, String commandKey, String actorId,
                              String role, int seq, int round, String createdAt) {
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
     * allergen_component 表行记录：不可变成分版本。
     */
    public record ComponentRow(long id, String batchKey, int version, String allergenCodes,
                               String segregationLevel, String commandKey, String createdAt) {
    }

    /**
     * merge_compat 表行记录：目标容器声明的兼容级别对。
     */
    public record CompatRow(long id, String targetKey, String levelA, String levelB, String createdAt) {
    }

    /**
     * merge_lineage 表行记录：合批血缘边。
     */
    public record MergeLineageRow(long id, String commandKey, String targetKey, String sourceKey,
                                  int seq, BigDecimal quantity, String createdAt) {
    }

    /**
     * inventory_stock 表行记录。
     */
    public record StockRow(String batchKey, BigDecimal quantity, String updatedAt) {
    }

    /**
     * allergen_risk 表行记录。
     */
    public record RiskRow(long id, String batchKey, int detectedVersion, String newAllergenCodes,
                          String releaseSnapshot, String detectedAt, String resolvedAt,
                          String resolveCommandKey) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<TestRow> TEST_MAPPER = (rs, n) -> new TestRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("test_key"),
            rs.getString("test_item"), rs.getString("outcome"),
            rs.getString("inspector"), rs.getInt("component_version"), rs.getString("created_at"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("actor_id"), rs.getString("role"),
            rs.getInt("seq"), rs.getInt("round"), rs.getString("created_at"));

    private static final RowMapper<RecallRow> RECALL_MAPPER = (rs, n) -> new RecallRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getString("actor_id"), rs.getString("reason"), rs.getString("created_at"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getString("command_type"), rs.getString("command_key"), rs.getString("fingerprint"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private static final RowMapper<LineageRow> LINEAGE_MAPPER = (rs, n) -> new LineageRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<ComponentRow> COMPONENT_MAPPER = (rs, n) -> new ComponentRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("version"),
            rs.getString("allergen_codes"), rs.getString("segregation_level"),
            rs.getString("command_key"), rs.getString("created_at"));

    private static final RowMapper<CompatRow> COMPAT_MAPPER = (rs, n) -> new CompatRow(
            rs.getLong("id"), rs.getString("target_key"), rs.getString("level_a"),
            rs.getString("level_b"), rs.getString("created_at"));

    private static final RowMapper<MergeLineageRow> MERGE_LINEAGE_MAPPER = (rs, n) ->
            new MergeLineageRow(rs.getLong("id"), rs.getString("command_key"),
                    rs.getString("target_key"), rs.getString("source_key"), rs.getInt("seq"),
                    rs.getBigDecimal("quantity"), rs.getString("created_at"));

    private static final RowMapper<StockRow> STOCK_MAPPER = (rs, n) -> new StockRow(
            rs.getString("batch_key"), rs.getBigDecimal("quantity"), rs.getString("updated_at"));

    private static final RowMapper<RiskRow> RISK_MAPPER = (rs, n) -> new RiskRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("detected_version"),
            rs.getString("new_allergen_codes"), rs.getString("release_snapshot"),
            rs.getString("detected_at"), rs.getString("resolved_at"),
            rs.getString("resolve_command_key"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRow> findBatch(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/修订/合批事务。
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
                        + " component_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.testKey(), row.testItem(), row.outcome(),
                row.inspector(), row.componentVersion(), row.createdAt());
    }

    public List<TestRow> findTests(String batchKey) {
        return jdbc.query("SELECT * FROM test_result WHERE batch_key = ? ORDER BY id",
                TEST_MAPPER, batchKey);
    }

    public List<ApprovalRow> findApprovals(String batchKey) {
        return jdbc.query("SELECT * FROM approval WHERE batch_key = ? ORDER BY id",
                APPROVAL_MAPPER, batchKey);
    }

    public void insertApproval(ApprovalRow row) {
        jdbc.update("INSERT INTO approval (batch_key, command_key, actor_id, role, seq, round,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.commandKey(), row.actorId(), row.role(),
                row.seq(), row.round(), row.createdAt());
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

    // ---------- 过敏原成分字典与不可变版本 ----------

    /**
     * 字典内全部合法过敏原代码。
     */
    public List<String> findAllergenCatalogCodes() {
        return jdbc.queryForList("SELECT code FROM allergen_catalog", String.class);
    }

    public void insertComponent(ComponentRow row) {
        jdbc.update("INSERT INTO allergen_component (batch_key, version, allergen_codes,"
                        + " segregation_level, command_key, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.version(), row.allergenCodes(), row.segregationLevel(),
                row.commandKey(), row.createdAt());
    }

    /**
     * 批次全部成分版本，按版本号升序；从不更新或删除。
     */
    public List<ComponentRow> findComponents(String batchKey) {
        return jdbc.query("SELECT * FROM allergen_component WHERE batch_key = ? ORDER BY version",
                COMPONENT_MAPPER, batchKey);
    }

    /**
     * 批次当前（版本号最大）成分版本；从未落成分版本的批次返回空。
     */
    public Optional<ComponentRow> findCurrentComponent(String batchKey) {
        return jdbc.query("SELECT * FROM allergen_component WHERE batch_key = ?"
                        + " ORDER BY version DESC LIMIT 1", COMPONENT_MAPPER, batchKey)
                .stream().findFirst();
    }

    // ---------- 合批兼容矩阵 ----------

    public void insertMergeCompat(CompatRow row) {
        jdbc.update("INSERT INTO merge_compat (target_key, level_a, level_b, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.targetKey(), row.levelA(), row.levelB(), row.createdAt());
    }

    /**
     * 目标容器声明的全部兼容级别对。
     */
    public List<CompatRow> findMergeCompat(String targetKey) {
        return jdbc.query("SELECT * FROM merge_compat WHERE target_key = ? ORDER BY id",
                COMPAT_MAPPER, targetKey);
    }

    // ---------- 合批血缘 ----------

    public void insertMergeLineage(MergeLineageRow row) {
        jdbc.update("INSERT INTO merge_lineage (command_key, target_key, source_key, seq,"
                        + " quantity, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.commandKey(), row.targetKey(), row.sourceKey(), row.seq(),
                row.quantity(), row.createdAt());
    }

    public List<MergeLineageRow> findMergeLineageByTarget(String targetKey) {
        return jdbc.query("SELECT * FROM merge_lineage WHERE target_key = ? ORDER BY id",
                MERGE_LINEAGE_MAPPER, targetKey);
    }

    public Optional<MergeLineageRow> findMergeLineageBySource(String sourceKey) {
        return jdbc.query("SELECT * FROM merge_lineage WHERE source_key = ?",
                        MERGE_LINEAGE_MAPPER, sourceKey)
                .stream().findFirst();
    }

    public List<MergeLineageRow> findAllMergeLineage() {
        return jdbc.query("SELECT * FROM merge_lineage ORDER BY id", MERGE_LINEAGE_MAPPER);
    }

    // ---------- 库存 ----------

    public void insertStock(StockRow row) {
        jdbc.update("INSERT INTO inventory_stock (batch_key, quantity, updated_at) VALUES (?, ?, ?)",
                row.batchKey(), row.quantity(), row.updatedAt());
    }

    public Optional<StockRow> findStock(String batchKey) {
        return jdbc.query("SELECT * FROM inventory_stock WHERE batch_key = ?", STOCK_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取库存，串行化合批扣减/增加。
     */
    public Optional<StockRow> findStockForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM inventory_stock WHERE batch_key = ? FOR UPDATE",
                        STOCK_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void updateStock(String batchKey, BigDecimal quantity, String updatedAt) {
        jdbc.update("UPDATE inventory_stock SET quantity = ?, updated_at = ? WHERE batch_key = ?",
                quantity, updatedAt, batchKey);
    }

    public void insertStockLedger(String batchKey, String changeType, String refCommandKey,
                                  BigDecimal changeAmount, BigDecimal balanceAfter, String createdAt) {
        jdbc.update("INSERT INTO stock_ledger (batch_key, change_type, ref_command_key,"
                        + " change_amount, balance_after, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                batchKey, changeType, refCommandKey, changeAmount, balanceAfter, createdAt);
    }

    public long countStockLedgerByCommand(String commandKey) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE ref_command_key = ?",
                Long.class, commandKey);
        return count == null ? 0 : count;
    }

    // ---------- 过敏原风险 ----------

    public void insertRisk(RiskRow row) {
        jdbc.update("INSERT INTO allergen_risk (batch_key, detected_version, new_allergen_codes,"
                        + " release_snapshot, detected_at, resolved_at, resolve_command_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.detectedVersion(), row.newAllergenCodes(),
                row.releaseSnapshot(), row.detectedAt(), row.resolvedAt(), row.resolveCommandKey());
    }

    /**
     * 批次当前未解除的风险记录（resolved_at 为空）；无则空。
     */
    public Optional<RiskRow> findOpenRisk(String batchKey) {
        return jdbc.query("SELECT * FROM allergen_risk WHERE batch_key = ? AND resolved_at IS NULL"
                        + " ORDER BY id DESC LIMIT 1", RISK_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 批次全部风险记录（含已解除），按发现顺序。
     */
    public List<RiskRow> findRisks(String batchKey) {
        return jdbc.query("SELECT * FROM allergen_risk WHERE batch_key = ? ORDER BY id",
                RISK_MAPPER, batchKey);
    }

    public void resolveRisk(long riskId, String resolvedAt, String resolveCommandKey) {
        jdbc.update("UPDATE allergen_risk SET resolved_at = ?, resolve_command_key = ? WHERE id = ?",
                resolvedAt, resolveCommandKey, riskId);
    }
}
