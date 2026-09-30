package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 批次域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class BatchRepository {

    /**
     * batch 表行记录；id 同时作为同批次事件的提交顺序依据。
     * cleanGeneration 为生产线清洁代次，合批时用于校验全部父批是否同代次。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, int cleanGeneration, String createdAt) {
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
     * batch_lineage 表行记录：SPLIT 一父多子、MERGE 多父一子；创建后不可改写。
     */
    public record LineageRow(long id, String relationType, String parentKey, String childKey,
                             int seq, String createdAt) {
    }

    /**
     * batch_allergen 表行记录：规范化过敏原集合中的一项。
     */
    public record AllergenRow(String batchKey, String allergen, int seq) {
    }

    /**
     * batch_merge 表行记录：一次合批事件，冻结清洁代次。
     */
    public record MergeRow(long id, String commandKey, String childKey,
                           int frozenCleanGeneration, String createdAt) {
    }

    /**
     * batch_merge_parent 表行记录：单个父批合批时的过敏原集合（JSON）与代次快照。
     */
    public record MergeParentRow(long id, long mergeId, String parentKey, int seq,
                                 String allergensJson, int cleanGeneration) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getInt("clean_generation"), rs.getString("created_at"));

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
            rs.getLong("id"), rs.getString("relation_type"), rs.getString("parent_key"),
            rs.getString("child_key"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<AllergenRow> ALLERGEN_MAPPER = (rs, n) -> new AllergenRow(
            rs.getString("batch_key"), rs.getString("allergen"), rs.getInt("seq"));

    private static final RowMapper<MergeRow> MERGE_MAPPER = (rs, n) -> new MergeRow(
            rs.getLong("id"), rs.getString("command_key"), rs.getString("child_key"),
            rs.getInt("frozen_clean_generation"), rs.getString("created_at"));

    private static final RowMapper<MergeParentRow> MERGE_PARENT_MAPPER = (rs, n) ->
            new MergeParentRow(rs.getLong("id"), rs.getLong("merge_id"), rs.getString("parent_key"),
                    rs.getInt("seq"), rs.getString("allergens_json"), rs.getInt("clean_generation"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRow> findBatch(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/合批/清洁推进事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status,"
                        + " clean_generation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.cleanGeneration(), row.createdAt());
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
     * 推进生产线清洁代次；调用方须在事务内持有该行行锁并保证只增不减。
     */
    public void updateCleanGeneration(String batchKey, int cleanGeneration) {
        jdbc.update("UPDATE batch SET clean_generation = ? WHERE batch_key = ?",
                cleanGeneration, batchKey);
    }

    public List<BatchRow> findAvailableBatches() {
        return jdbc.query("SELECT * FROM batch WHERE status <> 'RECALLED' ORDER BY id", BATCH_MAPPER);
    }

    public void insertAllergen(AllergenRow row) {
        jdbc.update("INSERT INTO batch_allergen (batch_key, allergen, seq) VALUES (?, ?, ?)",
                row.batchKey(), row.allergen(), row.seq());
    }

    /**
     * 规范化过敏原集合：按 seq（字典序）返回；空结果表示该批次不含登记过敏原。
     */
    public List<String> findAllergens(String batchKey) {
        return jdbc.query("SELECT * FROM batch_allergen WHERE batch_key = ? ORDER BY seq",
                ALLERGEN_MAPPER, batchKey).stream().map(AllergenRow::allergen).toList();
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
        jdbc.update("INSERT INTO batch_lineage (relation_type, parent_key, child_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.relationType(), row.parentKey(), row.childKey(), row.seq(), row.createdAt());
    }

    /**
     * 全部血缘边（父→子，含 SPLIT/MERGE），用于在内存中推导祖先链与后代集合；关系不可改写。
     */
    public List<LineageRow> findAllLineage() {
        return jdbc.query("SELECT * FROM batch_lineage ORDER BY id", LINEAGE_MAPPER);
    }

    /**
     * 某批次的直接父批业务键及关系类型；合批子批可能有多个父批。
     */
    public List<LineageRow> findParentEdges(String childKey) {
        return jdbc.query("SELECT * FROM batch_lineage WHERE child_key = ? ORDER BY seq",
                LINEAGE_MAPPER, childKey);
    }

    /**
     * 全部被直接召回（RECALLED）的批次业务键。
     */
    public List<String> findRecalledKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch WHERE status = 'RECALLED'",
                String.class);
    }

    public long insertMerge(MergeRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO batch_merge (command_key, child_key, frozen_clean_generation, created_at)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.commandKey());
            ps.setString(2, row.childKey());
            ps.setInt(3, row.frozenCleanGeneration());
            ps.setString(4, row.createdAt());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Optional<MergeRow> findMergeByChild(String childKey) {
        return jdbc.query("SELECT * FROM batch_merge WHERE child_key = ?", MERGE_MAPPER, childKey)
                .stream().findFirst();
    }

    public List<MergeParentRow> findMergeParents(long mergeId) {
        return jdbc.query("SELECT * FROM batch_merge_parent WHERE merge_id = ? ORDER BY seq",
                MERGE_PARENT_MAPPER, mergeId);
    }

    public void insertMergeParent(MergeParentRow row) {
        jdbc.update("INSERT INTO batch_merge_parent (merge_id, parent_key, seq, allergens_json,"
                        + " clean_generation) VALUES (?, ?, ?, ?, ?)",
                row.mergeId(), row.parentKey(), row.seq(), row.allergensJson(),
                row.cleanGeneration());
    }
}
