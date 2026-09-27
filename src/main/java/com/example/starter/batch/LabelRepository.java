package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 包装标签核销持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 *
 * <p>封箱记录 append-only：作废只更新状态与作废信息列，绝不删除行；标签当前占用由
 * label_occupancy 单独维护，其主键保证同一标签并发封箱最多一次成功。
 */
@Repository
public class LabelRepository {

    /**
     * label_plan 表行记录；每个批次至多一条。
     */
    public record LabelPlanRow(long id, String batchKey, int plannedQuantity,
                               long labelRangeStart, long labelRangeEnd, String createdAt) {
    }

    /**
     * package_seal 表行记录；同一标签每次封箱一个版本，作废不改写业务字段。
     */
    public record PackageSealRow(long id, String batchKey, long labelNo, int quantity, int version,
                                 String status, String commandKey, String sealedAt,
                                 String voidedAt, String voidReason, String voidCommandKey) {
    }

    /**
     * label_occupancy 表行记录：标签当前生效占用（一个批次、一个封箱）。
     */
    public record OccupancyRow(long labelNo, String batchKey, long sealId) {
    }

    /**
     * release_label_snapshot 表行记录：放行时固化，之后不可改写。
     */
    public record SnapshotRow(long id, String batchKey, int plannedQuantity, int sealedQuantity,
                              String usedLabels, String labelRangeSummary, String capturedAt) {
    }

    /**
     * release_snapshot_seal 表行记录：快照中的每个封箱版本。
     */
    public record SnapshotSealRow(long id, String batchKey, long labelNo, int quantity,
                                  int version, int seq) {
    }

    private static final RowMapper<LabelPlanRow> PLAN_MAPPER = (rs, n) -> new LabelPlanRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getLong("label_range_start"), rs.getLong("label_range_end"),
            rs.getString("created_at"));

    private static final RowMapper<PackageSealRow> SEAL_MAPPER = (rs, n) -> new PackageSealRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getLong("label_no"),
            rs.getInt("quantity"), rs.getInt("version"), rs.getString("status"),
            rs.getString("command_key"), rs.getString("sealed_at"),
            rs.getString("voided_at"), rs.getString("void_reason"),
            rs.getString("void_command_key"));

    private static final RowMapper<OccupancyRow> OCCUPANCY_MAPPER = (rs, n) -> new OccupancyRow(
            rs.getLong("label_no"), rs.getString("batch_key"), rs.getLong("seal_id"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("planned_quantity"),
            rs.getInt("sealed_quantity"), rs.getString("used_labels"),
            rs.getString("label_range_summary"), rs.getString("captured_at"));

    private static final RowMapper<SnapshotSealRow> SNAPSHOT_SEAL_MAPPER = (rs, n) ->
            new SnapshotSealRow(rs.getLong("id"), rs.getString("batch_key"), rs.getLong("label_no"),
                    rs.getInt("quantity"), rs.getInt("version"), rs.getInt("seq"));

    private final JdbcTemplate jdbc;

    public LabelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LabelPlanRow> findPlan(String batchKey) {
        return jdbc.query("SELECT * FROM label_plan WHERE batch_key = ?", PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertPlan(LabelPlanRow row) {
        jdbc.update("INSERT INTO label_plan (batch_key, planned_quantity, label_range_start,"
                        + " label_range_end, created_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.labelRangeStart(),
                row.labelRangeEnd(), row.createdAt());
    }

    /**
     * 批次的全部封箱版本（含已作废），按事件提交顺序（id 升序）。
     */
    public List<PackageSealRow> findSealsByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM package_seal WHERE batch_key = ? ORDER BY id",
                SEAL_MAPPER, batchKey);
    }

    /**
     * 批次当前生效封箱，按标签号升序。
     */
    public List<PackageSealRow> findActiveSeals(String batchKey) {
        return jdbc.query(
                "SELECT * FROM package_seal WHERE batch_key = ? AND status = 'ACTIVE' ORDER BY label_no",
                SEAL_MAPPER, batchKey);
    }

    /**
     * 某标签的全部封箱版本，按版本升序；用于判定标签首次归属批次及计算下一版本号。
     */
    public List<PackageSealRow> findSealVersions(long labelNo) {
        return jdbc.query("SELECT * FROM package_seal WHERE label_no = ? ORDER BY version",
                SEAL_MAPPER, labelNo);
    }

    /**
     * 某标签在指定批次的当前生效封箱；不存在返回空。
     */
    public Optional<PackageSealRow> findActiveSeal(String batchKey, long labelNo) {
        return jdbc.query(
                "SELECT * FROM package_seal WHERE batch_key = ? AND label_no = ? AND status = 'ACTIVE'",
                SEAL_MAPPER, batchKey, labelNo).stream().findFirst();
    }

    public void insertSeal(PackageSealRow row) {
        jdbc.update("INSERT INTO package_seal (batch_key, label_no, quantity, version, status,"
                        + " command_key, sealed_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                row.batchKey(), row.labelNo(), row.quantity(), row.version(),
                row.commandKey(), row.sealedAt());
    }

    /**
     * 作废封箱（append-only：只更新状态/作废信息，业务证据列不改写）。
     */
    public void voidSeal(long sealId, String voidedAt, String reason, String voidCommandKey) {
        jdbc.update("UPDATE package_seal SET status = 'VOIDED', voided_at = ?, void_reason = ?,"
                        + " void_command_key = ? WHERE id = ? AND status = 'ACTIVE'",
                voidedAt, reason, voidCommandKey, sealId);
    }

    public Optional<OccupancyRow> findOccupancy(long labelNo) {
        return jdbc.query("SELECT * FROM label_occupancy WHERE label_no = ?",
                OCCUPANCY_MAPPER, labelNo).stream().findFirst();
    }

    public void insertOccupancy(OccupancyRow row) {
        jdbc.update("INSERT INTO label_occupancy (label_no, batch_key, seal_id) VALUES (?, ?, ?)",
                row.labelNo(), row.batchKey(), row.sealId());
    }

    public void deleteOccupancy(long labelNo) {
        jdbc.update("DELETE FROM label_occupancy WHERE label_no = ?", labelNo);
    }

    /**
     * 某批次当前生效占用标签数。
     */
    public int countOccupancyForBatch(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_occupancy WHERE batch_key = ?",
                Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO release_label_snapshot (batch_key, planned_quantity,"
                        + " sealed_quantity, used_labels, label_range_summary, captured_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.plannedQuantity(), row.sealedQuantity(), row.usedLabels(),
                row.labelRangeSummary(), row.capturedAt());
    }

    public Optional<SnapshotRow> findSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_label_snapshot WHERE batch_key = ?",
                SNAPSHOT_MAPPER, batchKey).stream().findFirst();
    }

    public List<SnapshotSealRow> findSnapshotSeals(String batchKey) {
        return jdbc.query(
                "SELECT * FROM release_snapshot_seal WHERE batch_key = ? ORDER BY seq",
                SNAPSHOT_SEAL_MAPPER, batchKey);
    }

    public void insertSnapshotSeal(SnapshotSealRow row) {
        jdbc.update("INSERT INTO release_snapshot_seal (batch_key, label_no, quantity, version, seq)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.labelNo(), row.quantity(), row.version(), row.seq());
    }
}
