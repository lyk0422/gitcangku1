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
     * certificate_declaration 表行记录：版本化合格证声明，版本内容固化；status 为 ACTIVE/WITHDRAWN。
     * withdrawnBy/withdrawnAt 在 ACTIVE 时为 null，撤回写入后不再改写。
     */
    public record DeclarationRow(long id, String declarationKey, String materialCode, int version,
                                 int priority, String validFrom, String validTo, String status,
                                 String publishedBy, String withdrawnBy, String withdrawnAt,
                                 String publishedAt) {
    }

    /**
     * declaration_prefix 表行记录：声明允许的批号前缀。
     */
    public record PrefixRow(long id, String declarationKey, String materialCode, String prefix, int seq) {
    }

    /**
     * batch_certificate_binding 表行记录：批次绑定的声明版本；inheritedFrom 非空表示拆分继承。
     */
    public record BindingRow(long id, String batchKey, String materialCode, String declarationKey,
                             int version, String matchedPrefix, String inheritedFrom,
                             String boundBy, String boundAt) {
    }

    /**
     * batch_merge_lineage 表行记录：合批多父关系，创建后不可改写。
     */
    public record MergeLineageRow(long id, String parentKey, String childKey, int seq, String createdAt) {
    }

    /**
     * release_certificate_snapshot 表行记录：放行时固化的声明版本证据，此后不可改写。
     */
    public record ReleaseSnapshotRow(long id, String batchKey, String materialCode,
                                     String declarationKey, int version, String matchedPrefix,
                                     String validFrom, String validTo, String releasedAt) {
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

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getString("material_code"),
            rs.getInt("version"), rs.getInt("priority"), rs.getString("valid_from"),
            rs.getString("valid_to"), rs.getString("status"), rs.getString("published_by"),
            rs.getString("withdrawn_by"), rs.getString("withdrawn_at"), rs.getString("published_at"));

    private static final RowMapper<PrefixRow> PREFIX_MAPPER = (rs, n) -> new PrefixRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getString("material_code"),
            rs.getString("prefix"), rs.getInt("seq"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("material_code"),
            rs.getString("declaration_key"), rs.getInt("version"), rs.getString("matched_prefix"),
            rs.getString("inherited_from"), rs.getString("bound_by"), rs.getString("bound_at"));

    private static final RowMapper<MergeLineageRow> MERGE_LINEAGE_MAPPER = (rs, n) ->
            new MergeLineageRow(rs.getLong("id"), rs.getString("parent_key"),
                    rs.getString("child_key"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<ReleaseSnapshotRow> RELEASE_SNAPSHOT_MAPPER = (rs, n) ->
            new ReleaseSnapshotRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("material_code"), rs.getString("declaration_key"),
                    rs.getInt("version"), rs.getString("matched_prefix"), rs.getString("valid_from"),
                    rs.getString("valid_to"), rs.getString("released_at"));

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

    // ---------- 合格证声明 ----------

    public Optional<DeclarationRow> findDeclaration(String declarationKey) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE declaration_key = ?",
                        DECLARATION_MAPPER, declarationKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取声明，串行化同一声明上的撤回/绑定/放行校验。
     */
    public Optional<DeclarationRow> findDeclarationForUpdate(String declarationKey) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE declaration_key = ? FOR UPDATE",
                        DECLARATION_MAPPER, declarationKey)
                .stream().findFirst();
    }

    /**
     * 某物料的全部声明，按版本升序（id 与版本同序）。
     */
    public List<DeclarationRow> findDeclarationsByMaterial(String materialCode) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE material_code = ? ORDER BY version",
                DECLARATION_MAPPER, materialCode);
    }

    /**
     * 某物料当前最大版本号；无声明时返回 0。
     */
    public int maxDeclarationVersion(String materialCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM certificate_declaration WHERE material_code = ?",
                Integer.class, materialCode);
        return max == null ? 0 : max;
    }

    public void insertDeclaration(DeclarationRow row) {
        jdbc.update("INSERT INTO certificate_declaration (declaration_key, material_code, version,"
                        + " priority, valid_from, valid_to, status, published_by, withdrawn_by,"
                        + " withdrawn_at, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.declarationKey(), row.materialCode(), row.version(), row.priority(),
                row.validFrom(), row.validTo(), row.status(), row.publishedBy(),
                row.withdrawnBy(), row.withdrawnAt(), row.publishedAt());
    }

    /**
     * 撤回声明：仅置 WITHDRAWN 并记录撤回人与时间；版本内容与历史放行快照不受影响。
     */
    public void markDeclarationWithdrawn(String declarationKey, String withdrawnBy, String withdrawnAt) {
        jdbc.update("UPDATE certificate_declaration SET status = 'WITHDRAWN', withdrawn_by = ?,"
                        + " withdrawn_at = ? WHERE declaration_key = ?",
                withdrawnBy, withdrawnAt, declarationKey);
    }

    public void insertPrefix(PrefixRow row) {
        jdbc.update("INSERT INTO declaration_prefix (declaration_key, material_code, prefix, seq)"
                        + " VALUES (?, ?, ?, ?)",
                row.declarationKey(), row.materialCode(), row.prefix(), row.seq());
    }

    /**
     * 某声明的全部允许前缀，按请求顺序 seq 升序（稳定排序）。
     */
    public List<String> findPrefixes(String declarationKey) {
        return jdbc.queryForList(
                "SELECT prefix FROM declaration_prefix WHERE declaration_key = ? ORDER BY seq",
                String.class, declarationKey);
    }

    /**
     * 某物料指定优先级、ACTIVE 声明的前缀分组（每个声明一组），用于发布时同优先级重叠校验。
     */
    public List<List<String>> findActivePrefixGroupsByPriority(String materialCode, int priority) {
        List<PrefixRow> rows = jdbc.query(
                "SELECT dp.* FROM declaration_prefix dp"
                        + " JOIN certificate_declaration cd ON cd.declaration_key = dp.declaration_key"
                        + " WHERE dp.material_code = ? AND cd.priority = ? AND cd.status = 'ACTIVE'"
                        + " ORDER BY dp.id",
                PREFIX_MAPPER, materialCode, priority);
        List<List<String>> groups = new java.util.ArrayList<>();
        String current = null;
        List<String> group = null;
        for (PrefixRow row : rows) {
            if (!row.declarationKey().equals(current)) {
                group = new java.util.ArrayList<>();
                groups.add(group);
                current = row.declarationKey();
            }
            group.add(row.prefix());
        }
        return groups;
    }

    // ---------- 批次绑定 ----------

    public List<BindingRow> findBindings(String batchKey) {
        return jdbc.query("SELECT * FROM batch_certificate_binding WHERE batch_key = ? ORDER BY id",
                BINDING_MAPPER, batchKey);
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO batch_certificate_binding (batch_key, material_code, declaration_key,"
                        + " version, matched_prefix, inherited_from, bound_by, bound_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.materialCode(), row.declarationKey(), row.version(),
                row.matchedPrefix(), row.inheritedFrom(), row.boundBy(), row.boundAt());
    }

    public int deleteBindings(String batchKey) {
        return jdbc.update("DELETE FROM batch_certificate_binding WHERE batch_key = ?", batchKey);
    }

    // ---------- 合批血缘 ----------

    public void insertMergeLineage(MergeLineageRow row) {
        jdbc.update("INSERT INTO batch_merge_lineage (parent_key, child_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.seq(), row.createdAt());
    }

    public List<MergeLineageRow> findMergeLineageByChild(String childKey) {
        return jdbc.query("SELECT * FROM batch_merge_lineage WHERE child_key = ? ORDER BY seq",
                MERGE_LINEAGE_MAPPER, childKey);
    }

    public List<MergeLineageRow> findAllMergeLineage() {
        return jdbc.query("SELECT * FROM batch_merge_lineage ORDER BY id", MERGE_LINEAGE_MAPPER);
    }

    // ---------- 放行快照 ----------

    public Optional<ReleaseSnapshotRow> findReleaseSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_certificate_snapshot WHERE batch_key = ?",
                        RELEASE_SNAPSHOT_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 批次已固化的全部放行快照（普通批次一行；合批产物按物料多行）。
     */
    public List<ReleaseSnapshotRow> findReleaseSnapshots(String batchKey) {
        return jdbc.query("SELECT * FROM release_certificate_snapshot WHERE batch_key = ?"
                        + " ORDER BY material_code", RELEASE_SNAPSHOT_MAPPER, batchKey);
    }

    public void insertReleaseSnapshot(ReleaseSnapshotRow row) {
        jdbc.update("INSERT INTO release_certificate_snapshot (batch_key, material_code,"
                        + " declaration_key, version, matched_prefix, valid_from, valid_to, released_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.materialCode(), row.declarationKey(), row.version(),
                row.matchedPrefix(), row.validFrom(), row.validTo(), row.releasedAt());
    }
}
