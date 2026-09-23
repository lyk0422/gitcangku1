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
     * holdingPlant 为当前持有厂，batchVersion 为移交版本号（接收成功时加一）。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String holdingPlant, int batchVersion,
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

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("holding_plant"), rs.getInt("batch_version"),
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
                        + " holding_plant, batch_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.holdingPlant(), row.batchVersion(), row.createdAt());
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
     * handoff 表行记录：跨厂移交单。时间列为 ISO-8601 UTC 字符串，未发生的事件为 null。
     */
    public record HandoffRow(long id, String manifestKey, String sourcePlant, String targetPlant,
                             String expectedArrivalAt, String status, String receiver,
                             String createdAt, String shippedAt, String receivedAt,
                             String cancelledAt) {
    }

    /**
     * handoff_item 表行记录：移交清单明细及发运/接收快照。
     */
    public record HandoffItemRow(long id, String manifestKey, String batchKey, int seq,
                                 int expectedVersion, String lineageSnapshot, String preStatus,
                                 String sealNo, Integer receivedVersion) {
    }

    private static final RowMapper<HandoffRow> HANDOFF_MAPPER = (rs, n) -> new HandoffRow(
            rs.getLong("id"), rs.getString("manifest_key"), rs.getString("source_plant"),
            rs.getString("target_plant"), rs.getString("expected_arrival_at"),
            rs.getString("status"), rs.getString("receiver"), rs.getString("created_at"),
            rs.getString("shipped_at"), rs.getString("received_at"), rs.getString("cancelled_at"));

    private static final RowMapper<HandoffItemRow> HANDOFF_ITEM_MAPPER = (rs, n) ->
            new HandoffItemRow(rs.getLong("id"), rs.getString("manifest_key"),
                    rs.getString("batch_key"), rs.getInt("seq"), rs.getInt("expected_version"),
                    rs.getString("lineage_snapshot"), rs.getString("pre_status"),
                    rs.getString("seal_no"),
                    rs.getObject("received_version") == null ? null
                            : rs.getInt("received_version"));

    public Optional<HandoffRow> findHandoff(String manifestKey) {
        return jdbc.query("SELECT * FROM handoff WHERE manifest_key = ?", HANDOFF_MAPPER, manifestKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取移交单，串行化同一移交单上的发运/接收/取消事务。
     */
    public Optional<HandoffRow> findHandoffForUpdate(String manifestKey) {
        return jdbc.query("SELECT * FROM handoff WHERE manifest_key = ? FOR UPDATE",
                        HANDOFF_MAPPER, manifestKey)
                .stream().findFirst();
    }

    public void insertHandoff(HandoffRow row) {
        jdbc.update("INSERT INTO handoff (manifest_key, source_plant, target_plant,"
                        + " expected_arrival_at, status, receiver, created_at, shipped_at,"
                        + " received_at, cancelled_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.manifestKey(), row.sourcePlant(), row.targetPlant(), row.expectedArrivalAt(),
                row.status(), row.receiver(), row.createdAt(), row.shippedAt(), row.receivedAt(),
                row.cancelledAt());
    }

    public void updateHandoffShipped(String manifestKey, String shippedAt) {
        jdbc.update("UPDATE handoff SET status = 'SHIPPED', shipped_at = ? WHERE manifest_key = ?",
                shippedAt, manifestKey);
    }

    public void updateHandoffReceived(String manifestKey, String receiver, String receivedAt) {
        jdbc.update("UPDATE handoff SET status = 'RECEIVED', receiver = ?, received_at = ?"
                + " WHERE manifest_key = ?", receiver, receivedAt, manifestKey);
    }

    public void updateHandoffCancelled(String manifestKey, String cancelledAt) {
        jdbc.update("UPDATE handoff SET status = 'CANCELLED', cancelled_at = ? WHERE manifest_key = ?",
                cancelledAt, manifestKey);
    }

    public void insertHandoffItem(HandoffItemRow row) {
        jdbc.update("INSERT INTO handoff_item (manifest_key, batch_key, seq, expected_version,"
                        + " lineage_snapshot, pre_status, seal_no, received_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.manifestKey(), row.batchKey(), row.seq(), row.expectedVersion(),
                row.lineageSnapshot(), row.preStatus(), row.sealNo(), row.receivedVersion());
    }

    /**
     * 移交清单明细，按冻结顺序（seq，即 batchKey 字典序）稳定排序。
     */
    public List<HandoffItemRow> findHandoffItems(String manifestKey) {
        return jdbc.query("SELECT * FROM handoff_item WHERE manifest_key = ? ORDER BY seq",
                HANDOFF_ITEM_MAPPER, manifestKey);
    }

    /**
     * 发运时回写明细快照：发运前状态与封签号。
     */
    public void updateItemShipped(String manifestKey, String batchKey, String preStatus,
                                  String sealNo) {
        jdbc.update("UPDATE handoff_item SET pre_status = ?, seal_no = ?"
                + " WHERE manifest_key = ? AND batch_key = ?", preStatus, sealNo, manifestKey,
                batchKey);
    }

    /**
     * 接收成功时回写明细快照：接收后的批次新版本号。
     */
    public void updateItemReceived(String manifestKey, String batchKey, int receivedVersion) {
        jdbc.update("UPDATE handoff_item SET received_version = ?"
                + " WHERE manifest_key = ? AND batch_key = ?", receivedVersion, manifestKey,
                batchKey);
    }

    /**
     * 给定批次中，当前处于进行中移交单（CREATED/SHIPPED）的批次业务键。
     */
    public List<String> findBatchesInActiveHandoff(List<String> batchKeys) {
        if (batchKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", batchKeys.stream().map(k -> "?").toList());
        return jdbc.queryForList("SELECT i.batch_key FROM handoff_item i"
                        + " JOIN handoff h ON h.manifest_key = i.manifest_key"
                        + " WHERE h.status IN ('CREATED', 'SHIPPED') AND i.batch_key IN ("
                        + placeholders + ")",
                String.class, batchKeys.toArray());
    }

    /**
     * 接收成功时一次性切换批次：状态、持有厂与版本号。
     */
    public void updateBatchReceived(String batchKey, String status, String holdingPlant,
                                    int batchVersion) {
        jdbc.update("UPDATE batch SET status = ?, holding_plant = ?, batch_version = ?"
                + " WHERE batch_key = ?", status, holdingPlant, batchVersion, batchKey);
    }
}
