package com.example.starter.batch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 批次领域持久化。时间列以 UTC 的 LocalDateTime 写入 DATETIME(6)，读取时同样按 UTC 解释，
 * 与数据库会话时区无关，保证重启后状态与时间不变。
 */
@Repository
public class BatchRepository {

    /** 批次行。 */
    public record BatchRow(long id, String batchKey, String productCode, String lotNumber,
                           Instant producedAt, BatchStatus status) {
    }

    /** 检验结果行。 */
    public record TestResultRow(long id, long batchId, String testKey, String item,
                                TestOutcome outcome, String inspector, Instant createdAt) {
    }

    /** 批准行。 */
    public record ApprovalRow(long id, long batchId, ApprovalRole role, String actorId,
                              Instant createdAt) {
    }

    /** 召回行。 */
    public record RecallRow(long id, long batchId, String reason, String actorId,
                            Instant createdAt) {
    }

    /** 幂等命令行。 */
    public record CommandRow(long id, String operation, String commandKey, String fingerprint,
                             int responseStatus, String responseBody) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getString("product_code"),
            rs.getString("lot_number"),
            toInstant(rs, "produced_at"),
            BatchStatus.valueOf(rs.getString("status")));

    private static final RowMapper<TestResultRow> TEST_MAPPER = (rs, n) -> new TestResultRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("test_key"),
            rs.getString("item"),
            TestOutcome.valueOf(rs.getString("outcome")),
            rs.getString("inspector"),
            toInstant(rs, "created_at"));

    private static final RowMapper<ApprovalRow> APPROVAL_MAPPER = (rs, n) -> new ApprovalRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            ApprovalRole.valueOf(rs.getString("role")),
            rs.getString("actor_id"),
            toInstant(rs, "created_at"));

    private static final RowMapper<RecallRow> RECALL_MAPPER = (rs, n) -> new RecallRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getString("reason"),
            rs.getString("actor_id"),
            toInstant(rs, "created_at"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getLong("id"),
            rs.getString("operation"),
            rs.getString("command_key"),
            rs.getString("fingerprint"),
            rs.getInt("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 按业务键查询批次（不加锁）。 */
    public Optional<BatchRow> findBatchByKey(String batchKey) {
        List<BatchRow> rows = jdbc.query(
                "SELECT id, batch_key, product_code, lot_number, produced_at, status FROM batch WHERE batch_key = ?",
                BATCH_MAPPER, batchKey);
        return rows.stream().findFirst();
    }

    /** 按业务键查询批次并对行加排他锁（SELECT ... FOR UPDATE），须在事务内调用。 */
    public Optional<BatchRow> lockBatchByKey(String batchKey) {
        List<BatchRow> rows = jdbc.query(
                "SELECT id, batch_key, product_code, lot_number, produced_at, status FROM batch WHERE batch_key = ? FOR UPDATE",
                BATCH_MAPPER, batchKey);
        return rows.stream().findFirst();
    }

    /** 插入批次，返回自增 id。 */
    public long insertBatch(String batchKey, String productCode, String lotNumber,
                            Instant producedAt, BatchStatus status, Instant now) {
        jdbc.update(
                "INSERT INTO batch (batch_key, product_code, lot_number, produced_at, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchKey, productCode, lotNumber, toUtc(producedAt), status.name(), toUtc(now), toUtc(now));
        Long id = jdbc.queryForObject("SELECT id FROM batch WHERE batch_key = ?", Long.class, batchKey);
        return id == null ? -1L : id;
    }

    /** 更新批次状态与更新时间。 */
    public void updateBatchStatus(long batchId, BatchStatus status, Instant now) {
        jdbc.update("UPDATE batch SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), toUtc(now), batchId);
    }

    /** 插入必做检验项，seq 为请求中的顺序。 */
    public void insertRequiredItem(long batchId, String item, int seq) {
        jdbc.update("INSERT INTO batch_required_item (batch_id, item, seq) VALUES (?, ?, ?)",
                batchId, item, seq);
    }

    /** 按顺序查询批次必做检验项。 */
    public List<String> findRequiredItems(long batchId) {
        return jdbc.queryForList(
                "SELECT item FROM batch_required_item WHERE batch_id = ? ORDER BY seq",
                String.class, batchId);
    }

    /** 按检验幂等键查询批次内检验结果。 */
    public Optional<TestResultRow> findTestResult(long batchId, String testKey) {
        List<TestResultRow> rows = jdbc.query(
                "SELECT id, batch_id, test_key, item, outcome, inspector, created_at FROM test_result"
                        + " WHERE batch_id = ? AND test_key = ?",
                TEST_MAPPER, batchId, testKey);
        return rows.stream().findFirst();
    }

    /** 插入检验结果。 */
    public void insertTestResult(long batchId, String testKey, String item, TestOutcome outcome,
                                 String inspector, Instant now) {
        jdbc.update(
                "INSERT INTO test_result (batch_id, test_key, item, outcome, inspector, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                batchId, testKey, item, outcome.name(), inspector, toUtc(now));
    }

    /** 批次内已获得 PASS 结果的不同检验项数量。 */
    public int countPassedItems(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT item) FROM test_result WHERE batch_id = ? AND outcome = 'PASS'",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /** 批次全部检验结果，按提交时间升序。 */
    public List<TestResultRow> findTestResults(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, test_key, item, outcome, inspector, created_at FROM test_result"
                        + " WHERE batch_id = ? ORDER BY created_at, id",
                TEST_MAPPER, batchId);
    }

    /** 判断某人是否担任过该批次任一检验结果的检验人。 */
    public boolean existsTestResultByInspector(long batchId, String inspector) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_result WHERE batch_id = ? AND inspector = ?",
                Integer.class, batchId, inspector);
        return count != null && count > 0;
    }

    /** 判断该批次某角色是否已有批准。 */
    public boolean existsApprovalByRole(long batchId, ApprovalRole role) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_id = ? AND role = ?",
                Integer.class, batchId, role.name());
        return count != null && count > 0;
    }

    /** 判断某人是否已批准过该批次（任意角色）。 */
    public boolean existsApprovalByActor(long batchId, String actorId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_id = ? AND actor_id = ?",
                Integer.class, batchId, actorId);
        return count != null && count > 0;
    }

    /** 插入批准记录。 */
    public void insertApproval(long batchId, ApprovalRole role, String actorId, Instant now) {
        jdbc.update("INSERT INTO approval (batch_id, role, actor_id, created_at) VALUES (?, ?, ?, ?)",
                batchId, role.name(), actorId, toUtc(now));
    }

    /** 批次全部批准记录，按批准时间升序。 */
    public List<ApprovalRow> findApprovals(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, role, actor_id, created_at FROM approval WHERE batch_id = ?"
                        + " ORDER BY created_at, id",
                APPROVAL_MAPPER, batchId);
    }

    /** 插入召回记录。 */
    public void insertRecall(long batchId, String reason, String actorId, Instant now) {
        jdbc.update("INSERT INTO recall (batch_id, reason, actor_id, created_at) VALUES (?, ?, ?, ?)",
                batchId, reason, actorId, toUtc(now));
    }

    /** 查询批次召回记录（最多一条有效；历史全部保留）。 */
    public Optional<RecallRow> findRecall(long batchId) {
        List<RecallRow> rows = jdbc.query(
                "SELECT id, batch_id, reason, actor_id, created_at FROM recall WHERE batch_id = ?"
                        + " ORDER BY created_at, id",
                RECALL_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 当前可用批次：除 REJECTED 与 RECALLED 外的全部批次，按创建顺序。 */
    public List<BatchRow> findAvailableBatches() {
        return jdbc.query(
                "SELECT id, batch_key, product_code, lot_number, produced_at, status FROM batch"
                        + " WHERE status NOT IN ('REJECTED', 'RECALLED') ORDER BY id",
                BATCH_MAPPER);
    }

    /** 按操作类型与命令键查询幂等命令记录。 */
    public Optional<CommandRow> findCommand(String operation, String commandKey) {
        List<CommandRow> rows = jdbc.query(
                "SELECT id, operation, command_key, fingerprint, response_status, response_body"
                        + " FROM command_log WHERE operation = ? AND command_key = ?",
                COMMAND_MAPPER, operation, commandKey);
        return rows.stream().findFirst();
    }

    /** 记录命令首次执行结果快照。 */
    public void insertCommand(String operation, String commandKey, String fingerprint,
                              int responseStatus, String responseBody, Instant now) {
        jdbc.update(
                "INSERT INTO command_log (operation, command_key, fingerprint, response_status, response_body, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                operation, commandKey, fingerprint, responseStatus, responseBody, toUtc(now));
    }
}
