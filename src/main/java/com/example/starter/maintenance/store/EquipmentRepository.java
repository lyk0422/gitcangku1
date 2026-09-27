package com.example.starter.maintenance.store;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.EvidenceCheckResult;
import com.example.starter.maintenance.domain.EvidencePackage;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceConfirmation;
import com.example.starter.maintenance.domain.MaintenanceCorrection;
import com.example.starter.maintenance.domain.MaintenanceCycle;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MaintenanceTemplateItem;
import com.example.starter.maintenance.domain.Material;
import com.example.starter.maintenance.domain.Reading;

/**
 * 设备工时保养与证据包数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 */
@Repository
public class EquipmentRepository {

    private final JdbcTemplate jdbc;

    public EquipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Long readNullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer readNullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static final RowMapper<Equipment> EQUIPMENT_MAPPER = (rs, rowNum) -> new Equipment(
            rs.getString("equipment_id"),
            rs.getLong("maintenance_period_minutes"),
            readNullableLong(rs, "overdue_threshold_minutes"),
            rs.getLong("version"));

    private static final RowMapper<Reading> READING_MAPPER = (rs, rowNum) -> new Reading(
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            readInstant(rs, "sampled_at"),
            rs.getLong("cumulative_minutes"),
            rs.getInt("revision_no"));

    private static final RowMapper<MaintenanceRecord> MAINTENANCE_MAPPER = (rs, rowNum) -> new MaintenanceRecord(
            rs.getLong("maintenance_id"),
            rs.getString("equipment_id"),
            rs.getString("reading_id"),
            rs.getInt("anchor_revision_no"),
            readInstant(rs, "anchor_sampled_at"),
            rs.getLong("anchor_cumulative_minutes"),
            rs.getInt("cycle_no"),
            rs.getInt("template_version"),
            rs.getInt("evidence_package_version"),
            rs.getString("confirmed_by"),
            readInstant(rs, "completed_at"));

    private static final RowMapper<MaintenanceCycle> CYCLE_MAPPER = (rs, rowNum) -> new MaintenanceCycle(
            rs.getLong("cycle_id"),
            rs.getString("equipment_id"),
            rs.getInt("cycle_no"),
            rs.getLong("period_minutes"),
            "OPEN".equals(rs.getString("status")),
            rs.getObject("start_anchor_sampled_at", OffsetDateTime.class) == null
                    ? null
                    : readInstant(rs, "start_anchor_sampled_at"),
            rs.getLong("start_anchor_cumulative_minutes"),
            rs.getInt("current_evidence_version"),
            readNullableInt(rs, "current_template_version"),
            rs.getObject("completed_at", OffsetDateTime.class) == null
                    ? null
                    : readInstant(rs, "completed_at"),
            rs.getObject("end_anchor_sampled_at", OffsetDateTime.class) == null
                    ? null
                    : readInstant(rs, "end_anchor_sampled_at"),
            readNullableLong(rs, "end_anchor_cumulative_minutes"),
            readNullableLong(rs, "maintenance_id"));

    private static final RowMapper<MaintenanceTemplateItem> TEMPLATE_ITEM_MAPPER = (rs, rowNum) ->
            new MaintenanceTemplateItem(
                    rs.getString("template_id"),
                    rs.getInt("version"),
                    rs.getInt("position"),
                    rs.getString("item_code"),
                    rs.getString("item_name"),
                    rs.getBoolean("required"));

    private static final RowMapper<EvidencePackage> PACKAGE_MAPPER = (rs, rowNum) -> new EvidencePackage(
            rs.getLong("package_id"),
            rs.getString("equipment_id"),
            rs.getInt("cycle_no"),
            rs.getInt("package_version"),
            rs.getInt("template_version"),
            rs.getString("submitted_by"),
            readInstant(rs, "submitted_at"),
            rs.getString("request_id"));

    private static final RowMapper<EvidenceCheckResult> CHECK_RESULT_MAPPER = (rs, rowNum) ->
            new EvidenceCheckResult(
                    rs.getString("equipment_id"),
                    rs.getInt("cycle_no"),
                    rs.getInt("package_version"),
                    rs.getInt("position"),
                    rs.getString("item_code"),
                    "PASS".equals(rs.getString("result")),
                    rs.getString("note"));

    private static final RowMapper<Material> MATERIAL_MAPPER = (rs, rowNum) -> new Material(
            rs.getString("material_id"),
            rs.getString("material_name"),
            rs.getString("unit"),
            "ACTIVE".equals(rs.getString("status")),
            readInstant(rs, "registered_at"),
            rs.getObject("revoked_at", OffsetDateTime.class) == null
                    ? null
                    : readInstant(rs, "revoked_at"),
            rs.getString("revoke_reason"));

    private static final RowMapper<MaintenanceConfirmation> CONFIRMATION_MAPPER = (rs, rowNum) ->
            new MaintenanceConfirmation(
                    rs.getLong("confirmation_id"),
                    rs.getString("equipment_id"),
                    rs.getInt("cycle_no"),
                    rs.getInt("package_version"),
                    rs.getInt("template_version"),
                    rs.getLong("maintenance_id"),
                    rs.getString("submitted_by"),
                    rs.getString("confirmed_by"),
                    rs.getString("request_id"),
                    readInstant(rs, "confirmed_at"));

    private static final RowMapper<MaintenanceCorrection> CORRECTION_MAPPER = (rs, rowNum) ->
            new MaintenanceCorrection(
                    rs.getLong("correction_id"),
                    rs.getLong("maintenance_id"),
                    rs.getString("equipment_id"),
                    rs.getInt("cycle_no"),
                    rs.getString("content"),
                    rs.getString("created_by"),
                    rs.getString("request_id"),
                    readInstant(rs, "created_at"));

    private static final String CYCLE_COLUMNS =
            "cycle_id, equipment_id, cycle_no, period_minutes, status, start_anchor_sampled_at,"
                    + " start_anchor_cumulative_minutes, current_evidence_version, current_template_version,"
                    + " completed_at, end_anchor_sampled_at, end_anchor_cumulative_minutes, maintenance_id";

    // ---------- 设备 ----------

    public void insertEquipment(String equipmentId, long maintenancePeriodMinutes,
                                Long overdueThresholdMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO equipment (equipment_id, maintenance_period_minutes,"
                        + " overdue_threshold_minutes, version, created_at) VALUES (?, ?, ?, 1, ?)",
                equipmentId, maintenancePeriodMinutes, overdueThresholdMinutes, utc(createdAt));
    }

    public Optional<Equipment> findEquipment(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, overdue_threshold_minutes, version"
                        + " FROM equipment WHERE equipment_id = ?",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一设备的写操作串行化，保证版本校验与业务变更原子。 */
    public Optional<Equipment> findEquipmentForUpdate(String equipmentId) {
        List<Equipment> rows = jdbc.query(
                "SELECT equipment_id, maintenance_period_minutes, overdue_threshold_minutes, version"
                        + " FROM equipment WHERE equipment_id = ? FOR UPDATE",
                EQUIPMENT_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void incrementVersion(String equipmentId) {
        jdbc.update("UPDATE equipment SET version = version + 1 WHERE equipment_id = ?", equipmentId);
    }

    // ---------- 读数 ----------

    public void insertReading(Reading reading, Instant createdAt) {
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, sampled_at, cumulative_minutes,"
                        + " revision_no, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
                reading.equipmentId(), reading.readingId(), utc(reading.sampledAt()),
                reading.cumulativeMinutes(), utc(createdAt), utc(createdAt));
    }

    public Optional<Reading> findReading(String equipmentId, String readingId) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND reading_id = ?",
                READING_MAPPER, equipmentId, readingId);
        return rows.stream().findFirst();
    }

    public Optional<Reading> findReadingAt(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at = ?",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格早于给定时刻的最近一条读数。 */
    public Optional<Reading> findPrevReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at < ?"
                        + " ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    /** 采样时刻严格晚于给定时刻的最近一条读数。 */
    public Optional<Reading> findNextReading(String equipmentId, Instant sampledAt) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? AND sampled_at > ?"
                        + " ORDER BY sampled_at ASC LIMIT 1",
                READING_MAPPER, equipmentId, utc(sampledAt));
        return rows.stream().findFirst();
    }

    public Optional<Reading> findLatestReading(String equipmentId) {
        List<Reading> rows = jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at DESC LIMIT 1",
                READING_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public void updateReadingValue(String equipmentId, String readingId, long cumulativeMinutes,
                                   int newRevisionNo, Instant updatedAt) {
        jdbc.update("UPDATE reading SET cumulative_minutes = ?, revision_no = ?, updated_at = ?"
                        + " WHERE equipment_id = ? AND reading_id = ?",
                cumulativeMinutes, newRevisionNo, utc(updatedAt), equipmentId, readingId);
    }

    public List<Reading> listReadings(String equipmentId) {
        return jdbc.query(
                "SELECT equipment_id, reading_id, sampled_at, cumulative_minutes, revision_no"
                        + " FROM reading WHERE equipment_id = ? ORDER BY sampled_at ASC, reading_id ASC",
                READING_MAPPER, equipmentId);
    }

    // ---------- 修订历史 ----------

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long cumulativeMinutes, String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO reading_revision (equipment_id, reading_id, revision_no,"
                        + " cumulative_minutes, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, cumulativeMinutes, requestId, utc(createdAt));
    }

    public List<RevisionRow> listRevisions(String equipmentId, String readingId) {
        return jdbc.query(
                "SELECT revision_no, cumulative_minutes, request_id, created_at FROM reading_revision"
                        + " WHERE equipment_id = ? AND reading_id = ? ORDER BY revision_no ASC",
                (rs, rowNum) -> new RevisionRow(
                        rs.getInt("revision_no"),
                        rs.getLong("cumulative_minutes"),
                        rs.getString("request_id"),
                        readInstant(rs, "created_at")),
                equipmentId, readingId);
    }

    public record RevisionRow(int revisionNo, long cumulativeMinutes, String requestId, Instant createdAt) {
    }

    // ---------- 保养周期 ----------

    /** 登记设备时创建首周期：无起点锚点，周期工时取设备保养周期快照。 */
    public void insertFirstCycle(String equipmentId, long periodMinutes) {
        jdbc.update("INSERT INTO maintenance_cycle (equipment_id, cycle_no, period_minutes, status,"
                        + " start_anchor_sampled_at, start_anchor_cumulative_minutes,"
                        + " current_evidence_version, current_template_version) "
                        + "VALUES (?, 1, ?, 'OPEN', NULL, 0, 0, NULL)",
                equipmentId, periodMinutes);
    }

    public Optional<MaintenanceCycle> findOpenCycle(String equipmentId) {
        List<MaintenanceCycle> rows = jdbc.query(
                "SELECT " + CYCLE_COLUMNS + " FROM maintenance_cycle"
                        + " WHERE equipment_id = ? AND status = 'OPEN'"
                        + " ORDER BY cycle_no DESC LIMIT 1",
                CYCLE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    /** 行锁版本：写操作在设备行锁内调用，周期行也加锁以保证证据/确认串行。 */
    public Optional<MaintenanceCycle> findOpenCycleForUpdate(String equipmentId) {
        List<MaintenanceCycle> rows = jdbc.query(
                "SELECT " + CYCLE_COLUMNS + " FROM maintenance_cycle"
                        + " WHERE equipment_id = ? AND status = 'OPEN'"
                        + " ORDER BY cycle_no DESC LIMIT 1 FOR UPDATE",
                CYCLE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public Optional<MaintenanceCycle> findCycle(String equipmentId, int cycleNo) {
        List<MaintenanceCycle> rows = jdbc.query(
                "SELECT " + CYCLE_COLUMNS + " FROM maintenance_cycle"
                        + " WHERE equipment_id = ? AND cycle_no = ?",
                CYCLE_MAPPER, equipmentId, cycleNo);
        return rows.stream().findFirst();
    }

    public List<MaintenanceCycle> listCycles(String equipmentId) {
        return jdbc.query(
                "SELECT " + CYCLE_COLUMNS + " FROM maintenance_cycle"
                        + " WHERE equipment_id = ? ORDER BY cycle_no ASC",
                CYCLE_MAPPER, equipmentId);
    }

    public void updateCycleEvidence(String equipmentId, int cycleNo, int packageVersion,
                                    Integer templateVersion) {
        jdbc.update("UPDATE maintenance_cycle SET current_evidence_version = ?,"
                        + " current_template_version = ? WHERE equipment_id = ? AND cycle_no = ?",
                packageVersion, templateVersion, equipmentId, cycleNo);
    }

    /** 完工：当前周期置 COMPLETED 并固化锚点快照与保养记录标识。 */
    public void completeCycle(String equipmentId, int cycleNo, Instant anchorSampledAt,
                              long anchorCumulativeMinutes, long maintenanceId, Instant completedAt) {
        jdbc.update("UPDATE maintenance_cycle SET status = 'COMPLETED', completed_at = ?,"
                        + " end_anchor_sampled_at = ?, end_anchor_cumulative_minutes = ?,"
                        + " maintenance_id = ? WHERE equipment_id = ? AND cycle_no = ?",
                utc(completedAt), utc(anchorSampledAt), anchorCumulativeMinutes, maintenanceId,
                equipmentId, cycleNo);
    }

    /** 开启下一周期：起点锚点为刚完成周期的完工锚点（左闭右开）。 */
    public void insertNextCycle(String equipmentId, int nextCycleNo, long periodMinutes,
                                Instant startAnchorSampledAt, long startAnchorCumulativeMinutes) {
        jdbc.update("INSERT INTO maintenance_cycle (equipment_id, cycle_no, period_minutes, status,"
                        + " start_anchor_sampled_at, start_anchor_cumulative_minutes,"
                        + " current_evidence_version, current_template_version) "
                        + "VALUES (?, ?, ?, 'OPEN', ?, ?, 0, NULL)",
                equipmentId, nextCycleNo, periodMinutes,
                utc(startAnchorSampledAt), startAnchorCumulativeMinutes);
    }

    // ---------- 模板 ----------

    /** 当前模板版本号；模板尚未建立时返回 null。 */
    public Integer findCurrentTemplateVersion(String templateId) {
        List<Integer> rows = jdbc.query(
                "SELECT current_version FROM maintenance_template WHERE template_id = ?",
                (rs, rowNum) -> rs.getInt("current_version"), templateId);
        return rows.stream().findFirst().orElse(null);
    }

    public void insertTemplate(String templateId, int version, Instant now) {
        jdbc.update("INSERT INTO maintenance_template (template_id, current_version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?)",
                templateId, version, utc(now), utc(now));
    }

    public void bumpTemplateVersion(String templateId, int newVersion, Instant now) {
        jdbc.update("UPDATE maintenance_template SET current_version = ?, updated_at = ?"
                        + " WHERE template_id = ?", newVersion, utc(now), templateId);
    }

    public void insertTemplateItem(String templateId, int version, int position,
                                   String itemCode, String itemName, boolean required) {
        jdbc.update("INSERT INTO maintenance_template_item (template_id, version, position,"
                        + " item_code, item_name, required) VALUES (?, ?, ?, ?, ?, ?)",
                templateId, version, position, itemCode, itemName, required);
    }

    public List<MaintenanceTemplateItem> listTemplateItems(String templateId, int version) {
        return jdbc.query(
                "SELECT template_id, version, position, item_code, item_name, required"
                        + " FROM maintenance_template_item WHERE template_id = ? AND version = ?"
                        + " ORDER BY position ASC",
                TEMPLATE_ITEM_MAPPER, templateId, version);
    }

    // ---------- 证据包 ----------

    public long insertEvidencePackage(EvidencePackage evidencePackage) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO evidence_package (equipment_id, cycle_no, package_version,"
                            + " template_version, submitted_by, submitted_at, request_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, evidencePackage.equipmentId());
            ps.setInt(2, evidencePackage.cycleNo());
            ps.setInt(3, evidencePackage.packageVersion());
            ps.setInt(4, evidencePackage.templateVersion());
            ps.setString(5, evidencePackage.submittedBy());
            ps.setObject(6, utc(evidencePackage.submittedAt()));
            ps.setString(7, evidencePackage.requestId());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("证据包主键生成失败");
        }
        return key.longValue();
    }

    public void insertCheckResult(EvidenceCheckResult result) {
        jdbc.update("INSERT INTO evidence_check_result (equipment_id, cycle_no, package_version,"
                        + " position, item_code, result, note) VALUES (?, ?, ?, ?, ?, ?, ?)",
                result.equipmentId(), result.cycleNo(), result.packageVersion(),
                result.position(), result.itemCode(), result.pass() ? "PASS" : "FAIL", result.note());
    }

    public void insertMaterialSummary(String equipmentId, int cycleNo, int packageVersion,
                                      String materialId, BigDecimal quantity, String unit) {
        jdbc.update("INSERT INTO evidence_material_summary (equipment_id, cycle_no, package_version,"
                        + " material_id, quantity, unit) VALUES (?, ?, ?, ?, ?, ?)",
                equipmentId, cycleNo, packageVersion, materialId, quantity, unit);
    }

    public Optional<EvidencePackage> findEvidencePackage(String equipmentId, int cycleNo, int packageVersion) {
        List<EvidencePackage> rows = jdbc.query(
                "SELECT package_id, equipment_id, cycle_no, package_version, template_version,"
                        + " submitted_by, submitted_at, request_id FROM evidence_package"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND package_version = ?",
                PACKAGE_MAPPER, equipmentId, cycleNo, packageVersion);
        return rows.stream().findFirst();
    }

    public List<EvidencePackage> listEvidencePackages(String equipmentId, int cycleNo) {
        return jdbc.query(
                "SELECT package_id, equipment_id, cycle_no, package_version, template_version,"
                        + " submitted_by, submitted_at, request_id FROM evidence_package"
                        + " WHERE equipment_id = ? AND cycle_no = ? ORDER BY package_version ASC",
                PACKAGE_MAPPER, equipmentId, cycleNo);
    }

    public List<EvidenceCheckResult> listCheckResults(String equipmentId, int cycleNo, int packageVersion) {
        return jdbc.query(
                "SELECT equipment_id, cycle_no, package_version, position, item_code, result, note"
                        + " FROM evidence_check_result"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND package_version = ?"
                        + " ORDER BY position ASC",
                CHECK_RESULT_MAPPER, equipmentId, cycleNo, packageVersion);
    }

    public List<Material.Summary> listMaterialSummaries(String equipmentId, int cycleNo) {
        return jdbc.query(
                "SELECT material_id, quantity, unit, package_version FROM evidence_material_summary"
                        + " WHERE equipment_id = ? AND cycle_no = ? ORDER BY material_id ASC",
                (rs, rowNum) -> new Material.Summary(
                        rs.getString("material_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit"),
                        rs.getInt("package_version")),
                equipmentId, cycleNo);
    }

    /** 指定证据包版本引入的材料摘要（批次周期内唯一，故按版本过滤即该包新增摘要）。 */
    public List<Material.Summary> listMaterialSummariesForPackage(String equipmentId, int cycleNo,
                                                                  int packageVersion) {
        return jdbc.query(
                "SELECT material_id, quantity, unit, package_version FROM evidence_material_summary"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND package_version = ?"
                        + " ORDER BY material_id ASC",
                (rs, rowNum) -> new Material.Summary(
                        rs.getString("material_id"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("unit"),
                        rs.getInt("package_version")),
                equipmentId, cycleNo, packageVersion);
    }

    /** 同设备同周期是否已引用该材料批次（跨证据版本同样禁止重复）。 */
    public boolean existsMaterialSummary(String equipmentId, int cycleNo, String materialId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_material_summary"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND material_id = ?",
                Integer.class, equipmentId, cycleNo, materialId);
        return count != null && count > 0;
    }

    // ---------- 材料 ----------

    public void insertMaterial(String materialId, String materialName, String unit, Instant registeredAt) {
        jdbc.update("INSERT INTO material (material_id, material_name, unit, status, registered_at)"
                        + " VALUES (?, ?, ?, 'ACTIVE', ?)",
                materialId, materialName, unit, utc(registeredAt));
    }

    public Optional<Material> findMaterial(String materialId) {
        List<Material> rows = jdbc.query(
                "SELECT material_id, material_name, unit, status, registered_at, revoked_at, revoke_reason"
                        + " FROM material WHERE material_id = ?",
                MATERIAL_MAPPER, materialId);
        return rows.stream().findFirst();
    }

    /** 材料行锁：证据引用与材料撤销按提交顺序串行裁决。 */
    public Optional<Material> findMaterialForUpdate(String materialId) {
        List<Material> rows = jdbc.query(
                "SELECT material_id, material_name, unit, status, registered_at, revoked_at, revoke_reason"
                        + " FROM material WHERE material_id = ? FOR UPDATE",
                MATERIAL_MAPPER, materialId);
        return rows.stream().findFirst();
    }

    public List<Material> listMaterials() {
        return jdbc.query(
                "SELECT material_id, material_name, unit, status, registered_at, revoked_at, revoke_reason"
                        + " FROM material ORDER BY material_id ASC",
                MATERIAL_MAPPER);
    }

    public void revokeMaterial(String materialId, String reason, Instant revokedAt) {
        jdbc.update("UPDATE material SET status = 'REVOKED', revoked_at = ?, revoke_reason = ?"
                        + " WHERE material_id = ?",
                utc(revokedAt), reason, materialId);
    }

    // ---------- 保养记录 ----------

    public long insertMaintenance(String equipmentId, String readingId, int anchorRevisionNo,
                                  Instant anchorSampledAt, long anchorCumulativeMinutes,
                                  int cycleNo, int templateVersion, int evidencePackageVersion,
                                  String confirmedBy, String requestId, Instant completedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance (equipment_id, reading_id, anchor_revision_no,"
                            + " anchor_sampled_at, anchor_cumulative_minutes, cycle_no, template_version,"
                            + " evidence_package_version, confirmed_by, request_id, completed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setString(2, readingId);
            ps.setInt(3, anchorRevisionNo);
            ps.setObject(4, utc(anchorSampledAt));
            ps.setLong(5, anchorCumulativeMinutes);
            ps.setInt(6, cycleNo);
            ps.setInt(7, templateVersion);
            ps.setInt(8, evidencePackageVersion);
            ps.setString(9, confirmedBy);
            ps.setString(10, requestId);
            ps.setObject(11, utc(completedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("保养记录主键生成失败");
        }
        return key.longValue();
    }

    /** 最近一次保养（锚点时间最大者；锚点时间严格递增，故唯一）。 */
    public Optional<MaintenanceRecord> findLastMaintenance(String equipmentId) {
        List<MaintenanceRecord> rows = jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes, cycle_no, template_version,"
                        + " evidence_package_version, confirmed_by, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at DESC, maintenance_id DESC LIMIT 1",
                MAINTENANCE_MAPPER, equipmentId);
        return rows.stream().findFirst();
    }

    public Optional<MaintenanceRecord> findMaintenance(long maintenanceId) {
        List<MaintenanceRecord> rows = jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes, cycle_no, template_version,"
                        + " evidence_package_version, confirmed_by, completed_at"
                        + " FROM maintenance WHERE maintenance_id = ?",
                MAINTENANCE_MAPPER, maintenanceId);
        return rows.stream().findFirst();
    }

    public List<MaintenanceRecord> listMaintenances(String equipmentId) {
        return jdbc.query(
                "SELECT maintenance_id, equipment_id, reading_id, anchor_revision_no,"
                        + " anchor_sampled_at, anchor_cumulative_minutes, cycle_no, template_version,"
                        + " evidence_package_version, confirmed_by, completed_at"
                        + " FROM maintenance WHERE equipment_id = ?"
                        + " ORDER BY anchor_sampled_at ASC, maintenance_id ASC",
                MAINTENANCE_MAPPER, equipmentId);
    }

    /** 该读数是否已被任意历史保养记录锚定（锚定后不可修订）。 */
    public boolean existsMaintenanceAnchoringReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = ? AND reading_id = ?",
                Integer.class, equipmentId, readingId);
        return count != null && count > 0;
    }

    // ---------- 双人确认 ----------

    public void insertConfirmation(MaintenanceConfirmation confirmation) {
        jdbc.update("INSERT INTO maintenance_confirmation (equipment_id, cycle_no, package_version,"
                        + " template_version, maintenance_id, submitted_by, confirmed_by, request_id,"
                        + " confirmed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                confirmation.equipmentId(), confirmation.cycleNo(), confirmation.packageVersion(),
                confirmation.templateVersion(), confirmation.maintenanceId(), confirmation.submittedBy(),
                confirmation.confirmedBy(), confirmation.requestId(), utc(confirmation.confirmedAt()));
    }

    public Optional<MaintenanceConfirmation> findConfirmation(String equipmentId, int cycleNo) {
        List<MaintenanceConfirmation> rows = jdbc.query(
                "SELECT confirmation_id, equipment_id, cycle_no, package_version, template_version,"
                        + " maintenance_id, submitted_by, confirmed_by, request_id, confirmed_at"
                        + " FROM maintenance_confirmation WHERE equipment_id = ? AND cycle_no = ?",
                CONFIRMATION_MAPPER, equipmentId, cycleNo);
        return rows.stream().findFirst();
    }

    // ---------- 更正附录 ----------

    public long insertCorrection(MaintenanceCorrection correction) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance_correction (maintenance_id, equipment_id, cycle_no,"
                            + " content, created_by, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, correction.maintenanceId());
            ps.setString(2, correction.equipmentId());
            ps.setInt(3, correction.cycleNo());
            ps.setString(4, correction.content());
            ps.setString(5, correction.createdBy());
            ps.setString(6, correction.requestId());
            ps.setObject(7, utc(correction.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("更正附录主键生成失败");
        }
        return key.longValue();
    }

    public List<MaintenanceCorrection> listCorrections(String equipmentId, Long maintenanceId) {
        if (maintenanceId == null) {
            return jdbc.query(
                    "SELECT correction_id, maintenance_id, equipment_id, cycle_no, content, created_by,"
                            + " request_id, created_at FROM maintenance_correction"
                            + " WHERE equipment_id = ? ORDER BY correction_id ASC",
                    CORRECTION_MAPPER, equipmentId);
        }
        return jdbc.query(
                "SELECT correction_id, maintenance_id, equipment_id, cycle_no, content, created_by,"
                        + " request_id, created_at FROM maintenance_correction"
                        + " WHERE equipment_id = ? AND maintenance_id = ? ORDER BY correction_id ASC",
                CORRECTION_MAPPER, equipmentId, maintenanceId);
    }

    // ---------- 幂等去重 ----------

    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, operation, request_fingerprint, response_body"
                        + " FROM idempotency_request WHERE request_id = ?",
                (rs, rowNum) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("operation"),
                        rs.getString("request_fingerprint"),
                        rs.getString("response_body")),
                requestId);
        return rows.stream().findFirst();
    }

    public void insertIdempotency(String requestId, String operation, String fingerprint,
                                  String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_request (request_id, operation, request_fingerprint,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?)",
                requestId, operation, fingerprint, responseBody, utc(createdAt));
    }

    public record IdempotencyRow(String requestId, String operation, String fingerprint, String responseBody) {
    }
}
