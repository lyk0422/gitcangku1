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
     * batch_lineage 表行记录：拆分父子关系，创建后不可改写。
     */
    public record LineageRow(long id, String parentKey, String childKey, int seq, String createdAt) {
    }

    /**
     * packing_plan 表行记录：批次包装计划与连续标签号段，登记后不可修改。
     */
    public record PackingPlanRow(String batchKey, long plannedQuantity, long labelRangeStart,
                                 long labelRangeEnd, String createdAt) {
    }

    /**
     * box_seal_event 表行记录：只增不改的封箱历史证据；作废只置状态/原因/作废时间。
     */
    public record SealEventRow(long id, String batchKey, String sealKey, int version, long labelNo,
                               long quantity, String status, String voidReason,
                               String sealedAt, String voidedAt) {
    }

    /**
     * box_seal_active 表行记录：活跃封箱占用（数量与标签），作废时删除。
     */
    public record ActiveSealRow(String batchKey, long labelNo, String sealKey, int version,
                                long quantity, String sealedAt) {
    }

    /**
     * label_binding 表行记录：标签号与批次的永久绑定（作废也不解除）。
     */
    public record LabelBindingRow(long labelNo, String batchKey, String firstSealedAt) {
    }

    /**
     * release_snapshot 表行记录：放行时刻固化的计划/实际数量与标签排序摘要。
     */
    public record ReleaseSnapshotRow(String batchKey, long plannedQuantity, long sealedQuantity,
                                     long usedLabelCount, long sealCount, String usedLabels,
                                     String createdAt) {
    }

    /**
     * release_snapshot_seal 表行记录：放行快照内固化的单个封箱版本。
     */
    public record SnapshotSealRow(long id, String batchKey, String sealKey, int version, long labelNo,
                                  long quantity, String sealedAt, int seq) {
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

    private static final RowMapper<LineageRow> LINEAGE_MAPPER = (rs, n) -> new LineageRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<PackingPlanRow> PACKING_PLAN_MAPPER = (rs, n) -> new PackingPlanRow(
            rs.getString("batch_key"), rs.getLong("planned_quantity"),
            rs.getLong("label_range_start"), rs.getLong("label_range_end"),
            rs.getString("created_at"));

    private static final RowMapper<SealEventRow> SEAL_EVENT_MAPPER = (rs, n) -> new SealEventRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("seal_key"),
            rs.getInt("version"), rs.getLong("label_no"), rs.getLong("quantity"),
            rs.getString("status"), rs.getString("void_reason"),
            rs.getString("sealed_at"), rs.getString("voided_at"));

    private static final RowMapper<ActiveSealRow> ACTIVE_SEAL_MAPPER = (rs, n) -> new ActiveSealRow(
            rs.getString("batch_key"), rs.getLong("label_no"), rs.getString("seal_key"),
            rs.getInt("version"), rs.getLong("quantity"), rs.getString("sealed_at"));

    private static final RowMapper<LabelBindingRow> LABEL_BINDING_MAPPER = (rs, n) ->
            new LabelBindingRow(rs.getLong("label_no"), rs.getString("batch_key"),
                    rs.getString("first_sealed_at"));

    private static final RowMapper<ReleaseSnapshotRow> RELEASE_SNAPSHOT_MAPPER = (rs, n) ->
            new ReleaseSnapshotRow(rs.getString("batch_key"), rs.getLong("planned_quantity"),
                    rs.getLong("sealed_quantity"), rs.getLong("used_label_count"),
                    rs.getLong("seal_count"), rs.getString("used_labels"),
                    rs.getString("created_at"));

    private static final RowMapper<SnapshotSealRow> SNAPSHOT_SEAL_MAPPER = (rs, n) ->
            new SnapshotSealRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("seal_key"), rs.getInt("version"), rs.getLong("label_no"),
                    rs.getLong("quantity"), rs.getString("sealed_at"), rs.getInt("seq"));

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

    // ---------- 包装计划与标签核销 ----------

    public Optional<PackingPlanRow> findPackingPlan(String batchKey) {
        return jdbc.query("SELECT * FROM packing_plan WHERE batch_key = ?",
                        PACKING_PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertPackingPlan(PackingPlanRow row) {
        jdbc.update("INSERT INTO packing_plan (batch_key, planned_quantity, label_range_start,"
                        + " label_range_end, created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.labelRangeStart(),
                row.labelRangeEnd(), row.createdAt());
    }

    /**
     * 某批次全部封箱事件（含已作废与历史版本），按自增 id 即提交顺序返回。
     */
    public List<SealEventRow> findSealEvents(String batchKey) {
        return jdbc.query("SELECT * FROM box_seal_event WHERE batch_key = ? ORDER BY id",
                SEAL_EVENT_MAPPER, batchKey);
    }

    /**
     * 某 sealKey 的最新版本事件（作废重封后版本递增）；不存在返回空。
     */
    public Optional<SealEventRow> findLatestSealEvent(String batchKey, String sealKey) {
        return jdbc.query("SELECT * FROM box_seal_event WHERE batch_key = ? AND seal_key = ?"
                        + " ORDER BY version DESC LIMIT 1",
                        SEAL_EVENT_MAPPER, batchKey, sealKey)
                .stream().findFirst();
    }

    public void insertSealEvent(SealEventRow row) {
        jdbc.update("INSERT INTO box_seal_event (batch_key, seal_key, version, label_no, quantity,"
                        + " status, void_reason, sealed_at, voided_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.sealKey(), row.version(), row.labelNo(), row.quantity(),
                row.status(), row.voidReason(), row.sealedAt(), row.voidedAt());
    }

    /**
     * 作废封箱事件：仅置状态、原因与作废时间；封箱数量、标签号、封箱时间等原始字段不被覆盖。
     */
    public int voidSealEvent(String batchKey, String sealKey, int version,
                             String reason, String voidedAt) {
        return jdbc.update("UPDATE box_seal_event SET status = 'VOIDED', void_reason = ?,"
                        + " voided_at = ? WHERE batch_key = ? AND seal_key = ? AND version = ?"
                        + " AND status = 'SEALED'",
                reason, voidedAt, batchKey, sealKey, version);
    }

    /**
     * 某批次全部活跃封箱占用，按标签号升序返回。
     */
    public List<ActiveSealRow> findActiveSeals(String batchKey) {
        return jdbc.query("SELECT * FROM box_seal_active WHERE batch_key = ? ORDER BY label_no",
                ACTIVE_SEAL_MAPPER, batchKey);
    }

    public Optional<ActiveSealRow> findActiveSealByKey(String batchKey, String sealKey) {
        return jdbc.query("SELECT * FROM box_seal_active WHERE batch_key = ? AND seal_key = ?",
                        ACTIVE_SEAL_MAPPER, batchKey, sealKey)
                .stream().findFirst();
    }

    public void insertActiveSeal(ActiveSealRow row) {
        jdbc.update("INSERT INTO box_seal_active (batch_key, label_no, seal_key, version, quantity,"
                        + " sealed_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.labelNo(), row.sealKey(), row.version(),
                row.quantity(), row.sealedAt());
    }

    public int deleteActiveSeal(String batchKey, String sealKey) {
        return jdbc.update("DELETE FROM box_seal_active WHERE batch_key = ? AND seal_key = ?",
                batchKey, sealKey);
    }

    public long sumActiveQuantity(String batchKey) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM box_seal_active WHERE batch_key = ?",
                Long.class, batchKey);
        return sum == null ? 0L : sum;
    }

    public Optional<LabelBindingRow> findLabelBinding(long labelNo) {
        return jdbc.query("SELECT * FROM label_binding WHERE label_no = ?",
                        LABEL_BINDING_MAPPER, labelNo)
                .stream().findFirst();
    }

    public void insertLabelBinding(LabelBindingRow row) {
        jdbc.update("INSERT INTO label_binding (label_no, batch_key, first_sealed_at)"
                        + " VALUES (?, ?, ?)",
                row.labelNo(), row.batchKey(), row.firstSealedAt());
    }

    public Optional<ReleaseSnapshotRow> findReleaseSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                        RELEASE_SNAPSHOT_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertReleaseSnapshot(ReleaseSnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, planned_quantity, sealed_quantity,"
                        + " used_label_count, seal_count, used_labels, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.sealedQuantity(), row.usedLabelCount(),
                row.sealCount(), row.usedLabels(), row.createdAt());
    }

    public void insertSnapshotSeal(String batchKey, String sealKey, int version, long labelNo,
                                   long quantity, String sealedAt, int seq) {
        jdbc.update("INSERT INTO release_snapshot_seal (batch_key, seal_key, version, label_no,"
                        + " quantity, sealed_at, seq) VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchKey, sealKey, version, labelNo, quantity, sealedAt, seq);
    }

    /**
     * 快照内固化的封箱版本，按标签号升序序号返回。
     */
    public List<SnapshotSealRow> findSnapshotSeals(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot_seal WHERE batch_key = ? ORDER BY seq",
                SNAPSHOT_SEAL_MAPPER, batchKey);
    }
}
