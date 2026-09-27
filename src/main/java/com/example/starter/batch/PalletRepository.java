package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 托盘域持久化层：全部 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 *
 * <p>并发约束依赖两张唯一索引：
 * {@code uk_loading_active_batch (batch_key, active)} 保证同一批次至多一个 ACTIVE 装载；
 * {@code uk_loading_active_slot (pallet_key, slot_no, active)} 保证同托盘分隔位不重复；
 * 已解除历史行 active 为 NULL，多 NULL 在唯一索引中互不冲突。
 */
@Repository
public class PalletRepository {

    /**
     * pallet 表行记录。
     */
    public record PalletRow(long id, String palletKey, int dividerCount, int dividerVersion,
                           String status, String createdAt) {
    }

    /**
     * pallet_loading 表行记录；active 为 1 表示当前有效，null 表示已解除历史行。
     */
    public record LoadingRow(long id, String palletKey, String batchKey, String segregationCategory,
                             int slotNo, int dividerVersion, int seq, Integer active,
                             String loadedAt, String unloadedAt) {
    }

    /**
     * pallet_shipment 装运快照行记录，装运后不随后续隔板修改改写。
     */
    public record ShipmentRow(long id, String palletKey, String batchKey, String segregationCategory,
                              int slotNo, int dividerVersion, int seq, String shippedAt) {
    }

    /**
     * pallet_event 事件历史行记录，只增不改。
     */
    public record EventRow(long id, String palletKey, String eventType, String detail, String createdAt) {
    }

    /**
     * pallet_review_flag 召回联动待复核标记行记录。
     */
    public record ReviewFlagRow(long id, String palletKey, String batchKey,
                                String sourceRecalledKey, String createdAt) {
    }

    private static final RowMapper<PalletRow> PALLET_MAPPER = (rs, n) -> new PalletRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getInt("divider_count"),
            rs.getInt("divider_version"), rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<LoadingRow> LOADING_MAPPER = (rs, n) -> new LoadingRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("batch_key"),
            rs.getString("segregation_category"), rs.getInt("slot_no"),
            rs.getInt("divider_version"), rs.getInt("seq"),
            (Integer) rs.getObject("active"),
            rs.getString("loaded_at"), rs.getString("unloaded_at"));

    private static final RowMapper<ShipmentRow> SHIPMENT_MAPPER = (rs, n) -> new ShipmentRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("batch_key"),
            rs.getString("segregation_category"), rs.getInt("slot_no"),
            rs.getInt("divider_version"), rs.getInt("seq"), rs.getString("shipped_at"));

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, n) -> new EventRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("event_type"),
            rs.getString("detail"), rs.getString("created_at"));

    private static final RowMapper<ReviewFlagRow> REVIEW_MAPPER = (rs, n) -> new ReviewFlagRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("batch_key"),
            rs.getString("source_recalled_key"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public PalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPallet(PalletRow row) {
        jdbc.update("INSERT INTO pallet (pallet_key, divider_count, divider_version, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.palletKey(), row.dividerCount(), row.dividerVersion(), row.status(), row.createdAt());
    }

    public Optional<PalletRow> findPallet(String palletKey) {
        return jdbc.query("SELECT * FROM pallet WHERE pallet_key = ?", PALLET_MAPPER, palletKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取托盘，串行化同一托盘上的装载/解除/隔板配置/装运/隔离处置。
     */
    public Optional<PalletRow> findPalletForUpdate(String palletKey) {
        return jdbc.query("SELECT * FROM pallet WHERE pallet_key = ? FOR UPDATE", PALLET_MAPPER, palletKey)
                .stream().findFirst();
    }

    public List<PalletRow> findAllPallets() {
        return jdbc.query("SELECT * FROM pallet ORDER BY id", PALLET_MAPPER);
    }

    /**
     * 按主键顺序锁定多只托盘，保证锁顺序确定（调用方须先排序 palletKey）。
     */
    public void lockPalletsForUpdate(List<String> palletKeys) {
        if (palletKeys.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", palletKeys.stream().map(k -> "?").toList());
        jdbc.queryForList("SELECT pallet_key FROM pallet WHERE pallet_key IN (" + placeholders
                + ") ORDER BY pallet_key FOR UPDATE", String.class, palletKeys.toArray());
    }

    public void updateDividers(String palletKey, int dividerCount, int dividerVersion) {
        jdbc.update("UPDATE pallet SET divider_count = ?, divider_version = ? WHERE pallet_key = ?",
                dividerCount, dividerVersion, palletKey);
    }

    public void updateStatus(String palletKey, String status) {
        jdbc.update("UPDATE pallet SET status = ? WHERE pallet_key = ?", status, palletKey);
    }

    public void insertLoading(LoadingRow row) {
        jdbc.update("INSERT INTO pallet_loading (pallet_key, batch_key, segregation_category, slot_no,"
                        + " divider_version, seq, active, loaded_at, unloaded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.palletKey(), row.batchKey(), row.segregationCategory(), row.slotNo(),
                row.dividerVersion(), row.seq(), row.active(), row.loadedAt(), row.unloadedAt());
    }

    /**
     * 批次当前有效装载（active=1）；命中唯一索引，至多一条。
     */
    public Optional<LoadingRow> findActiveLoadingByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM pallet_loading WHERE batch_key = ? AND active = 1",
                        LOADING_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 指定批次集合的全部当前 ACTIVE 装载（跨托盘），用于召回联动与整批校验。
     */
    public List<LoadingRow> findActiveLoadingsByBatches(List<String> batchKeys) {
        if (batchKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", batchKeys.stream().map(k -> "?").toList());
        return jdbc.query("SELECT * FROM pallet_loading WHERE active = 1 AND batch_key IN ("
                + placeholders + ") ORDER BY id", LOADING_MAPPER, batchKeys.toArray());
    }

    /**
     * 托盘当前 ACTIVE 装载，按装载落定顺序（id）排列，即各装载请求规范顺序的拼接。
     */
    public List<LoadingRow> findActiveLoadings(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_loading WHERE pallet_key = ? AND active = 1 ORDER BY id",
                LOADING_MAPPER, palletKey);
    }

    /**
     * 托盘全部装载行（含已解除历史），按 id 排列。
     */
    public List<LoadingRow> findAllLoadings(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_loading WHERE pallet_key = ? ORDER BY id",
                LOADING_MAPPER, palletKey);
    }

    /**
     * 解除装载：active 置 NULL（释放唯一索引占用与分隔位），写解除时间；返回受影响行数。
     */
    public int deactivateLoading(long id, String unloadedAt) {
        return jdbc.update("UPDATE pallet_loading SET active = NULL, unloaded_at = ?"
                + " WHERE id = ? AND active = 1", unloadedAt, id);
    }

    public void insertShipment(ShipmentRow row) {
        jdbc.update("INSERT INTO pallet_shipment (pallet_key, batch_key, segregation_category, slot_no,"
                        + " divider_version, seq, shipped_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.palletKey(), row.batchKey(), row.segregationCategory(), row.slotNo(),
                row.dividerVersion(), row.seq(), row.shippedAt());
    }

    public List<ShipmentRow> findShipment(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_shipment WHERE pallet_key = ? ORDER BY id",
                SHIPMENT_MAPPER, palletKey);
    }

    public void insertEvent(EventRow row) {
        jdbc.update("INSERT INTO pallet_event (pallet_key, event_type, detail, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.palletKey(), row.eventType(), row.detail(), row.createdAt());
    }

    public List<EventRow> findEvents(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_event WHERE pallet_key = ? ORDER BY id",
                EVENT_MAPPER, palletKey);
    }

    /**
     * 幂等写入待复核标记：同一批次至多一条（并发下靠 NOT EXISTS 守卫 + uk_review_batch 兜底）。
     */
    public int insertReviewFlagIfAbsent(String palletKey, String batchKey,
                                        String sourceRecalledKey, String createdAt) {
        return jdbc.update("INSERT INTO pallet_review_flag (pallet_key, batch_key, source_recalled_key,"
                        + " created_at) SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM"
                        + " pallet_review_flag WHERE batch_key = ?)",
                palletKey, batchKey, sourceRecalledKey, createdAt, batchKey);
    }

    public List<ReviewFlagRow> findReviewFlags(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_review_flag WHERE pallet_key = ? ORDER BY id",
                REVIEW_MAPPER, palletKey);
    }

    /**
     * 批次解除装载时释放其待复核活标记；REVIEW_FLAGGED 事件作为历史证据保留，不删除。
     */
    public int deleteReviewFlag(String batchKey) {
        return jdbc.update("DELETE FROM pallet_review_flag WHERE batch_key = ?", batchKey);
    }

    public boolean existsReviewFlagForPallet(String palletKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pallet_review_flag WHERE pallet_key = ?",
                Integer.class, palletKey);
        return count != null && count > 0;
    }

    /**
     * 读取幂等命令快照（command_log 与批次域共用）。
     */
    public Optional<CommandView> findCommand(String commandType, String commandKey) {
        return jdbc.query("SELECT * FROM command_log WHERE command_type = ? AND command_key = ?",
                        (rs, n) -> new CommandView(rs.getString("fingerprint"),
                                rs.getInt("response_status"), rs.getString("response_body")),
                        commandType, commandKey)
                .stream().findFirst();
    }

    /**
     * 命令快照视图：参数指纹 + 首次响应状态码与 JSON。
     */
    public record CommandView(String fingerprint, int responseStatus, String responseBody) {
    }

    public void insertCommand(String commandType, String commandKey, String fingerprint,
                              int responseStatus, String responseBody, String createdAt) {
        jdbc.update("INSERT INTO command_log (command_type, command_key, fingerprint,"
                        + " response_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                commandType, commandKey, fingerprint, responseStatus, responseBody, createdAt);
    }
}
