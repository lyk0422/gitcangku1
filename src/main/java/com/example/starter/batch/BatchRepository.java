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
        // 可用批次：排除已召回；排除条件到期降级（PENDING_RELEASE 且存在 EXPIRED 条件放行）的批次
        return jdbc.query("SELECT * FROM batch b WHERE b.status <> 'RECALLED'"
                        + " AND NOT (b.status = 'PENDING_RELEASE' AND EXISTS ("
                        + " SELECT 1 FROM conditional_release c"
                        + " WHERE c.batch_key = b.batch_key AND c.status = 'EXPIRED'))"
                        + " ORDER BY b.id",
                BATCH_MAPPER);
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

    /**
     * conditional_release 表行记录；completedAt 未完成为 null。
     */
    public record ConditionRow(long id, String batchKey, String conditionKey, String createdBy,
                               String createdRole, String expiresAt, String status,
                               String createdAt, String completedAt) {
    }

    /**
     * condition_item 表行记录；cleared 为 0/1，核销字段未核销时为 null。
     */
    public record ConditionItemRow(long id, String conditionKey, String batchKey, String itemKey,
                                   String description, int seq, int cleared, String clearedBy,
                                   String clearedRole, String evidence, String clearedAt) {
    }

    private static final RowMapper<ConditionRow> CONDITION_MAPPER = (rs, n) -> new ConditionRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("condition_key"),
            rs.getString("created_by"), rs.getString("created_role"), rs.getString("expires_at"),
            rs.getString("status"), rs.getString("created_at"), rs.getString("completed_at"));

    private static final RowMapper<ConditionItemRow> CONDITION_ITEM_MAPPER = (rs, n) ->
            new ConditionItemRow(rs.getLong("id"), rs.getString("condition_key"),
                    rs.getString("batch_key"), rs.getString("item_key"), rs.getString("description"),
                    rs.getInt("seq"), rs.getInt("cleared"), rs.getString("cleared_by"),
                    rs.getString("cleared_role"), rs.getString("evidence"),
                    rs.getString("cleared_at"));

    public Optional<ConditionRow> findCondition(String conditionKey) {
        return jdbc.query("SELECT * FROM conditional_release WHERE condition_key = ?",
                        CONDITION_MAPPER, conditionKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取条件放行记录，串行化同一条件上的核销事务。
     */
    public Optional<ConditionRow> findConditionForUpdate(String conditionKey) {
        return jdbc.query("SELECT * FROM conditional_release WHERE condition_key = ? FOR UPDATE",
                        CONDITION_MAPPER, conditionKey)
                .stream().findFirst();
    }

    /**
     * 某批次当前 ACTIVE 的条件放行（同一批次至多一条 ACTIVE）。
     */
    public Optional<ConditionRow> findActiveConditionByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM conditional_release"
                        + " WHERE batch_key = ? AND status = 'ACTIVE' ORDER BY id",
                        CONDITION_MAPPER, batchKey)
                .stream().findFirst();
    }

    public List<ConditionRow> findConditionsByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM conditional_release WHERE batch_key = ? ORDER BY id",
                CONDITION_MAPPER, batchKey);
    }

    public void insertCondition(ConditionRow row) {
        jdbc.update("INSERT INTO conditional_release (batch_key, condition_key, created_by,"
                        + " created_role, expires_at, status, created_at, completed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.conditionKey(), row.createdBy(), row.createdRole(),
                row.expiresAt(), row.status(), row.createdAt(), row.completedAt());
    }

    public void updateConditionStatus(String conditionKey, String status, String completedAt) {
        jdbc.update("UPDATE conditional_release SET status = ?, completed_at = ?"
                        + " WHERE condition_key = ?",
                status, completedAt, conditionKey);
    }

    public void insertConditionItem(ConditionItemRow row) {
        jdbc.update("INSERT INTO condition_item (condition_key, batch_key, item_key, description,"
                        + " seq, cleared, cleared_by, cleared_role, evidence, cleared_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.conditionKey(), row.batchKey(), row.itemKey(), row.description(), row.seq(),
                row.cleared(), row.clearedBy(), row.clearedRole(), row.evidence(), row.clearedAt());
    }

    public List<ConditionItemRow> findConditionItems(String conditionKey) {
        return jdbc.query("SELECT * FROM condition_item WHERE condition_key = ? ORDER BY seq",
                CONDITION_ITEM_MAPPER, conditionKey);
    }

    /**
     * 核销子项：仅当未核销时更新，返回受影响行数（0 表示已被并发核销，不得重复计数）。
     */
    public int clearConditionItem(String conditionKey, String itemKey, String clearedBy,
                                  String clearedRole, String evidence, String clearedAt) {
        return jdbc.update("UPDATE condition_item SET cleared = 1, cleared_by = ?,"
                        + " cleared_role = ?, evidence = ?, cleared_at = ?"
                        + " WHERE condition_key = ? AND item_key = ? AND cleared = 0",
                clearedBy, clearedRole, evidence, clearedAt, conditionKey, itemKey);
    }

    /**
     * 全部 ACTIVE 条件放行（调用方在持有批次行锁时调用，到期时刻用可注入时钟在 Java 侧比较，
     * 避免 ISO instant 字符串小数位不一致导致的字典序误判）。
     */
    public List<ConditionRow> findActiveConditions() {
        return jdbc.query("SELECT * FROM conditional_release WHERE status = 'ACTIVE' ORDER BY id",
                CONDITION_MAPPER);
    }
}
