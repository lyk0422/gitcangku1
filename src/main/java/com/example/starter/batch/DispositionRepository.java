package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 召回处置单持久化层：处置单、冻结闭包批次、落账快照、批次级落账结论。
 * 所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取；路径以 "->" 连接的祖先链存储。
 */
@Repository
public class DispositionRepository {

    /**
     * disposition_order 行记录。
     */
    public record OrderRow(long id, String dispositionKey, String ancestorKey, String status,
                           int version, String submitActor, String decideActor, String holdReason,
                           String submittedAt, String decidedAt) {
    }

    /**
     * disposition_order_batch 冻结批次行记录。
     */
    public record FrozenBatchRow(long id, String dispositionKey, String batchKey, String category,
                                 long frozenVersion, String frozenStatus, String frozenPath,
                                 int seq, String createdAt) {
    }

    /**
     * disposition_snapshot 落账快照行记录。
     */
    public record SnapshotRow(long id, String dispositionKey, String batchKey, String category,
                              String previousStatus, String finalStatus, long batchVersion,
                              String path, String reason, String createdAt) {
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getLong("id"), rs.getString("disposition_key"), rs.getString("ancestor_key"),
            rs.getString("status"), rs.getInt("version"), rs.getString("submit_actor"),
            rs.getString("decide_actor"), rs.getString("hold_reason"),
            rs.getString("submitted_at"), rs.getString("decided_at"));

    private static final RowMapper<FrozenBatchRow> FROZEN_MAPPER = (rs, n) -> new FrozenBatchRow(
            rs.getLong("id"), rs.getString("disposition_key"), rs.getString("batch_key"),
            rs.getString("category"), rs.getLong("frozen_version"), rs.getString("frozen_status"),
            rs.getString("frozen_path"), rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("disposition_key"), rs.getString("batch_key"),
            rs.getString("category"), rs.getString("previous_status"), rs.getString("final_status"),
            rs.getLong("batch_version"), rs.getString("path"), rs.getString("reason"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public DispositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertOrder(OrderRow row) {
        jdbc.update("INSERT INTO disposition_order (disposition_key, ancestor_key, status, version,"
                        + " submit_actor, decide_actor, hold_reason, submitted_at, decided_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.dispositionKey(), row.ancestorKey(), row.status(), row.version(),
                row.submitActor(), row.decideActor(), row.holdReason(),
                row.submittedAt(), row.decidedAt());
    }

    public Optional<OrderRow> findOrder(String dispositionKey) {
        return jdbc.query("SELECT * FROM disposition_order WHERE disposition_key = ?",
                        ORDER_MAPPER, dispositionKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取处置单，串行化同一处置单上的确认/拒绝/取消以及并发同 requestId 重放。
     */
    public Optional<OrderRow> findOrderForUpdate(String dispositionKey) {
        return jdbc.query("SELECT * FROM disposition_order WHERE disposition_key = ? FOR UPDATE",
                        ORDER_MAPPER, dispositionKey)
                .stream().findFirst();
    }

    /**
     * 决论（确认/拒绝/取消）：置为终态、记录决论人、版本 +1、决论时间。
     */
    public void decideOrder(String dispositionKey, String status, String decideActor,
                            String decidedAt) {
        jdbc.update("UPDATE disposition_order SET status = ?, decide_actor = ?, decided_at = ?,"
                        + " version = version + 1 WHERE disposition_key = ?",
                status, decideActor, decidedAt, dispositionKey);
    }

    public void insertFrozenBatch(FrozenBatchRow row) {
        jdbc.update("INSERT INTO disposition_order_batch (disposition_key, batch_key, category,"
                        + " frozen_version, frozen_status, frozen_path, seq, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.dispositionKey(), row.batchKey(), row.category(), row.frozenVersion(),
                row.frozenStatus(), row.frozenPath(), row.seq(), row.createdAt());
    }

    public List<FrozenBatchRow> findFrozenBatches(String dispositionKey) {
        return jdbc.query("SELECT * FROM disposition_order_batch WHERE disposition_key = ? ORDER BY seq",
                FROZEN_MAPPER, dispositionKey);
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO disposition_snapshot (disposition_key, batch_key, category,"
                        + " previous_status, final_status, batch_version, path, reason, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.dispositionKey(), row.batchKey(), row.category(), row.previousStatus(),
                row.finalStatus(), row.batchVersion(), row.path(), row.reason(), row.createdAt());
    }

    public List<SnapshotRow> findSnapshots(String dispositionKey) {
        return jdbc.query("SELECT * FROM disposition_snapshot WHERE disposition_key = ? ORDER BY id",
                SNAPSHOT_MAPPER, dispositionKey);
    }

    /**
     * 写入批次级落账结论；batch_key 主键冲突表示该批次已被另一处置单落账，
     * 由服务层据此判定"不允许该流转"并整单回滚。
     */
    public void insertBatchDisposition(String batchKey, String dispositionKey, String category,
                                       String finalStatus, String createdAt) {
        jdbc.update("INSERT INTO batch_disposition (batch_key, disposition_key, category,"
                        + " final_status, created_at) VALUES (?, ?, ?, ?, ?)",
                batchKey, dispositionKey, category, finalStatus, createdAt);
    }

    public boolean existsBatchDisposition(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_disposition WHERE batch_key = ?",
                Integer.class, batchKey);
        return count != null && count > 0;
    }

    /**
     * 全部已完成处置落账的批次业务键（DESTROY/REWORK/HOLD 均计入）；这些批次保持终态或隔离，
     * 不再出现在可用批次查询中。
     */
    public List<String> findLandedKeys() {
        return jdbc.queryForList("SELECT batch_key FROM batch_disposition", String.class);
    }
}
