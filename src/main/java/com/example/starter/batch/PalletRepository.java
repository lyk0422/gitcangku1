package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 托盘域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取；
 * NULL 语义：review_marked_at/unloaded_at 为 NULL 表示未标记/未解除。
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
     * pallet_load 表行记录：装载历史，解除/装运只改状态与时间，不删除。
     */
    public record LoadRow(long id, String palletKey, String batchKey, String category,
                          int compartmentNo, int seq, String status, boolean pendingReview,
                          String reviewMarkedAt, String loadCommandKey,
                          String loadedAt, String unloadedAt) {
    }

    /**
     * batch_occupancy 表行记录：批次当前占用，主键保证并发唯一占用。
     */
    public record OccupancyRow(String batchKey, String palletKey, long loadId, String createdAt) {
    }

    /**
     * pallet_shipment 表行记录：装运快照，固化后不追溯改写。
     */
    public record ShipmentRow(long id, String palletKey, String commandKey, int dividerCount,
                              int dividerVersion, int itemCount, int categoryCount, String shippedAt) {
    }

    /**
     * pallet_disposal 表行记录。
     */
    public record DisposalRow(long id, String palletKey, String commandKey, String reason,
                              String createdAt) {
    }

    private static final RowMapper<PalletRow> PALLET_MAPPER = (rs, n) -> new PalletRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getInt("divider_count"),
            rs.getInt("divider_version"), rs.getString("status"), rs.getString("created_at"));

    private static final RowMapper<LoadRow> LOAD_MAPPER = (rs, n) -> new LoadRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("batch_key"),
            rs.getString("category"), rs.getInt("compartment_no"), rs.getInt("seq"),
            rs.getString("status"), rs.getBoolean("pending_review"),
            rs.getString("review_marked_at"), rs.getString("load_command_key"),
            rs.getString("loaded_at"), rs.getString("unloaded_at"));

    private static final RowMapper<OccupancyRow> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyRow(
            rs.getString("batch_key"), rs.getString("pallet_key"),
            rs.getLong("load_id"), rs.getString("created_at"));

    private static final RowMapper<ShipmentRow> SHIPMENT_MAPPER = (rs, n) -> new ShipmentRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("command_key"),
            rs.getInt("divider_count"), rs.getInt("divider_version"),
            rs.getInt("item_count"), rs.getInt("category_count"), rs.getString("shipped_at"));

    private static final RowMapper<DisposalRow> DISPOSAL_MAPPER = (rs, n) -> new DisposalRow(
            rs.getLong("id"), rs.getString("pallet_key"), rs.getString("command_key"),
            rs.getString("reason"), rs.getString("created_at"));

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
     * 行锁读取托盘，串行化同一托盘上的装载/解除/装运/处置/隔板修改事务。
     */
    public Optional<PalletRow> findPalletForUpdate(String palletKey) {
        return jdbc.query("SELECT * FROM pallet WHERE pallet_key = ? FOR UPDATE",
                        PALLET_MAPPER, palletKey)
                .stream().findFirst();
    }

    public void updateDividers(String palletKey, int dividerCount, int dividerVersion) {
        jdbc.update("UPDATE pallet SET divider_count = ?, divider_version = ? WHERE pallet_key = ?",
                dividerCount, dividerVersion, palletKey);
    }

    public void updatePalletStatus(String palletKey, String status) {
        jdbc.update("UPDATE pallet SET status = ? WHERE pallet_key = ?", status, palletKey);
    }

    /**
     * 写入装载记录并返回自增 id（供占用表引用）。
     */
    public long insertLoad(LoadRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pallet_load (pallet_key, batch_key, category, compartment_no, seq,"
                            + " status, pending_review, review_marked_at, load_command_key,"
                            + " loaded_at, unloaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.palletKey());
            ps.setString(2, row.batchKey());
            ps.setString(3, row.category());
            ps.setInt(4, row.compartmentNo());
            ps.setInt(5, row.seq());
            ps.setString(6, row.status());
            ps.setBoolean(7, row.pendingReview());
            ps.setNull(8, Types.VARCHAR);
            ps.setString(9, row.loadCommandKey());
            ps.setString(10, row.loadedAt());
            ps.setNull(11, Types.VARCHAR);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 当前在托装载，按事件发生顺序（id）返回。
     */
    public List<LoadRow> findCurrentLoads(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_load WHERE pallet_key = ? AND status = 'LOADED'"
                + " ORDER BY id", LOAD_MAPPER, palletKey);
    }

    public Optional<LoadRow> findCurrentLoad(String palletKey, String batchKey) {
        return jdbc.query("SELECT * FROM pallet_load WHERE pallet_key = ? AND batch_key = ?"
                        + " AND status = 'LOADED'", LOAD_MAPPER, palletKey, batchKey)
                .stream().findFirst();
    }

    /**
     * 全部装载历史（含已解除、已装运），按事件发生顺序返回；历史不删除不改写。
     */
    public List<LoadRow> findLoadHistory(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_load WHERE pallet_key = ? ORDER BY id",
                LOAD_MAPPER, palletKey);
    }

    /**
     * 解除装载：置状态与解除时间，保留历史行。
     */
    public void markUnloaded(long loadId, String unloadedAt) {
        jdbc.update("UPDATE pallet_load SET status = 'UNLOADED', unloaded_at = ? WHERE id = ?",
                unloadedAt, loadId);
    }

    /**
     * 装运放行：托盘全部在托装载固化为 SHIPPED。
     */
    public void markShipped(String palletKey) {
        jdbc.update("UPDATE pallet_load SET status = 'SHIPPED'"
                + " WHERE pallet_key = ? AND status = 'LOADED'", palletKey);
    }

    public void insertOccupancy(OccupancyRow row) {
        jdbc.update("INSERT INTO batch_occupancy (batch_key, pallet_key, load_id, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.batchKey(), row.palletKey(), row.loadId(), row.createdAt());
    }

    public Optional<OccupancyRow> findOccupancy(String batchKey) {
        return jdbc.query("SELECT * FROM batch_occupancy WHERE batch_key = ?",
                        OCCUPANCY_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void deleteOccupancy(String batchKey) {
        jdbc.update("DELETE FROM batch_occupancy WHERE batch_key = ?", batchKey);
    }

    public void insertShipment(ShipmentRow row) {
        jdbc.update("INSERT INTO pallet_shipment (pallet_key, command_key, divider_count,"
                        + " divider_version, item_count, category_count, shipped_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.palletKey(), row.commandKey(), row.dividerCount(), row.dividerVersion(),
                row.itemCount(), row.categoryCount(), row.shippedAt());
    }

    public Optional<ShipmentRow> findShipment(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_shipment WHERE pallet_key = ?",
                        SHIPMENT_MAPPER, palletKey)
                .stream().findFirst();
    }

    public void insertDisposal(DisposalRow row) {
        jdbc.update("INSERT INTO pallet_disposal (pallet_key, command_key, reason, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.palletKey(), row.commandKey(), row.reason(), row.createdAt());
    }

    public Optional<DisposalRow> findDisposal(String palletKey) {
        return jdbc.query("SELECT * FROM pallet_disposal WHERE pallet_key = ?",
                        DISPOSAL_MAPPER, palletKey)
                .stream().findFirst();
    }

    /**
     * 血缘召回命中托盘批次时，将命中托盘上全部未装运（LOADED）批次标记待复核；
     * 已标记的行不改写（保留首次标记时间）。
     */
    public void markPendingReview(List<String> batchKeys, String markedAt) {
        if (batchKeys.isEmpty()) {
            return;
        }
        String batchPlaceholders = String.join(", ", Collections.nCopies(batchKeys.size(), "?"));
        List<String> pallets = jdbc.queryForList(
                "SELECT DISTINCT pallet_key FROM pallet_load WHERE status = 'LOADED'"
                        + " AND batch_key IN (" + batchPlaceholders + ")",
                String.class, batchKeys.toArray());
        if (pallets.isEmpty()) {
            return;
        }
        String palletPlaceholders = String.join(", ", Collections.nCopies(pallets.size(), "?"));
        Object[] args = new Object[pallets.size() + 1];
        args[0] = markedAt;
        for (int i = 0; i < pallets.size(); i++) {
            args[i + 1] = pallets.get(i);
        }
        jdbc.update("UPDATE pallet_load SET pending_review = TRUE, review_marked_at = ?"
                + " WHERE status = 'LOADED' AND pending_review = FALSE"
                + " AND pallet_key IN (" + palletPlaceholders + ")", args);
    }
}
