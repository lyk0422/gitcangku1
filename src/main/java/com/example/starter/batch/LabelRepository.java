package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 包装标签核销持久化层：包装计划、封箱记录与放行快照；所有 SQL 参数化，
 * 时间以 ISO-8601 UTC 字符串存取；历史行只增不改，作废通过状态与版本字段体现。
 */
@Repository
public class LabelRepository {

    /**
     * packaging_plan 表行记录；每批次仅一份，登记后不可改写。
     */
    public record PlanRow(long id, String batchKey, int plannedQuantity, long labelStart,
                          long labelEnd, String createdAt) {
    }

    /**
     * box_seal 表行记录；activeLabelNo 为 NULL 表示已作废、标签占用已释放。
     */
    public record SealRow(long id, String batchKey, String sealKey, long labelNo, int quantity,
                          String status, int version, Long activeLabelNo, String voidReason,
                          String createdAt, String voidedAt) {
    }

    /**
     * release_snapshot 表行记录；放行时固化，之后不得改写。
     */
    public record SnapshotRow(long id, String batchKey, int plannedQuantity, int sealedQuantity,
                              int labelCount, String labelDigest, String sealsJson,
                              String createdAt) {
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getLong("label_start"), rs.getLong("label_end"), rs.getString("created_at"));

    private static final RowMapper<SealRow> SEAL_MAPPER = (rs, n) -> {
        long activeLabel = rs.getLong("active_label_no");
        return new SealRow(rs.getLong("id"), rs.getString("batch_key"), rs.getString("seal_key"),
                rs.getLong("label_no"), rs.getInt("quantity"), rs.getString("status"),
                rs.getInt("version"), rs.wasNull() ? null : activeLabel,
                rs.getString("void_reason"), rs.getString("created_at"), rs.getString("voided_at"));
    };

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getInt("sealed_quantity"), rs.getInt("label_count"), rs.getString("label_digest"),
            rs.getString("seals_json"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public LabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPlan(PlanRow row) {
        jdbc.update("INSERT INTO packaging_plan (batch_key, planned_quantity, label_start,"
                        + " label_end, created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.labelStart(), row.labelEnd(),
                row.createdAt());
    }

    public Optional<PlanRow> findPlan(String batchKey) {
        return jdbc.query("SELECT * FROM packaging_plan WHERE batch_key = ?", PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertSeal(SealRow row) {
        jdbc.update("INSERT INTO box_seal (batch_key, seal_key, label_no, quantity, status, version,"
                        + " active_label_no, void_reason, created_at, voided_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.sealKey(), row.labelNo(), row.quantity(), row.status(),
                row.version(), row.activeLabelNo(), row.voidReason(), row.createdAt(),
                row.voidedAt());
    }

    public Optional<SealRow> findSeal(String batchKey, String sealKey) {
        return jdbc.query("SELECT * FROM box_seal WHERE batch_key = ? AND seal_key = ?",
                        SEAL_MAPPER, batchKey, sealKey)
                .stream().findFirst();
    }

    /**
     * 某批次全部封箱记录（含已作废），按提交顺序；历史查询使用，读取不改变状态。
     */
    public List<SealRow> findSeals(String batchKey) {
        return jdbc.query("SELECT * FROM box_seal WHERE batch_key = ? ORDER BY id",
                SEAL_MAPPER, batchKey);
    }

    /**
     * 某批次活跃封箱记录，按提交顺序。
     */
    public List<SealRow> findActiveSeals(String batchKey) {
        return jdbc.query("SELECT * FROM box_seal WHERE batch_key = ? AND status = 'ACTIVE'"
                + " ORDER BY id", SEAL_MAPPER, batchKey);
    }

    /**
     * 某标签号当前的全局活跃占用（任意批次）；用于给出可区分的 409 原因。
     */
    public Optional<SealRow> findActiveLabelOwner(long labelNo) {
        return jdbc.query("SELECT * FROM box_seal WHERE active_label_no = ?",
                        SEAL_MAPPER, labelNo)
                .stream().findFirst();
    }

    /**
     * 作废封箱：状态置 VOIDED、版本 +1、写入原因与作废时间，并释放标签占用（active_label_no 置 NULL）。
     */
    public void voidSeal(String batchKey, String sealKey, int newVersion, String reason,
                         String voidedAt) {
        jdbc.update("UPDATE box_seal SET status = 'VOIDED', version = ?, active_label_no = NULL,"
                        + " void_reason = ?, voided_at = ? WHERE batch_key = ? AND seal_key = ?",
                newVersion, reason, voidedAt, batchKey, sealKey);
    }

    /**
     * 某批次活跃封箱数量之和；无活跃封箱时为 0。
     */
    public int sumActiveQuantities(String batchKey) {
        Integer sum = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM box_seal"
                + " WHERE batch_key = ? AND status = 'ACTIVE'", Integer.class, batchKey);
        return sum == null ? 0 : sum;
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, planned_quantity, sealed_quantity,"
                        + " label_count, label_digest, seals_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.sealedQuantity(), row.labelCount(),
                row.labelDigest(), row.sealsJson(), row.createdAt());
    }

    public Optional<SnapshotRow> findSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                        SNAPSHOT_MAPPER, batchKey)
                .stream().findFirst();
    }
}
