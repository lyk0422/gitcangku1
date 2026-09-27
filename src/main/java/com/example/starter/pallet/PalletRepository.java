package com.example.starter.pallet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 托盘域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 * 装载/装运历史只增不改；在托占用通过 pallet_active_load 主键保证唯一。
 */
@Repository
public class PalletRepository {

    /**
     * pallet 表行记录。
     */
    public record PalletRow(long id, String palletKey, String status, int dividerCount,
                            int dividerVersion, String shippedBy, String shippedAt,
                            String disposedBy, String disposedAt, String disposalReason,
                            String createdAt) {
    }

    /**
     * pallet_load 表行记录：装载历史，只增不改。
     */
    public record LoadRow(long id, String palletKey, String batchKey, String category,
                          int compartment, int seq, String status, boolean reviewFlag,
                          String reviewMarkedAt, String loadedAt, String unloadedAt,
                          String shippedAt) {
    }

    /**
     * pallet_shipment 表行记录：装运时点快照，创建后不可改写。
     */
    public record ShipmentRow(long id, String palletKey, String batchKey, String category,
                              int compartment, int seq, int dividerCount, int dividerVersion,
                              String shippedAt) {
    }

    /**
     * pallet_divider_change 表行记录：隔板配置变更历史。
     */
    public record DividerChangeRow(long id, String palletKey, int oldDividerCount,
                                   int newDividerCount, int dividerVersion, String createdAt) {
    }

    private static final RowMapper<PalletRow> PALLET_MAPPER = (rs, n) -> new PalletRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("status"),
            rs.getInt("divider_count"), rs.getInt("divider_version"),
            rs.getString("shipped_by"), rs.getString("shipped_at"),
            rs.getString("disposed_by"), rs.getString("disposed_at"),
            rs.getString("disposal_reason"), rs.getString("created_at"));

    private static final RowMapper<LoadRow> LOAD_MAPPER = (rs, n) -> new LoadRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("batch_key"),
            rs.getString("category"), rs.getInt("compartment"), rs.getInt("seq"),
            rs.getString("status"), rs.getBoolean("review_flag"),
            rs.getString("review_marked_at"), rs.getString("loaded_at"),
            rs.getString("unloaded_at"), rs.getString("shipped_at"));

    private static final RowMapper<ShipmentRow> SHIPMENT_MAPPER = (rs, n) -> new ShipmentRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("batch_key"),
            rs.getString("category"), rs.getInt("compartment"), rs.getInt("seq"),
            rs.getInt("divider_count"), rs.getInt("divider_version"),
            rs.getString("shipped_at"));

    private static final RowMapper<DividerChangeRow> DIVIDER_CHANGE_MAPPER = (rs, n) -> new DividerChangeRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getInt("old_divider_count"),
            rs.getInt("new_divider_count"), rs.getInt("divider_version"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public PalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PalletRow> findPallet(String palletKey) {
        return jdbc.query("SELECT * FROM pallet WHERE pallet_key = ?", PALLET_MAPPER, palletKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取托盘，串行化同一托盘上的装载/解除/装运/隔板变更事务。
     */
    public Optional<PalletRow> findPalletForUpdate(String palletKey) {
        return jdbc.query("SELECT * FROM pallet WHERE pallet_key = ? FOR UPDATE",
                        PALLET_MAPPER, palletKey)
                .stream().findFirst();
    }

    public void insertPallet(PalletRow row) {
        jdbc.update("INSERT INTO pallet (pallet_key, status, divider_count, divider_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.palletKey(), row.status(), row.dividerCount(), row.dividerVersion(),
                row.createdAt());
    }

    public void updateDivider(String palletKey, int dividerCount, int dividerVersion) {
        jdbc.update("UPDATE pallet SET divider_count = ?, divider_version = ? WHERE pallet_key = ?",
                dividerCount, dividerVersion, palletKey);
    }

    public void markShipped(String palletKey, String shippedBy, String shippedAt) {
        jdbc.update("UPDATE pallet SET status = 'SHIPPED', shipped_by = ?, shipped_at = ?"
                        + " WHERE pallet_key = ?",
                shippedBy, shippedAt, palletKey);
    }

    public void markDisposal(String palletKey, String disposedBy, String disposedAt, String reason) {
        jdbc.update("UPDATE pallet SET status = 'QUARANTINE_DISPOSAL', disposed_by = ?,"
                        + " disposed_at = ?, disposal_reason = ? WHERE pallet_key = ?",
                disposedBy, disposedAt, reason, palletKey);
    }

    public void insertLoad(LoadRow row) {
        jdbc.update("INSERT INTO pallet_load (pallet_key, batch_key, category, compartment, seq,"
                        + " status, review_flag, loaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.palletKey(), row.batchKey(), row.category(), row.compartment(), row.seq(),
                row.status(), row.reviewFlag(), row.loadedAt());
    }

    /**
     * 托盘当前在托（ACTIVE）装载记录，按装载事件顺序。
     */
    public List<LoadRow> findActiveLoads(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_load WHERE pallet_key = ? AND status = 'ACTIVE'"
                + " ORDER BY id", LOAD_MAPPER, palletKey);
    }

    /**
     * 托盘全部装载历史（含已解除、已装运），按装载事件顺序；历史只增不改。
     */
    public List<LoadRow> findAllLoads(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_load WHERE pallet_key = ? ORDER BY id",
                LOAD_MAPPER, palletKey);
    }

    /**
     * 批次当前在托记录（全局至多一条，由 pallet_active_load 主键保证）。
     */
    public Optional<LoadRow> findActiveLoadByBatch(String batchKey) {
        return jdbc.query("SELECT l.* FROM pallet_load l"
                        + " JOIN pallet_active_load a ON a.load_id = l.id WHERE a.batch_key = ?",
                LOAD_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 刚插入的在托装载记录 id（同托盘同批次 ACTIVE 唯一）。
     */
    public long findActiveLoadId(String palletKey, String batchKey) {
        return jdbc.queryForObject("SELECT id FROM pallet_load"
                        + " WHERE pallet_key = ? AND batch_key = ? AND status = 'ACTIVE'",
                Long.class, palletKey, batchKey);
    }

    public void insertActiveLoad(String batchKey, String palletKey, long loadId) {
        jdbc.update("INSERT INTO pallet_active_load (batch_key, pallet_key, load_id)"
                + " VALUES (?, ?, ?)", batchKey, palletKey, loadId);
    }

    public void deleteActiveLoad(String batchKey) {
        jdbc.update("DELETE FROM pallet_active_load WHERE batch_key = ?", batchKey);
    }

    public void markLoadUnloaded(long loadId, String unloadedAt) {
        jdbc.update("UPDATE pallet_load SET status = 'UNLOADED', unloaded_at = ? WHERE id = ?",
                unloadedAt, loadId);
    }

    /**
     * 托盘全部在托记录置为已装运；返回受影响行数（应与在托数一致）。
     */
    public int markActiveLoadsShipped(String palletKey, String shippedAt) {
        return jdbc.update("UPDATE pallet_load SET status = 'SHIPPED', shipped_at = ?"
                + " WHERE pallet_key = ? AND status = 'ACTIVE'", shippedAt, palletKey);
    }

    /**
     * 给定批次集合中当前在托的托盘业务键（去重、按业务键排序，供召回联动按确定顺序处理）。
     */
    public List<String> findActivePalletKeysOfBatches(List<String> batchKeys) {
        if (batchKeys.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT DISTINCT pallet_key FROM pallet_active_load WHERE batch_key IN ("
                + placeholders(batchKeys.size()) + ") ORDER BY pallet_key";
        return jdbc.queryForList(sql, String.class, batchKeys.toArray());
    }

    /**
     * 将指定托盘全部未装运（ACTIVE）批次标记为待复核；已标记的不重复改写时间。
     */
    public int markReviewForPallets(List<String> palletKeys, String markedAt) {
        if (palletKeys.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE pallet_load SET review_flag = TRUE, review_marked_at = ?"
                + " WHERE status = 'ACTIVE' AND review_flag = FALSE AND pallet_key IN ("
                + placeholders(palletKeys.size()) + ")";
        Object[] args = new Object[palletKeys.size() + 1];
        args[0] = markedAt;
        for (int i = 0; i < palletKeys.size(); i++) {
            args[i + 1] = palletKeys.get(i);
        }
        return jdbc.update(sql, args);
    }

    public void insertShipment(ShipmentRow row) {
        jdbc.update("INSERT INTO pallet_shipment (pallet_key, batch_key, category, compartment,"
                        + " seq, divider_count, divider_version, shipped_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.palletKey(), row.batchKey(), row.category(), row.compartment(), row.seq(),
                row.dividerCount(), row.dividerVersion(), row.shippedAt());
    }

    /**
     * 托盘装运快照，按快照序号排序；未装运时为空。
     */
    public List<ShipmentRow> findShipment(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_shipment WHERE pallet_key = ? ORDER BY seq",
                SHIPMENT_MAPPER, palletKey);
    }

    /**
     * 批次是否已被装运放行（装运快照存在即已放行，一个批次至多装运一次）。
     */
    public boolean isBatchShipped(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pallet_shipment WHERE batch_key = ?",
                Integer.class, batchKey);
        return count != null && count > 0;
    }

    public void insertDividerChange(DividerChangeRow row) {
        jdbc.update("INSERT INTO pallet_divider_change (pallet_key, old_divider_count,"
                        + " new_divider_count, divider_version, created_at) VALUES (?, ?, ?, ?, ?)",
                row.palletKey(), row.oldDividerCount(), row.newDividerCount(),
                row.dividerVersion(), row.createdAt());
    }

    public List<DividerChangeRow> findDividerChanges(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_divider_change WHERE pallet_key = ? ORDER BY id",
                DIVIDER_CHANGE_MAPPER, palletKey);
    }

    private static String placeholders(int count) {
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < count; i++) {
            joiner.add("?");
        }
        return joiner.toString();
    }
}
