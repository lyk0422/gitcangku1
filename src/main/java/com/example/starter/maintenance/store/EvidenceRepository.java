package com.example.starter.maintenance.store;

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

import com.example.starter.maintenance.domain.CompletionConfirmation;
import com.example.starter.maintenance.domain.EvidenceItemResult;
import com.example.starter.maintenance.domain.EvidenceMaterial;
import com.example.starter.maintenance.domain.EvidencePackage;
import com.example.starter.maintenance.domain.MaintenanceAppendix;
import com.example.starter.maintenance.domain.MaterialBatch;
import com.example.starter.maintenance.domain.TemplateItem;

/**
 * 保养证据包相关数据访问层（模板、材料批次、证据包、完工确认、更正附录）。
 * 所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 * 证据包、检查结果、材料摘要、完工确认与附录均为插入式不可变记录。
 */
@Repository
public class EvidenceRepository {

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<TemplateItem> TEMPLATE_ITEM_MAPPER = (rs, rowNum) -> new TemplateItem(
            rs.getString("item_code"),
            rs.getString("item_name"),
            rs.getBoolean("required_flag"),
            rs.getInt("sort_order"));

    private static final RowMapper<MaterialBatch> MATERIAL_MAPPER = (rs, rowNum) -> new MaterialBatch(
            rs.getString("batch_code"),
            rs.getString("material_name"),
            rs.getString("status"),
            readInstant(rs, "created_at"),
            readInstant(rs, "revoked_at"),
            rs.getString("revoke_reason"));

    private static final RowMapper<EvidencePackage> PACKAGE_MAPPER = (rs, rowNum) -> new EvidencePackage(
            rs.getLong("package_id"),
            rs.getString("equipment_id"),
            rs.getLong("cycle_no"),
            rs.getInt("package_version"),
            rs.getString("template_id"),
            rs.getInt("template_version"),
            rs.getString("submitted_by"),
            readInstant(rs, "submitted_at"));

    private static final RowMapper<EvidenceItemResult> ITEM_RESULT_MAPPER = (rs, rowNum) -> new EvidenceItemResult(
            rs.getLong("package_id"),
            rs.getString("item_code"),
            rs.getString("item_name"),
            rs.getBoolean("required_flag"),
            rs.getInt("sort_order"),
            rs.getString("result"),
            rs.getString("note"));

    private static final RowMapper<EvidenceMaterial> EVIDENCE_MATERIAL_MAPPER = (rs, rowNum) -> new EvidenceMaterial(
            rs.getLong("package_id"),
            rs.getString("batch_code"),
            rs.getString("material_name"),
            rs.getObject("quantity") == null ? null : rs.getLong("quantity"),
            rs.getString("unit"),
            rs.getString("summary"));

    private static final RowMapper<CompletionConfirmation> CONFIRMATION_MAPPER = (rs, rowNum) -> new CompletionConfirmation(
            rs.getLong("confirmation_id"),
            rs.getString("equipment_id"),
            rs.getLong("cycle_no"),
            rs.getInt("package_version"),
            rs.getInt("template_version"),
            rs.getString("confirmed_by"),
            rs.getLong("maintenance_id"),
            readInstant(rs, "confirmed_at"));

    private static final RowMapper<MaintenanceAppendix> APPENDIX_MAPPER = (rs, rowNum) -> new MaintenanceAppendix(
            rs.getLong("appendix_id"),
            rs.getLong("maintenance_id"),
            rs.getString("equipment_id"),
            rs.getString("created_by"),
            rs.getString("content"),
            readInstant(rs, "created_at"));

    // ---------- 保养模板 ----------

    public void insertTemplateHead(String templateId, Instant createdAt) {
        jdbc.update("INSERT INTO maintenance_template_head (template_id, current_version, created_at)"
                        + " VALUES (?, 1, ?)",
                templateId, utc(createdAt));
    }

    /** 模板头当前版本号；不存在时为空。 */
    public Optional<Integer> findTemplateCurrentVersion(String templateId) {
        List<Integer> rows = jdbc.query(
                "SELECT current_version FROM maintenance_template_head WHERE template_id = ?",
                (rs, rowNum) -> rs.getInt("current_version"), templateId);
        return rows.stream().findFirst();
    }

    /** 模板头行锁：模板修订串行化。 */
    public Optional<Integer> lockTemplateHead(String templateId) {
        List<Integer> rows = jdbc.query(
                "SELECT current_version FROM maintenance_template_head WHERE template_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt("current_version"), templateId);
        return rows.stream().findFirst();
    }

    public void updateTemplateCurrentVersion(String templateId, int newVersion) {
        jdbc.update("UPDATE maintenance_template_head SET current_version = ? WHERE template_id = ?",
                newVersion, templateId);
    }

    public void insertTemplateItem(String templateId, int templateVersion, TemplateItem item) {
        jdbc.update("INSERT INTO maintenance_template_item (template_id, template_version, item_code,"
                        + " item_name, required_flag, sort_order) VALUES (?, ?, ?, ?, ?, ?)",
                templateId, templateVersion, item.itemCode(), item.itemName(),
                item.required(), item.sortOrder());
    }

    /** 指定模板版本的检查项，按规范排序 (sort_order, item_code) 升序。 */
    public List<TemplateItem> listTemplateItems(String templateId, int templateVersion) {
        return jdbc.query(
                "SELECT item_code, item_name, required_flag, sort_order FROM maintenance_template_item"
                        + " WHERE template_id = ? AND template_version = ?"
                        + " ORDER BY sort_order ASC, item_code ASC",
                TEMPLATE_ITEM_MAPPER, templateId, templateVersion);
    }

    /** 模板全部版本号（升序）。 */
    public List<Integer> listTemplateVersions(String templateId) {
        return jdbc.query(
                "SELECT DISTINCT template_version FROM maintenance_template_item"
                        + " WHERE template_id = ? ORDER BY template_version ASC",
                (rs, rowNum) -> rs.getInt("template_version"), templateId);
    }

    // ---------- 材料批次 ----------

    public void insertMaterialBatch(String batchCode, String materialName, String requestId, Instant createdAt) {
        jdbc.update("INSERT INTO material_batch (batch_code, material_name, status, request_id, created_at)"
                        + " VALUES (?, ?, 'ACTIVE', ?, ?)",
                batchCode, materialName, requestId, utc(createdAt));
    }

    public Optional<MaterialBatch> findMaterialBatch(String batchCode) {
        List<MaterialBatch> rows = jdbc.query(
                "SELECT batch_code, material_name, status, created_at, revoked_at, revoke_reason"
                        + " FROM material_batch WHERE batch_code = ?",
                MATERIAL_MAPPER, batchCode);
        return rows.stream().findFirst();
    }

    /** 材料批次行锁：撤销操作串行化。 */
    public Optional<MaterialBatch> lockMaterialBatch(String batchCode) {
        List<MaterialBatch> rows = jdbc.query(
                "SELECT batch_code, material_name, status, created_at, revoked_at, revoke_reason"
                        + " FROM material_batch WHERE batch_code = ? FOR UPDATE",
                MATERIAL_MAPPER, batchCode);
        return rows.stream().findFirst();
    }

    public void revokeMaterialBatch(String batchCode, String reason, Instant revokedAt) {
        jdbc.update("UPDATE material_batch SET status = 'REVOKED', revoked_at = ?, revoke_reason = ?"
                        + " WHERE batch_code = ?",
                utc(revokedAt), reason, batchCode);
    }

    // ---------- 证据包 ----------

    public long insertEvidencePackage(String equipmentId, long cycleNo, int packageVersion,
                                      String templateId, int templateVersion, String submittedBy,
                                      String requestId, Instant submittedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO evidence_package (equipment_id, cycle_no, package_version, template_id,"
                            + " template_version, submitted_by, request_id, submitted_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setLong(2, cycleNo);
            ps.setInt(3, packageVersion);
            ps.setString(4, templateId);
            ps.setInt(5, templateVersion);
            ps.setString(6, submittedBy);
            ps.setString(7, requestId);
            ps.setObject(8, utc(submittedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("证据包主键生成失败");
        }
        return key.longValue();
    }

    private static final String PACKAGE_COLUMNS =
            "package_id, equipment_id, cycle_no, package_version, template_id, template_version,"
                    + " submitted_by, submitted_at";

    /** 设备当前周期最新证据包版本。 */
    public Optional<EvidencePackage> findLatestPackage(String equipmentId, long cycleNo) {
        List<EvidencePackage> rows = jdbc.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM evidence_package"
                        + " WHERE equipment_id = ? AND cycle_no = ?"
                        + " ORDER BY package_version DESC LIMIT 1",
                PACKAGE_MAPPER, equipmentId, cycleNo);
        return rows.stream().findFirst();
    }

    public Optional<EvidencePackage> findPackage(String equipmentId, long cycleNo, int packageVersion) {
        List<EvidencePackage> rows = jdbc.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM evidence_package"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND package_version = ?",
                PACKAGE_MAPPER, equipmentId, cycleNo, packageVersion);
        return rows.stream().findFirst();
    }

    /** 设备全部证据包版本（周期升序、版本升序），用于历史查询。 */
    public List<EvidencePackage> listPackages(String equipmentId) {
        return jdbc.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM evidence_package"
                        + " WHERE equipment_id = ? ORDER BY cycle_no ASC, package_version ASC",
                PACKAGE_MAPPER, equipmentId);
    }

    // ---------- 检查项结果 ----------

    public void insertItemResult(long packageId, EvidenceItemResult result) {
        jdbc.update("INSERT INTO evidence_item_result (package_id, item_code, item_name, required_flag,"
                        + " sort_order, result, note) VALUES (?, ?, ?, ?, ?, ?, ?)",
                packageId, result.itemCode(), result.itemName(), result.required(),
                result.sortOrder(), result.result(), result.note());
    }

    /** 证据包检查项结果，按规范排序 (sort_order, item_code) 升序。 */
    public List<EvidenceItemResult> listItemResults(long packageId) {
        return jdbc.query(
                "SELECT package_id, item_code, item_name, required_flag, sort_order, result, note"
                        + " FROM evidence_item_result WHERE package_id = ?"
                        + " ORDER BY sort_order ASC, item_code ASC",
                ITEM_RESULT_MAPPER, packageId);
    }

    // ---------- 材料摘要 ----------

    public void insertEvidenceMaterial(long packageId, String equipmentId, long cycleNo,
                                       EvidenceMaterial material) {
        jdbc.update("INSERT INTO evidence_material (package_id, equipment_id, cycle_no, batch_code,"
                        + " material_name, quantity, unit, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                packageId, equipmentId, cycleNo, material.batchCode(), material.materialName(),
                material.quantity(), material.unit(), material.summary());
    }

    /** 证据包材料摘要，按批次编码升序。 */
    public List<EvidenceMaterial> listEvidenceMaterials(long packageId) {
        return jdbc.query(
                "SELECT package_id, batch_code, material_name, quantity, unit, summary"
                        + " FROM evidence_material WHERE package_id = ? ORDER BY batch_code ASC",
                EVIDENCE_MATERIAL_MAPPER, packageId);
    }

    /** 设备当前周期已使用的材料批次编码（跨证据包版本），用于周期内去重。 */
    public List<String> listCycleMaterialBatchCodes(String equipmentId, long cycleNo) {
        return jdbc.query(
                "SELECT batch_code FROM evidence_material WHERE equipment_id = ? AND cycle_no = ?"
                        + " ORDER BY batch_code ASC",
                (rs, rowNum) -> rs.getString("batch_code"), equipmentId, cycleNo);
    }

    // ---------- 完工确认 ----------

    public long insertConfirmation(String equipmentId, long cycleNo, int packageVersion,
                                   int templateVersion, String confirmedBy, String requestId,
                                   long maintenanceId, Instant confirmedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO completion_confirmation (equipment_id, cycle_no, package_version,"
                            + " template_version, confirmed_by, request_id, maintenance_id, confirmed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setLong(2, cycleNo);
            ps.setInt(3, packageVersion);
            ps.setInt(4, templateVersion);
            ps.setString(5, confirmedBy);
            ps.setString(6, requestId);
            ps.setLong(7, maintenanceId);
            ps.setObject(8, utc(confirmedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("完工确认主键生成失败");
        }
        return key.longValue();
    }

    public Optional<CompletionConfirmation> findConfirmation(String equipmentId, long cycleNo) {
        List<CompletionConfirmation> rows = jdbc.query(
                "SELECT confirmation_id, equipment_id, cycle_no, package_version, template_version,"
                        + " confirmed_by, maintenance_id, confirmed_at"
                        + " FROM completion_confirmation WHERE equipment_id = ? AND cycle_no = ?",
                CONFIRMATION_MAPPER, equipmentId, cycleNo);
        return rows.stream().findFirst();
    }

    // ---------- 更正附录 ----------

    public long insertAppendix(long maintenanceId, String equipmentId, String createdBy,
                               String content, String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO maintenance_appendix (maintenance_id, equipment_id, created_by,"
                            + " content, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, maintenanceId);
            ps.setString(2, equipmentId);
            ps.setString(3, createdBy);
            ps.setString(4, content);
            ps.setString(5, requestId);
            ps.setObject(6, utc(createdAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("更正附录主键生成失败");
        }
        return key.longValue();
    }

    /** 保养记录的更正附录（按创建顺序升序）。 */
    public List<MaintenanceAppendix> listAppendices(long maintenanceId) {
        return jdbc.query(
                "SELECT appendix_id, maintenance_id, equipment_id, created_by, content, created_at"
                        + " FROM maintenance_appendix WHERE maintenance_id = ? ORDER BY appendix_id ASC",
                APPENDIX_MAPPER, maintenanceId);
    }
}
