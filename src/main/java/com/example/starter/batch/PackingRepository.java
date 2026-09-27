package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 批次标签核销持久化层：包装计划、封箱版本、有效标签占用与放行快照。
 * 所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取；封箱版本只增不改，作废仅翻转状态。
 */
@Repository
public class PackingRepository {

    /**
     * packaging_plan 表行记录；一个批次至多一份，登记后不可修改。
     */
    public record PlanRow(long id, String batchKey, int plannedQuantity, long labelStart,
                          long labelEnd, String createdAt) {
    }

    /**
     * packing_record 表行记录（封箱版本）。status=ACTIVE 时 void* 三字段为 null；
     * status=VOID 时三字段非空，数量与标签已释放。
     */
    public record PackingRow(long id, String batchKey, String commandKey, long labelNo,
                             int quantity, String packedBy, String status, String packedAt,
                             String voidedBy, String voidReason, String voidedAt) {
    }

    /**
     * label_occupancy 表行记录：标签号全局唯一的有效占用。
     */
    public record OccupancyRow(long labelNo, String batchKey, long packingId, String occupiedAt) {
    }

    /**
     * release_snapshot 表行记录：放行时固化，只插不改。
     */
    public record SnapshotRow(long id, String batchKey, int plannedQuantity, int packedQuantity,
                              int usedLabelCount, String labelSummary, String recordsJson,
                              String createdAt) {
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getLong("label_start"), rs.getLong("label_end"), rs.getString("created_at"));

    private static final RowMapper<PackingRow> PACKING_MAPPER = (rs, n) -> new PackingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("command_key"),
            rs.getLong("label_no"), rs.getInt("quantity"), rs.getString("packed_by"),
            rs.getString("status"), rs.getString("packed_at"),
            rs.getString("voided_by"), rs.getString("void_reason"), rs.getString("voided_at"));

    private static final RowMapper<OccupancyRow> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyRow(
            rs.getLong("label_no"), rs.getString("batch_key"),
            rs.getLong("packing_id"), rs.getString("occupied_at"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getInt("packed_quantity"), rs.getInt("used_label_count"),
            rs.getString("label_summary"), rs.getString("records_json"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public PackingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PlanRow> findPlan(String batchKey) {
        return jdbc.query("SELECT * FROM packaging_plan WHERE batch_key = ?", PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertPlan(PlanRow row) {
        jdbc.update("INSERT INTO packaging_plan (batch_key, planned_quantity, label_start, label_end, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.labelStart(), row.labelEnd(), row.createdAt());
    }

    public List<PackingRow> findActiveRecords(String batchKey) {
        return jdbc.query("SELECT * FROM packing_record WHERE batch_key = ? AND status = 'ACTIVE' ORDER BY id",
                PACKING_MAPPER, batchKey);
    }

    public List<PackingRow> findAllRecords(String batchKey) {
        return jdbc.query("SELECT * FROM packing_record WHERE batch_key = ? ORDER BY id",
                PACKING_MAPPER, batchKey);
    }

    public Optional<PackingRow> findRecord(long packingId) {
        return jdbc.query("SELECT * FROM packing_record WHERE id = ?", PACKING_MAPPER, packingId)
                .stream().findFirst();
    }

    /**
     * 行锁读取封箱版本，串行化同一版本上的作废/重放与放行。
     */
    public Optional<PackingRow> findRecordForUpdate(long packingId) {
        return jdbc.query("SELECT * FROM packing_record WHERE id = ? FOR UPDATE",
                        PACKING_MAPPER, packingId)
                .stream().findFirst();
    }

    /**
     * 插入封箱版本并回填自增版本号。
     */
    public long insertPacking(PackingRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO packing_record (batch_key, command_key, label_no, quantity,"
                            + " packed_by, status, packed_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.batchKey());
            ps.setString(2, row.commandKey());
            ps.setLong(3, row.labelNo());
            ps.setInt(4, row.quantity());
            ps.setString(5, row.packedBy());
            ps.setString(6, row.packedAt());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertOccupancy(OccupancyRow row) {
        jdbc.update("INSERT INTO label_occupancy (label_no, batch_key, packing_id, occupied_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.labelNo(), row.batchKey(), row.packingId(), row.occupiedAt());
    }

    public Optional<OccupancyRow> findOccupancy(long labelNo) {
        return jdbc.query("SELECT * FROM label_occupancy WHERE label_no = ?",
                        OCCUPANCY_MAPPER, labelNo)
                .stream().findFirst();
    }

    public List<Long> findOccupiedLabels(String batchKey) {
        return jdbc.queryForList(
                "SELECT label_no FROM label_occupancy WHERE batch_key = ? ORDER BY label_no",
                Long.class, batchKey);
    }

    public void deleteOccupancy(long labelNo) {
        jdbc.update("DELETE FROM label_occupancy WHERE label_no = ?", labelNo);
    }

    /**
     * 作废封箱版本：仅翻转状态并写作废信息，行本身保留，历史不可删除。
     */
    public void voidRecord(long packingId, String voidedBy, String reason, String voidedAt) {
        jdbc.update("UPDATE packing_record SET status = 'VOID', voided_by = ?, void_reason = ?,"
                        + " voided_at = ? WHERE id = ?",
                voidedBy, reason, voidedAt, packingId);
    }

    public Optional<SnapshotRow> findSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                        SNAPSHOT_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, planned_quantity, packed_quantity,"
                        + " used_label_count, label_summary, records_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.packedQuantity(), row.usedLabelCount(),
                row.labelSummary(), row.recordsJson(), row.createdAt());
    }
}
