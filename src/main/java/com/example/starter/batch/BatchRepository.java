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
     * batch 表行记录；id 同时作为同批次事件的提交顺序依据；version 为偏差门禁的并发版本。
     */
    public record BatchRow(long id, String batchKey, String productCode, String batchNo,
                           String producedAt, String status, String createdAt,
                           BigDecimal minStorageTempC, BigDecimal maxStorageTempC, long version) {
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
     * command_log 表行记录：幂等命令快照；batchVersion 为执行时批次版本（非批次级命令为 null）。
     */
    public record CommandRow(String commandType, String commandKey, String fingerprint,
                             int responseStatus, String responseBody, Long batchVersion) {
    }

    /**
     * batch_lineage 表行记录：拆分(SPLIT)/返工(REWORK)父子关系，创建后不可改写。
     */
    public record LineageRow(long id, String parentKey, String childKey, String relationType,
                             int seq, String createdAt) {
    }

    /**
     * storage_excursion 表行记录：储运温度偏差区间与裁决状态。
     */
    public record ExcursionRow(long id, String batchKey, String excursionKey,
                               String startUtc, String endUtc,
                               BigDecimal minTempC, BigDecimal maxTempC,
                               String severity, String status, long batchVersion,
                               String confirmedBy, String confirmedAt, String createdAt) {
    }

    /**
     * excursion_adjudication 表行记录：MAJOR 偏差裁决不可变快照。
     */
    public record AdjudicationRow(long id, String batchKey, String excursionKey, String decision,
                                  String adjudicator, String role, String reworkBatchKey,
                                  String snapshotJson, String createdAt) {
    }

    /**
     * batch_disposition_risk 表行记录：处置风险只增不改。
     */
    public record DispositionRiskRow(long id, String batchKey, String excursionKey, String riskType,
                                     String detail, String createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("batch_no"), rs.getString("produced_at"),
            rs.getString("status"), rs.getString("created_at"),
            rs.getBigDecimal("min_storage_temp_c"), rs.getBigDecimal("max_storage_temp_c"),
            rs.getLong("version"));

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
            rs.getInt("response_status"), rs.getString("response_body"),
            (Long) rs.getObject("batch_version"));

    private static final RowMapper<LineageRow> LINEAGE_MAPPER = (rs, n) -> new LineageRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getString("relation_type"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<ExcursionRow> EXCURSION_MAPPER = (rs, n) -> new ExcursionRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("excursion_key"),
            rs.getString("start_utc"), rs.getString("end_utc"),
            rs.getBigDecimal("min_temp_c"), rs.getBigDecimal("max_temp_c"),
            rs.getString("severity"), rs.getString("status"), rs.getLong("batch_version"),
            rs.getString("confirmed_by"), rs.getString("confirmed_at"),
            rs.getString("created_at"));

    private static final RowMapper<AdjudicationRow> ADJUDICATION_MAPPER = (rs, n) -> new AdjudicationRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("excursion_key"),
            rs.getString("decision"), rs.getString("adjudicator"), rs.getString("role"),
            rs.getString("rework_batch_key"), rs.getString("snapshot_json"),
            rs.getString("created_at"));

    private static final RowMapper<DispositionRiskRow> DISPOSITION_RISK_MAPPER = (rs, n) ->
            new DispositionRiskRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("excursion_key"), rs.getString("risk_type"),
                    rs.getString("detail"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BatchRow> findBatch(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取批次，串行化同一批次上的检验/批准/召回/偏差事务。
     */
    public Optional<BatchRow> findBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch WHERE batch_key = ? FOR UPDATE", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertBatch(BatchRow row) {
        jdbc.update("INSERT INTO batch (batch_key, product_code, batch_no, produced_at, status, created_at,"
                        + " min_storage_temp_c, max_storage_temp_c, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.productCode(), row.batchNo(), row.producedAt(),
                row.status(), row.createdAt(),
                row.minStorageTempC(), row.maxStorageTempC(), row.version());
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
                        + " response_status, response_body, batch_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.commandType(), row.commandKey(), row.fingerprint(),
                row.responseStatus(), row.responseBody(), row.batchVersion(), createdAt);
    }

    public void insertLineage(LineageRow row) {
        jdbc.update("INSERT INTO batch_lineage (parent_key, child_key, relation_type, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.relationType(), row.seq(), row.createdAt());
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
     * 全部被直接召回（RECALLED）或拒收处置（DISPOSED）的批次业务键，二者均按召回口径拦截后代。
     */
    public List<String> findRecalledKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch WHERE status IN ('RECALLED', 'DISPOSED')",
                String.class);
    }

    /**
     * 批次版本号递增 1 并返回新值；与批次行锁配合，按事务提交顺序裁决并发偏差操作。
     */
    public long incrementVersion(String batchKey) {
        jdbc.update("UPDATE batch SET version = version + 1 WHERE batch_key = ?", batchKey);
        return findBatch(batchKey).orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey)).version();
    }

    // ---------- 储运偏差 ----------

    public void insertExcursion(ExcursionRow row) {
        jdbc.update("INSERT INTO storage_excursion (batch_key, excursion_key, start_utc, end_utc,"
                        + " min_temp_c, max_temp_c, severity, status, batch_version,"
                        + " confirmed_by, confirmed_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.excursionKey(), row.startUtc(), row.endUtc(),
                row.minTempC(), row.maxTempC(), row.severity(), row.status(), row.batchVersion(),
                row.confirmedBy(), row.confirmedAt(), row.createdAt());
    }

    public Optional<ExcursionRow> findExcursion(String batchKey, String excursionKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE batch_key = ? AND excursion_key = ?",
                        EXCURSION_MAPPER, batchKey, excursionKey)
                .stream().findFirst();
    }

    public List<ExcursionRow> findExcursions(String batchKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE batch_key = ? ORDER BY id",
                EXCURSION_MAPPER, batchKey);
    }

    public List<ExcursionRow> findOpenExcursions(String batchKey) {
        return jdbc.query("SELECT * FROM storage_excursion WHERE batch_key = ? AND status = 'OPEN' ORDER BY id",
                EXCURSION_MAPPER, batchKey);
    }

    /**
     * 未裁决的 MAJOR 偏差：门禁与放行判定依据。
     */
    public List<ExcursionRow> findOpenMajorExcursions(String batchKey) {
        return jdbc.query("SELECT * FROM storage_excursion"
                        + " WHERE batch_key = ? AND severity = 'MAJOR' AND status = 'OPEN' ORDER BY id",
                EXCURSION_MAPPER, batchKey);
    }

    /**
     * 未经质控确认的 MINOR 偏差：门禁依据。
     */
    public List<ExcursionRow> findOpenMinorExcursions(String batchKey) {
        return jdbc.query("SELECT * FROM storage_excursion"
                        + " WHERE batch_key = ? AND severity = 'MINOR' AND status = 'OPEN' ORDER BY id",
                EXCURSION_MAPPER, batchKey);
    }

    /**
     * MINOR 偏差质控确认：仅 OPEN MINOR 可更新为 CONFIRMED；MAJOR/已裁决/已确认不受影响（返回 0 行）。
     */
    public int confirmMinorExcursion(String batchKey, String excursionKey, String confirmedBy,
                                     String confirmedAt) {
        return jdbc.update("UPDATE storage_excursion SET status = 'CONFIRMED',"
                        + " confirmed_by = ?, confirmed_at = ?"
                        + " WHERE batch_key = ? AND excursion_key = ?"
                        + " AND severity = 'MINOR' AND status = 'OPEN'",
                confirmedBy, confirmedAt, batchKey, excursionKey);
    }

    /**
     * MAJOR 偏差裁决落定：仅 OPEN MAJOR 可更新为 ADJUDICATED；返回受影响行数用于并发裁决冲突判定。
     */
    public int adjudicateMajorExcursion(String batchKey, String excursionKey) {
        return jdbc.update("UPDATE storage_excursion SET status = 'ADJUDICATED'"
                        + " WHERE batch_key = ? AND excursion_key = ?"
                        + " AND severity = 'MAJOR' AND status = 'OPEN'",
                batchKey, excursionKey);
    }

    // ---------- 裁决快照 ----------

    public void insertAdjudication(AdjudicationRow row) {
        jdbc.update("INSERT INTO excursion_adjudication (batch_key, excursion_key, decision,"
                        + " adjudicator, role, rework_batch_key, snapshot_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.excursionKey(), row.decision(), row.adjudicator(), row.role(),
                row.reworkBatchKey(), row.snapshotJson(), row.createdAt());
    }

    public Optional<AdjudicationRow> findAdjudication(String batchKey, String excursionKey) {
        return jdbc.query("SELECT * FROM excursion_adjudication WHERE batch_key = ? AND excursion_key = ?",
                        ADJUDICATION_MAPPER, batchKey, excursionKey)
                .stream().findFirst();
    }

    public List<AdjudicationRow> findAdjudications(String batchKey) {
        return jdbc.query("SELECT * FROM excursion_adjudication WHERE batch_key = ? ORDER BY id",
                ADJUDICATION_MAPPER, batchKey);
    }

    // ---------- 处置风险记录 ----------

    public void insertDispositionRisk(DispositionRiskRow row) {
        jdbc.update("INSERT INTO batch_disposition_risk (batch_key, excursion_key, risk_type, detail, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.excursionKey(), row.riskType(), row.detail(), row.createdAt());
    }

    public List<DispositionRiskRow> findDispositionRisks(String batchKey) {
        return jdbc.query("SELECT * FROM batch_disposition_risk WHERE batch_key = ? ORDER BY id",
                DISPOSITION_RISK_MAPPER, batchKey);
    }

    /**
     * 某批次 REWORK 关系产生的直接返工子批业务键（最多一个）。
     */
    public Optional<String> findReworkChildKey(String parentKey) {
        return jdbc.queryForList("SELECT child_key FROM batch_lineage"
                        + " WHERE parent_key = ? AND relation_type = 'REWORK'", String.class, parentKey)
                .stream().findFirst();
    }
}
