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
                           String producedAt, String status, String createdAt,
                           String recipeKey, BigDecimal targetMass, int weighingVersion) {
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
            rs.getString("status"), rs.getString("created_at"),
            rs.getString("recipe_key"), rs.getBigDecimal("target_mass"),
            rs.getInt("weighing_version"));

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
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at,"
                        + " recipe_key, target_mass, weighing_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.createdAt(), row.recipeKey(), row.targetMass(), row.weighingVersion());
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
     * recipe_version 表行记录：不可变配方版本头。
     */
    public record RecipeRow(long id, String recipeKey, String createdAt) {
    }

    /**
     * recipe_ingredient 表行记录：成分及其质量百分比（最多4位小数，正值，全配方合计100）。
     */
    public record IngredientRow(long id, String recipeKey, String ingredient,
                                BigDecimal percentage, int seq) {
    }

    /**
     * weighing 表行记录：称量链只增不改；supersededBy 为 NULL 的行为当前有效行。
     */
    public record WeighingRow(long id, String batchKey, String ingredient, BigDecimal amount,
                              Long replacesId, Long supersededBy, String actorId, String createdAt) {
    }

    private static final RowMapper<RecipeRow> RECIPE_MAPPER = (rs, n) -> new RecipeRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getString("created_at"));

    private static final RowMapper<IngredientRow> INGREDIENT_MAPPER = (rs, n) -> new IngredientRow(
            rs.getLong("id"), rs.getString("recipe_key"), rs.getString("ingredient"),
            rs.getBigDecimal("percentage"), rs.getInt("seq"));

    private static final RowMapper<WeighingRow> WEIGHING_MAPPER = (rs, n) -> new WeighingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("ingredient"),
            rs.getBigDecimal("amount"), rs.getObject("replaces_id", Long.class),
            rs.getObject("superseded_by", Long.class), rs.getString("actor_id"),
            rs.getString("created_at"));

    public void insertRecipe(String recipeKey, String createdAt) {
        jdbc.update("INSERT INTO recipe_version (recipe_key, created_at) VALUES (?, ?)",
                recipeKey, createdAt);
    }

    public Optional<RecipeRow> findRecipe(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_key = ?",
                        RECIPE_MAPPER, recipeKey)
                .stream().findFirst();
    }

    public void insertRecipeIngredient(String recipeKey, String ingredient,
                                       BigDecimal percentage, int seq) {
        jdbc.update("INSERT INTO recipe_ingredient (recipe_key, ingredient, percentage, seq)"
                        + " VALUES (?, ?, ?, ?)",
                recipeKey, ingredient, percentage, seq);
    }

    /**
     * 配方成分列表，按创建请求顺序返回；配方不可变，查询结果稳定。
     */
    public List<IngredientRow> findRecipeIngredients(String recipeKey) {
        return jdbc.query("SELECT * FROM recipe_ingredient WHERE recipe_key = ? ORDER BY seq",
                INGREDIENT_MAPPER, recipeKey);
    }

    /**
     * 追加称量行并返回自增称量ID；称量链只增不改。
     */
    public long insertWeighing(WeighingRow row) {
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO weighing (batch_key, ingredient, amount, replaces_id,"
                            + " superseded_by, actor_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, row.batchKey());
            ps.setString(2, row.ingredient());
            ps.setBigDecimal(3, row.amount());
            if (row.replacesId() == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, row.replacesId());
            }
            ps.setNull(5, java.sql.Types.BIGINT);
            ps.setString(6, row.actorId());
            ps.setString(7, row.createdAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<WeighingRow> findWeighing(long id) {
        return jdbc.query("SELECT * FROM weighing WHERE id = ?", WEIGHING_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 批次当前有效称量链（未被更正替代的行），按称量ID升序。
     */
    public List<WeighingRow> findEffectiveWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? AND superseded_by IS NULL"
                        + " ORDER BY id", WEIGHING_MAPPER, batchKey);
    }

    /**
     * 批次全部称量行（含已被更正替代的历史行），按称量ID升序。
     */
    public List<WeighingRow> findAllWeighings(String batchKey) {
        return jdbc.query("SELECT * FROM weighing WHERE batch_key = ? ORDER BY id",
                WEIGHING_MAPPER, batchKey);
    }

    /**
     * 把被更正的称量行标记为失效，指向替代它的新行。
     */
    public void markWeighingSuperseded(long id, long supersededBy) {
        jdbc.update("UPDATE weighing SET superseded_by = ? WHERE id = ?", supersededBy, id);
    }

    /**
     * 推进批次称量链版本号；与业务写入同事务提交。
     */
    public void updateWeighingVersion(String batchKey, int version) {
        jdbc.update("UPDATE batch SET weighing_version = ? WHERE batch_key = ?",
                version, batchKey);
    }
}
