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
     * conditional_release 表行记录：一条条件放行（1～5 个条件子项）。
     */
    public record ConditionalReleaseRow(long id, String conditionKey, String batchKey,
                                        String commandKey, String creatorId, String creatorRole,
                                        String expiresAt, String createdAt) {
    }

    /**
     * condition_item 表行记录：closedAt 为 null 表示尚未核销。
     */
    public record ConditionItemRow(long id, String conditionKey, String itemKey, String description,
                                   int seq, String closedAt, String closerId, String closerRole,
                                   String closeCommandKey, String evidence) {
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

    private static final RowMapper<ConditionalReleaseRow> CONDITIONAL_RELEASE_MAPPER = (rs, n) ->
            new ConditionalReleaseRow(rs.getLong("id"), rs.getString("condition_key"),
                    rs.getString("batch_key"), rs.getString("command_key"),
                    rs.getString("creator_id"), rs.getString("creator_role"),
                    rs.getString("expires_at"), rs.getString("created_at"));

    private static final RowMapper<ConditionItemRow> CONDITION_ITEM_MAPPER = (rs, n) ->
            new ConditionItemRow(rs.getLong("id"), rs.getString("condition_key"),
                    rs.getString("item_key"), rs.getString("description"), rs.getInt("seq"),
                    rs.getString("closed_at"), rs.getString("closer_id"),
                    rs.getString("closer_role"), rs.getString("close_command_key"),
                    rs.getString("evidence"));

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

    /**
     * 当前可用批次：排除已召回批次；CONDITIONAL 批次在其最新一条条件放行到期时刻已到
     * （到期降级后允许重建，旧条件放行记录保留，故只按 id 最大的当前条件放行裁决）
     * 且仍有未核销子项时实时降级，不出现在结果中（按传入的当前 UTC 时刻判定，不依赖后台任务）。
     * ISO-8601 UTC instant 字符串可直接按字典序比较先后。
     */
    public List<BatchRow> findAvailableBatches(String nowIso) {
        return jdbc.query(
                "SELECT * FROM batch b WHERE b.status <> 'RECALLED'"
                        + " AND NOT (b.status = 'CONDITIONAL' AND EXISTS ("
                        + " SELECT 1 FROM conditional_release c"
                        + " WHERE c.batch_key = b.batch_key AND c.expires_at <= ?"
                        + " AND c.id = (SELECT MAX(c2.id) FROM conditional_release c2"
                        + " WHERE c2.batch_key = b.batch_key)))"
                        + " ORDER BY b.id",
                BATCH_MAPPER, nowIso);
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

    /**
     * 插入幂等命令占位行：业务动作执行前先占用 (command_type, command_key)。
     * 并发同键 INSERT 会阻塞至先到事务提交（本方随后得到唯一键冲突并改读其快照）
     * 或回滚（占位行随事务消失，本方继续执行业务）；业务失败即回滚，失败不占键。
     */
    public void insertCommandPlaceholder(String commandType, String commandKey, String fingerprint,
                                         String createdAt) {
        jdbc.update("INSERT INTO command_log (command_type, command_key, fingerprint,"
                        + " response_status, response_body, created_at) VALUES (?, ?, ?, 0, '', ?)",
                commandType, commandKey, fingerprint, createdAt);
    }

    /**
     * 业务动作成功后，把占位行更新为首次响应快照。
     */
    public void updateCommandResult(String commandType, String commandKey, int responseStatus,
                                    String responseBody) {
        jdbc.update("UPDATE command_log SET response_status = ?, response_body = ?"
                        + " WHERE command_type = ? AND command_key = ?",
                responseStatus, responseBody, commandType, commandKey);
    }

    public Optional<ConditionalReleaseRow> findConditionalRelease(String conditionKey) {
        return jdbc.query("SELECT * FROM conditional_release WHERE condition_key = ?",
                        CONDITIONAL_RELEASE_MAPPER, conditionKey)
                .stream().findFirst();
    }

    /**
     * 批次最新一条条件放行（到期降级后可重建，历史条件放行全部保留）。
     */
    public Optional<ConditionalReleaseRow> findLatestConditionalReleaseForBatch(String batchKey) {
        return jdbc.query("SELECT * FROM conditional_release WHERE batch_key = ? ORDER BY id DESC LIMIT 1",
                        CONDITIONAL_RELEASE_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 批次全部条件放行（按创建顺序稳定排序；历史明细用，记录永不物理删除）。
     */
    public List<ConditionalReleaseRow> findConditionalReleasesForBatch(String batchKey) {
        return jdbc.query("SELECT * FROM conditional_release WHERE batch_key = ? ORDER BY id",
                CONDITIONAL_RELEASE_MAPPER, batchKey);
    }

    public void insertConditionalRelease(ConditionalReleaseRow row) {
        jdbc.update("INSERT INTO conditional_release (condition_key, batch_key, command_key,"
                        + " creator_id, creator_role, expires_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.conditionKey(), row.batchKey(), row.commandKey(), row.creatorId(),
                row.creatorRole(), row.expiresAt(), row.createdAt());
    }

    public List<ConditionItemRow> findConditionItems(String conditionKey) {
        return jdbc.query("SELECT * FROM condition_item WHERE condition_key = ? ORDER BY seq",
                CONDITION_ITEM_MAPPER, conditionKey);
    }

    public Optional<ConditionItemRow> findConditionItem(String conditionKey, String itemKey) {
        return jdbc.query("SELECT * FROM condition_item WHERE condition_key = ? AND item_key = ?",
                        CONDITION_ITEM_MAPPER, conditionKey, itemKey)
                .stream().findFirst();
    }

    public void insertConditionItem(String conditionKey, String itemKey, String description, int seq) {
        jdbc.update("INSERT INTO condition_item (condition_key, item_key, description, seq)"
                        + " VALUES (?, ?, ?, ?)",
                conditionKey, itemKey, description, seq);
    }

    /**
     * 核销子项：仅当 closed_at 仍为 NULL 时生效，返回受影响行数；
     * 行已存在（含并发重复核销）时返回 0，核销不重复计数。
     */
    public int closeConditionItemIfOpen(String conditionKey, String itemKey, String closerId,
                                        String closerRole, String commandKey, String evidence,
                                        String closedAt) {
        return jdbc.update("UPDATE condition_item SET closed_at = ?, closer_id = ?, closer_role = ?,"
                        + " close_command_key = ?, evidence = ?"
                        + " WHERE condition_key = ? AND item_key = ? AND closed_at IS NULL",
                closedAt, closerId, closerRole, commandKey, evidence, conditionKey, itemKey);
    }
}
