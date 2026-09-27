package com.example.starter.maintenance.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.EvidenceAppendix;
import com.example.starter.maintenance.domain.EvidenceMaterial;
import com.example.starter.maintenance.domain.EvidencePackage;
import com.example.starter.maintenance.domain.EvidenceResult;
import com.example.starter.maintenance.domain.MaintenanceConfirmation;

/**
 * 证据包数据访问层。证据包头/结果/材料摘要/确认/附录均只追加不可变；
 * 版本 V 的最终包 = 该周期内 package_version &lt;= V 的最新检查项结果与全部材料摘要。
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

    // ---------- 证据包头 ----------

    /** 周期内下一个证据包版本号（无版本时为 1）。 */
    public int nextPackageVersion(String equipmentId, int cycleNo) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(package_version), 0) FROM evidence_package"
                        + " WHERE equipment_id = ? AND cycle_no = ?",
                Integer.class, equipmentId, cycleNo);
        return (max == null ? 0 : max) + 1;
    }

    public void insertPackage(EvidencePackage evidencePackage, String requestId) {
        jdbc.update("INSERT INTO evidence_package (equipment_id, cycle_no, package_version,"
                        + " template_version, submitted_by, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                evidencePackage.equipmentId(), evidencePackage.cycleNo(),
                evidencePackage.packageVersion(), evidencePackage.templateVersion(),
                evidencePackage.submittedBy(), requestId, utc(evidencePackage.createdAt()));
    }

    public Optional<EvidencePackage> findPackage(String equipmentId, int cycleNo, int packageVersion) {
        List<EvidencePackage> rows = jdbc.query(
                "SELECT equipment_id, cycle_no, package_version, template_version, submitted_by, created_at"
                        + " FROM evidence_package WHERE equipment_id = ? AND cycle_no = ? AND package_version = ?",
                PACKAGE_MAPPER, equipmentId, cycleNo, packageVersion);
        return rows.stream().findFirst();
    }

    /** 周期内最新证据包版本；无证据时为空。 */
    public Optional<EvidencePackage> findLatestPackage(String equipmentId, int cycleNo) {
        List<EvidencePackage> rows = jdbc.query(
                "SELECT equipment_id, cycle_no, package_version, template_version, submitted_by, created_at"
                        + " FROM evidence_package WHERE equipment_id = ? AND cycle_no = ?"
                        + " ORDER BY package_version DESC LIMIT 1",
                PACKAGE_MAPPER, equipmentId, cycleNo);
        return rows.stream().findFirst();
    }

    /** 周期内全部证据包头（按版本升序）。 */
    public List<EvidencePackage> listPackages(String equipmentId, int cycleNo) {
        return jdbc.query(
                "SELECT equipment_id, cycle_no, package_version, template_version, submitted_by, created_at"
                        + " FROM evidence_package WHERE equipment_id = ? AND cycle_no = ?"
                        + " ORDER BY package_version ASC",
                PACKAGE_MAPPER, equipmentId, cycleNo);
    }

    private static final org.springframework.jdbc.core.RowMapper<EvidencePackage> PACKAGE_MAPPER = (rs, n) ->
            new EvidencePackage(
                    rs.getString("equipment_id"),
                    rs.getInt("cycle_no"),
                    rs.getInt("package_version"),
                    rs.getInt("template_version"),
                    rs.getString("submitted_by"),
                    readInstant(rs, "created_at"));

    // ---------- 检查项结果 ----------

    public void insertResult(EvidenceResult result, String requestId) {
        jdbc.update("INSERT INTO evidence_check_result (equipment_id, cycle_no, package_version, ordinal,"
                        + " item_code, result, remark, submitted_by, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                result.equipmentId(), result.cycleNo(), result.packageVersion(), result.ordinal(),
                result.itemCode(), result.pass() ? EvidenceResult.PASS : EvidenceResult.FAIL,
                result.remark(), result.submittedBy(), requestId, utc(result.createdAt()));
    }

    /**
     * 版本 asOfVersion 的最终检查项结果：每项取 package_version 最大（&lt;= asOfVersion）的一行，
     * 按模板 ordinal 升序。
     */
    public List<EvidenceResult> listResultsAsOf(String equipmentId, int cycleNo, int asOfVersion) {
        return jdbc.query(
                "SELECT r.id, r.equipment_id, r.cycle_no, r.package_version, r.ordinal, r.item_code,"
                        + " r.result, r.remark, r.submitted_by, r.created_at"
                        + " FROM evidence_check_result r"
                        + " JOIN (SELECT item_code, MAX(package_version) AS max_version"
                        + " FROM evidence_check_result"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND package_version <= ?"
                        + " GROUP BY item_code) m"
                        + " ON r.item_code = m.item_code AND r.package_version = m.max_version"
                        + " WHERE r.equipment_id = ? AND r.cycle_no = ?"
                        + " ORDER BY r.ordinal ASC, r.item_code ASC",
                RESULT_MAPPER, equipmentId, cycleNo, asOfVersion, equipmentId, cycleNo);
    }

    private static final org.springframework.jdbc.core.RowMapper<EvidenceResult> RESULT_MAPPER = (rs, n) ->
            new EvidenceResult(
                    rs.getLong("id"),
                    rs.getString("equipment_id"),
                    rs.getInt("cycle_no"),
                    rs.getInt("package_version"),
                    rs.getInt("ordinal"),
                    rs.getString("item_code"),
                    EvidenceResult.PASS.equals(rs.getString("result")),
                    rs.getString("remark"),
                    rs.getString("submitted_by"),
                    readInstant(rs, "created_at"));

    // ---------- 材料摘要 ----------

    public void insertMaterial(EvidenceMaterial material, String requestId) {
        jdbc.update("INSERT INTO evidence_material (equipment_id, cycle_no, batch_code, added_version,"
                        + " quantity, unit, submitted_by, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                material.equipmentId(), material.cycleNo(), material.batchCode(),
                material.addedVersion(), material.quantity(), material.unit(),
                material.submittedBy(), requestId, utc(material.createdAt()));
    }

    public boolean existsMaterialInCycle(String equipmentId, int cycleNo, String batchCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_material"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND batch_code = ?",
                Integer.class, equipmentId, cycleNo, batchCode);
        return count != null && count > 0;
    }

    /** 版本 asOfVersion 的最终材料摘要：added_version &lt;= asOfVersion，按进入版本、批次编码升序。 */
    public List<EvidenceMaterial> listMaterialsAsOf(String equipmentId, int cycleNo, int asOfVersion) {
        return jdbc.query(
                "SELECT id, equipment_id, cycle_no, batch_code, added_version, quantity, unit,"
                        + " submitted_by, created_at FROM evidence_material"
                        + " WHERE equipment_id = ? AND cycle_no = ? AND added_version <= ?"
                        + " ORDER BY added_version ASC, batch_code ASC",
                MATERIAL_MAPPER, equipmentId, cycleNo, asOfVersion);
    }

    private static final org.springframework.jdbc.core.RowMapper<EvidenceMaterial> MATERIAL_MAPPER = (rs, n) ->
            new EvidenceMaterial(
                    rs.getLong("id"),
                    rs.getString("equipment_id"),
                    rs.getInt("cycle_no"),
                    rs.getString("batch_code"),
                    rs.getInt("added_version"),
                    rs.getLong("quantity"),
                    rs.getString("unit"),
                    rs.getString("submitted_by"),
                    readInstant(rs, "created_at"));

    // ---------- 双人完工确认 ----------

    public void insertConfirmation(MaintenanceConfirmation confirmation, String requestId) {
        jdbc.update("INSERT INTO maintenance_confirmation (maintenance_id, equipment_id, cycle_no,"
                        + " template_version, package_version, submitter_id, confirmer_id,"
                        + " request_id, confirmed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                confirmation.maintenanceId(), confirmation.equipmentId(), confirmation.cycleNo(),
                confirmation.templateVersion(), confirmation.packageVersion(),
                confirmation.submitterId(), confirmation.confirmerId(),
                requestId, utc(confirmation.confirmedAt()));
    }

    public Optional<MaintenanceConfirmation> findConfirmationByMaintenance(long maintenanceId) {
        List<MaintenanceConfirmation> rows = jdbc.query(
                "SELECT maintenance_id, equipment_id, cycle_no, template_version, package_version,"
                        + " submitter_id, confirmer_id, confirmed_at"
                        + " FROM maintenance_confirmation WHERE maintenance_id = ?",
                CONFIRMATION_MAPPER, maintenanceId);
        return rows.stream().findFirst();
    }

    private static final org.springframework.jdbc.core.RowMapper<MaintenanceConfirmation> CONFIRMATION_MAPPER =
            (rs, n) -> new MaintenanceConfirmation(
                    rs.getLong("maintenance_id"),
                    rs.getString("equipment_id"),
                    rs.getInt("cycle_no"),
                    rs.getInt("template_version"),
                    rs.getInt("package_version"),
                    rs.getString("submitter_id"),
                    rs.getString("confirmer_id"),
                    readInstant(rs, "confirmed_at"));

    // ---------- 更正附录 ----------

    public long insertAppendix(String equipmentId, int cycleNo, String operatorId, String note,
                               String requestId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO evidence_appendix (equipment_id, cycle_no, operator_id, note,"
                            + " request_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, equipmentId);
            ps.setInt(2, cycleNo);
            ps.setString(3, operatorId);
            ps.setString(4, note);
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

    public List<EvidenceAppendix> listAppendices(String equipmentId, int cycleNo) {
        return jdbc.query(
                "SELECT id, equipment_id, cycle_no, operator_id, note, created_at"
                        + " FROM evidence_appendix WHERE equipment_id = ? AND cycle_no = ? ORDER BY id ASC",
                (rs, n) -> new EvidenceAppendix(
                        rs.getLong("id"),
                        rs.getString("equipment_id"),
                        rs.getInt("cycle_no"),
                        rs.getString("operator_id"),
                        rs.getString("note"),
                        readInstant(rs, "created_at")),
                equipmentId, cycleNo);
    }
}
