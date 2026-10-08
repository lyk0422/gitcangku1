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
     *
     * @param batchType STANDARD 普通批次；SEALED 密封批次（须压力曲线检验）
     * @param version   乐观版本号，从 0 开始，每次状态/曲线变更加 1
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String batchType, long version,
                           String createdAt) {
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

    /**
     * pressure_curve 表行记录：每个密封批次至多一条，PASS/FAIL 均保留且不可改写。
     */
    public record PressureCurveRow(long id, String batchKey, String requestId, String actorId,
                                   String outcome, int nodeCount, String totalDecline,
                                   String maxSegmentRate, long submittedVersion, long batchVersion,
                                   String createdAt) {
    }

    /**
     * pressure_curve_node 表行记录：按提交身份顺序存储，不重排。
     */
    public record PressureCurveNodeRow(long id, long curveId, int seq, long pointSecond,
                                       String pressureKpa, String createdAt) {
    }

    /**
     * pressure_curve_segment 表行记录：逐段下降/速率的冻结快照，批准放行后不可改写。
     */
    public record PressureCurveSegmentRow(long id, long curveId, int seq, long fromSecond,
                                          long toSecond, String declineKpa, String rateKpaPerSecond,
                                          boolean rateExceeded, String createdAt) {
    }

    /**
     * request_log 表行记录：X-Request-Id 全局幂等快照。
     */
    public record RequestLogRow(String requestId, String operation, String resourceKey,
                                String fingerprint, int responseStatus, String responseBody,
                                String actorId) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("batch_type"), rs.getLong("version"),
            rs.getString("created_at"));

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

    private static final RowMapper<PressureCurveRow> PRESSURE_CURVE_MAPPER = (rs, n) -> new PressureCurveRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("request_id"),
            rs.getString("actor_id"), rs.getString("outcome"), rs.getInt("node_count"),
            rs.getString("total_decline"), rs.getString("max_segment_rate"),
            rs.getLong("submitted_version"), rs.getLong("batch_version"),
            rs.getString("created_at"));

    private static final RowMapper<PressureCurveNodeRow> PRESSURE_NODE_MAPPER = (rs, n) ->
            new PressureCurveNodeRow(rs.getLong("id"), rs.getLong("curve_id"), rs.getInt("seq"),
                    rs.getLong("point_second"), rs.getString("pressure_kpa"),
                    rs.getString("created_at"));

    private static final RowMapper<PressureCurveSegmentRow> PRESSURE_SEGMENT_MAPPER = (rs, n) ->
            new PressureCurveSegmentRow(rs.getLong("id"), rs.getLong("curve_id"), rs.getInt("seq"),
                    rs.getLong("from_second"), rs.getLong("to_second"),
                    rs.getString("decline_kpa"), rs.getString("rate_kpa_per_second"),
                    rs.getInt("rate_exceeded") == 1, rs.getString("created_at"));

    private static final RowMapper<RequestLogRow> REQUEST_LOG_MAPPER = (rs, n) -> new RequestLogRow(
            rs.getString("request_id"), rs.getString("operation"), rs.getString("resource_key"),
            rs.getString("fingerprint"), rs.getInt("response_status"),
            rs.getString("response_body"), rs.getString("actor_id"));

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
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status,"
                        + " batch_type, version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.batchType(), row.version(), row.createdAt());
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
        jdbc.update("UPDATE batch SET status = ?, version = version + 1 WHERE batch_key = ?",
                status, batchKey);
    }

    /**
     * 仅当版本等于 expectedVersion 时更新状态并推进版本；返回受影响行数（0 表示版本冲突）。
     */
    public int compareAndUpdateStatus(String batchKey, String status, long expectedVersion) {
        return jdbc.update(
                "UPDATE batch SET status = ?, version = version + 1 WHERE batch_key = ? AND version = ?",
                status, batchKey, expectedVersion);
    }

    /**
     * 仅推进版本（曲线 PASS 且批次状态不变时留痕，使期望版本继续参与并发裁决）。
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
     * 插入压力曲线头并在同事务内取回自增 id；FAIL 曲线同样插入且不可改写。
     */
    public long insertPressureCurve(PressureCurveRow row) {
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pressure_curve (batch_key, request_id, actor_id, outcome,"
                            + " node_count, total_decline, max_segment_rate, submitted_version,"
                            + " batch_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.batchKey());
            ps.setString(2, row.requestId());
            ps.setString(3, row.actorId());
            ps.setString(4, row.outcome());
            ps.setInt(5, row.nodeCount());
            ps.setString(6, row.totalDecline());
            ps.setString(7, row.maxSegmentRate());
            ps.setLong(8, row.submittedVersion());
            ps.setLong(9, row.batchVersion());
            ps.setString(10, row.createdAt());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertPressureCurveNode(PressureCurveNodeRow row) {
        jdbc.update("INSERT INTO pressure_curve_node (curve_id, seq, point_second, pressure_kpa, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.curveId(), row.seq(), row.pointSecond(), row.pressureKpa(), row.createdAt());
    }

    public void insertPressureCurveSegment(PressureCurveSegmentRow row) {
        jdbc.update("INSERT INTO pressure_curve_segment (curve_id, seq, from_second, to_second,"
                        + " decline_kpa, rate_kpa_per_second, rate_exceeded, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.curveId(), row.seq(), row.fromSecond(), row.toSecond(),
                row.declineKpa(), row.rateKpaPerSecond(), row.rateExceeded() ? 1 : 0,
                row.createdAt());
    }

    public List<PressureCurveSegmentRow> findPressureCurveSegments(long curveId) {
        return jdbc.query("SELECT * FROM pressure_curve_segment WHERE curve_id = ? ORDER BY seq",
                PRESSURE_SEGMENT_MAPPER, curveId);
    }

    public Optional<PressureCurveRow> findPressureCurve(String batchKey) {
        return jdbc.query("SELECT * FROM pressure_curve WHERE batch_key = ?",
                        PRESSURE_CURVE_MAPPER, batchKey)
                .stream().findFirst();
    }

    public List<PressureCurveNodeRow> findPressureCurveNodes(long curveId) {
        return jdbc.query("SELECT * FROM pressure_curve_node WHERE curve_id = ? ORDER BY seq",
                PRESSURE_NODE_MAPPER, curveId);
    }

    public Optional<RequestLogRow> findRequestLog(String requestId) {
        return jdbc.query("SELECT * FROM request_log WHERE request_id = ?",
                        REQUEST_LOG_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 写入全局请求快照；仅业务成功（含合法 FAIL 曲线）的请求占用 requestId，业务失败不写入。
     */
    public void insertRequestLog(RequestLogRow row, String createdAt) {
        jdbc.update("INSERT INTO request_log (request_id, operation, resource_key, fingerprint,"
                        + " response_status, response_body, actor_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.operation(), row.resourceKey(), row.fingerprint(),
                row.responseStatus(), row.responseBody(), row.actorId(), createdAt);
    }
}