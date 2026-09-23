package com.example.starter.handoff;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 跨厂移交域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 * 移交单、冻结清单、血缘闭包快照与事件证据均只增不改，终态由整单状态列标识。
 */
@Repository
public class HandoffRepository {

    /**
     * handoff 表行记录：移交单主表，manifestKey 全局唯一。
     */
    public record HandoffRow(long id, String handoffKey, String manifestKey, String sourcePlant,
                             String targetPlant, String status, String expectedArrivalAt,
                             String shippedAt, String receivedAt, String receiver, String createdAt) {
    }

    /**
     * handoff_item 表行记录：冻结清单项，发运时补写封签与发运前快照。
     */
    public record HandoffItemRow(long id, String handoffKey, String batchKey, long expectedVersion,
                                 int seq, String sealNo, String statusBeforeShip,
                                 Long versionBeforeShip) {
    }

    /**
     * handoff_lineage_snapshot 表行记录：phase=CREATE 创建冻结，phase=RECEIVE 接收重展开。
     */
    public record LineageSnapshotRow(long id, String handoffKey, String batchKey, String ancestorKey,
                                     int depth, String ancestorStatus, String ancestorHolderPlant,
                                     String phase, int seq) {
    }

    /**
     * handoff_event 表行记录：不可变事件证据。
     */
    public record HandoffEventRow(long id, String handoffKey, String eventType, String payload,
                                  String createdAt) {
    }

    private static final RowMapper<HandoffRow> HANDOFF_MAPPER = (rs, n) -> new HandoffRow(
            rs.getLong("id"), rs.getString("handoff_key"), rs.getString("manifest_key"),
            rs.getString("source_plant"), rs.getString("target_plant"), rs.getString("status"),
            rs.getString("expected_arrival_at"), rs.getString("shipped_at"),
            rs.getString("received_at"), rs.getString("receiver"), rs.getString("created_at"));

    private static final RowMapper<HandoffItemRow> ITEM_MAPPER = (rs, n) -> new HandoffItemRow(
            rs.getLong("id"), rs.getString("handoff_key"), rs.getString("batch_key"),
            rs.getLong("expected_version"), rs.getInt("seq"), rs.getString("seal_no"),
            rs.getString("status_before_ship"),
            rs.getObject("version_before_ship") == null ? null : rs.getLong("version_before_ship"));

    private static final RowMapper<LineageSnapshotRow> LINEAGE_MAPPER = (rs, n) ->
            new LineageSnapshotRow(
            rs.getLong("id"), rs.getString("handoff_key"), rs.getString("batch_key"),
            rs.getString("ancestor_key"), rs.getInt("depth"), rs.getString("ancestor_status"),
            rs.getString("ancestor_holder_plant"), rs.getString("phase"), rs.getInt("seq"));

    private static final RowMapper<HandoffEventRow> EVENT_MAPPER = (rs, n) -> new HandoffEventRow(
            rs.getLong("id"), rs.getString("handoff_key"), rs.getString("event_type"),
            rs.getString("payload"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public HandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertHandoff(HandoffRow row) {
        jdbc.update("INSERT INTO handoff (handoff_key, manifest_key, source_plant, target_plant,"
                        + " status, expected_arrival_at, shipped_at, received_at, receiver, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.handoffKey(), row.manifestKey(), row.sourcePlant(), row.targetPlant(),
                row.status(), row.expectedArrivalAt(), row.shippedAt(), row.receivedAt(),
                row.receiver(), row.createdAt());
    }

    public Optional<HandoffRow> findHandoffByManifest(String manifestKey) {
        return jdbc.query("SELECT * FROM handoff WHERE manifest_key = ?", HANDOFF_MAPPER, manifestKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取移交单，串行化同一移交单上的发运/接收/取消并发命令。
     */
    public Optional<HandoffRow> findHandoffByManifestForUpdate(String manifestKey) {
        return jdbc.query("SELECT * FROM handoff WHERE manifest_key = ? FOR UPDATE",
                        HANDOFF_MAPPER, manifestKey)
                .stream().findFirst();
    }

    public void markShipped(String handoffKey, String shippedAt) {
        jdbc.update("UPDATE handoff SET status = ?, shipped_at = ? WHERE handoff_key = ?",
                HandoffStatus.IN_TRANSIT.name(), shippedAt, handoffKey);
    }

    public void markReceived(String handoffKey, String receivedAt, String receiver) {
        jdbc.update("UPDATE handoff SET status = ?, received_at = ?, receiver = ? WHERE handoff_key = ?",
                HandoffStatus.RECEIVED.name(), receivedAt, receiver, handoffKey);
    }

    public void markCancelled(String handoffKey) {
        jdbc.update("UPDATE handoff SET status = ? WHERE handoff_key = ?",
                HandoffStatus.CANCELLED.name(), handoffKey);
    }

    public void insertItem(HandoffItemRow row) {
        jdbc.update("INSERT INTO handoff_item (handoff_key, batch_key, expected_version, seq,"
                        + " seal_no, status_before_ship, version_before_ship)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.handoffKey(), row.batchKey(), row.expectedVersion(), row.seq(),
                row.sealNo(), row.statusBeforeShip(),
                row.versionBeforeShip() == null ? null : row.versionBeforeShip());
    }

    /**
     * 冻结清单按创建请求顺序（seq）稳定返回。
     */
    public List<HandoffItemRow> findItems(String handoffKey) {
        return jdbc.query("SELECT * FROM handoff_item WHERE handoff_key = ? ORDER BY seq",
                ITEM_MAPPER, handoffKey);
    }

    /**
     * 发运时补写逐批封签号与发运前批次状态/版本快照，供接收核对与取消原子恢复。
     */
    public void updateItemShipInfo(String handoffKey, String batchKey, String sealNo,
                                   String statusBeforeShip, long versionBeforeShip) {
        jdbc.update("UPDATE handoff_item SET seal_no = ?, status_before_ship = ?,"
                        + " version_before_ship = ? WHERE handoff_key = ? AND batch_key = ?",
                sealNo, statusBeforeShip, versionBeforeShip, handoffKey, batchKey);
    }

    /**
     * 批次是否已处于其他未终结移交单（CREATED 或 IN_TRANSIT）中；任一批次命中则整单创建失败。
     */
    public int countActiveHandoffForBatch(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_item hi JOIN handoff h ON h.handoff_key = hi.handoff_key"
                        + " WHERE hi.batch_key = ? AND h.status IN ('CREATED', 'IN_TRANSIT')",
                Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    public void insertLineageSnapshot(LineageSnapshotRow row) {
        jdbc.update("INSERT INTO handoff_lineage_snapshot (handoff_key, batch_key, ancestor_key,"
                        + " depth, ancestor_status, ancestor_holder_plant, phase, seq)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.handoffKey(), row.batchKey(), row.ancestorKey(), row.depth(),
                row.ancestorStatus(), row.ancestorHolderPlant(), row.phase(), row.seq());
    }

    /**
     * 血缘快照按阶段（CREATE 在前、RECEIVE 在后）与阶段内稳定序号返回。
     */
    public List<LineageSnapshotRow> findLineageSnapshots(String handoffKey) {
        return jdbc.query("SELECT * FROM handoff_lineage_snapshot WHERE handoff_key = ?"
                        + " ORDER BY phase, seq", LINEAGE_MAPPER, handoffKey);
    }

    public void insertEvent(HandoffEventRow row) {
        jdbc.update("INSERT INTO handoff_event (handoff_key, event_type, payload, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.handoffKey(), row.eventType(), row.payload(), row.createdAt());
    }

    /**
     * 事件证据按落定顺序（id）稳定返回，写入后不可变。
     */
    public List<HandoffEventRow> findEvents(String handoffKey) {
        return jdbc.query("SELECT * FROM handoff_event WHERE handoff_key = ? ORDER BY id",
                EVENT_MAPPER, handoffKey);
    }
}
