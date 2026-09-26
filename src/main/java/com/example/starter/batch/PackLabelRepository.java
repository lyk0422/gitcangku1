package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 包装标签核销持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 * 标签占用（label_usage）仅存当前有效占用，作废即删行释放；
 * 封箱（carton）与放行快照（release_label_snapshot）只增不改写历史字段。
 */
@Repository
public class PackLabelRepository {

    /**
     * pack_plan 表行记录：批次包装计划，每批次一份。
     */
    public record PackPlanRow(long id, String batchKey, int plannedQuantity,
                              long labelStart, long labelEnd, String createdAt) {
    }

    /**
     * carton 表行记录：作废仅更新 status/version/void_reason/voided_at，其余字段创建后不改写。
     */
    public record CartonRow(long id, String batchKey, String cartonKey, long labelNo,
                            int quantity, String status, int version,
                            String voidReason, String voidedAt, String createdAt) {
    }

    /**
     * label_usage 表行记录：当前有效标签占用。
     */
    public record LabelUsageRow(long labelNo, String batchKey, String cartonKey, String createdAt) {
    }

    /**
     * release_label_snapshot 表行记录：放行时固化，之后不改写。
     */
    public record SnapshotRow(long id, String batchKey, int plannedQuantity, int sealedQuantity,
                              int cartonCount, String labels, String labelDigest,
                              String cartonVersions, String createdAt) {
    }

    private static final RowMapper<PackPlanRow> PLAN_MAPPER = (rs, n) -> new PackPlanRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getLong("label_start"), rs.getLong("label_end"), rs.getString("created_at"));

    private static final RowMapper<CartonRow> CARTON_MAPPER = (rs, n) -> new CartonRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("carton_key"),
            rs.getLong("label_no"), rs.getInt("quantity"), rs.getString("status"),
            rs.getInt("version"), rs.getString("void_reason"), rs.getString("voided_at"),
            rs.getString("created_at"));

    private static final RowMapper<LabelUsageRow> USAGE_MAPPER = (rs, n) -> new LabelUsageRow(
            rs.getLong("label_no"), rs.getString("batch_key"), rs.getString("carton_key"),
            rs.getString("created_at"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getInt("sealed_quantity"), rs.getInt("carton_count"), rs.getString("labels"),
            rs.getString("label_digest"), rs.getString("carton_versions"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public PackLabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PackPlanRow> findPlan(String batchKey) {
        return jdbc.query("SELECT * FROM pack_plan WHERE batch_key = ?", PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertPlan(PackPlanRow row) {
        jdbc.update("INSERT INTO pack_plan (batch_key, planned_quantity, label_start, label_end,"
                        + " created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.labelStart(), row.labelEnd(),
                row.createdAt());
    }

    public Optional<CartonRow> findCarton(String batchKey, String cartonKey) {
        return jdbc.query("SELECT * FROM carton WHERE batch_key = ? AND carton_key = ?",
                        CARTON_MAPPER, batchKey, cartonKey)
                .stream().findFirst();
    }

    /**
     * 批次全部封箱（含已作废），按提交顺序排列；历史明细读取不改变状态。
     */
    public List<CartonRow> findCartons(String batchKey) {
        return jdbc.query("SELECT * FROM carton WHERE batch_key = ? ORDER BY id",
                CARTON_MAPPER, batchKey);
    }

    public void insertCarton(CartonRow row) {
        jdbc.update("INSERT INTO carton (batch_key, carton_key, label_no, quantity, status,"
                        + " version, void_reason, voided_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.cartonKey(), row.labelNo(), row.quantity(), row.status(),
                row.version(), row.voidReason(), row.voidedAt(), row.createdAt());
    }

    /**
     * 作废封箱：置 VOIDED、版本 +1、回填作废原因与时间；数量/标签/创建时间不改写。
     */
    public void voidCarton(String batchKey, String cartonKey, int newVersion,
                           String voidReason, String voidedAt) {
        jdbc.update("UPDATE carton SET status = 'VOIDED', version = ?, void_reason = ?,"
                        + " voided_at = ? WHERE batch_key = ? AND carton_key = ?",
                newVersion, voidReason, voidedAt, batchKey, cartonKey);
    }

    public Optional<LabelUsageRow> findLabelUsage(long labelNo) {
        return jdbc.query("SELECT * FROM label_usage WHERE label_no = ?", USAGE_MAPPER, labelNo)
                .stream().findFirst();
    }

    /**
     * 批次当前有效占用标签，按标签号升序（规范排序）。
     */
    public List<LabelUsageRow> findLabelUsages(String batchKey) {
        return jdbc.query("SELECT * FROM label_usage WHERE batch_key = ? ORDER BY label_no",
                USAGE_MAPPER, batchKey);
    }

    public void insertLabelUsage(LabelUsageRow row) {
        jdbc.update("INSERT INTO label_usage (label_no, batch_key, carton_key, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.labelNo(), row.batchKey(), row.cartonKey(), row.createdAt());
    }

    /**
     * 释放标签占用（作废时调用）。
     */
    public void deleteLabelUsage(long labelNo) {
        jdbc.update("DELETE FROM label_usage WHERE label_no = ?", labelNo);
    }

    public Optional<SnapshotRow> findSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_label_snapshot WHERE batch_key = ?",
                        SNAPSHOT_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO release_label_snapshot (batch_key, planned_quantity,"
                        + " sealed_quantity, carton_count, labels, label_digest, carton_versions,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.sealedQuantity(), row.cartonCount(),
                row.labels(), row.labelDigest(), row.cartonVersions(), row.createdAt());
    }
}
