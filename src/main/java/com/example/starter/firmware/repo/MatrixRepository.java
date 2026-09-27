package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareRequirement;
import com.example.starter.firmware.domain.MatrixRevision;
import com.example.starter.firmware.domain.ModuleDependency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 模块兼容矩阵数据访问：固件需求与模块依赖为当前态（登记时覆盖），
 * 矩阵版本单行递增，登记历史只增不改。
 */
@Repository
public class MatrixRepository {

    private static final RowMapper<FirmwareRequirement> REQUIREMENT_MAPPER = (rs, rowNum) ->
            new FirmwareRequirement(rs.getString("firmware_version"), rs.getString("module"),
                    rs.getString("min_version"));

    private static final RowMapper<ModuleDependency> DEPENDENCY_MAPPER = (rs, rowNum) ->
            new ModuleDependency(rs.getString("module"), rs.getString("depends_on"),
                    rs.getString("min_version"));

    private static final RowMapper<MatrixRevision> REVISION_MAPPER = (rs, rowNum) ->
            new MatrixRevision(rs.getInt("version"), rs.getInt("requirement_count"),
                    rs.getInt("dependency_count"), rs.getString("registered_at_utc"));

    private final JdbcTemplate jdbc;

    public MatrixRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 当前矩阵版本，未登记过为 0。
     */
    public int currentVersion() {
        List<Integer> versions = jdbc.query("SELECT version FROM compat_matrix_meta WHERE id = 1",
                (rs, rowNum) -> rs.getInt("version"));
        return versions.isEmpty() ? 0 : versions.get(0);
    }

    /**
     * 锁定矩阵元信息行（SELECT ... FOR UPDATE）：登记在事务开始时调用，
     * 使并发登记串行化，依赖环校验与合并写入基于一致的图状态。
     */
    public void lockMetaForUpdate() {
        jdbc.query("SELECT version FROM compat_matrix_meta WHERE id = 1 FOR UPDATE",
                (rs, rowNum) -> rs.getInt("version"));
    }

    /**
     * 锁定矩阵元信息行并返回当前版本：拉取乐观快照多次不一致时的兜底一致读。
     */
    public int currentVersionForUpdate() {
        List<Integer> versions = jdbc.query(
                "SELECT version FROM compat_matrix_meta WHERE id = 1 FOR UPDATE",
                (rs, rowNum) -> rs.getInt("version"));
        return versions.isEmpty() ? 0 : versions.get(0);
    }

    /**
     * 矩阵版本加一并返回新版本；首次登记时建行。调用方须在事务内。
     */
    public int bumpVersion() {
        int updated = jdbc.update("UPDATE compat_matrix_meta SET version = version + 1 WHERE id = 1");
        if (updated == 0) {
            jdbc.update("INSERT INTO compat_matrix_meta (id, version) VALUES (1, 1)");
        }
        return currentVersion();
    }

    public void upsertRequirement(FirmwareRequirement requirement) {
        jdbc.update("MERGE INTO firmware_requirement (firmware_version, module, min_version, updated_at)"
                        + " KEY (firmware_version, module) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                requirement.firmwareVersion(), requirement.module(), requirement.minVersion());
    }

    public void upsertDependency(ModuleDependency dependency) {
        jdbc.update("MERGE INTO module_dependency (module, depends_on, min_version, updated_at)"
                        + " KEY (module, depends_on) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                dependency.module(), dependency.dependsOn(), dependency.minVersion());
    }

    /**
     * 全部固件需求，按（固件版本，模块）升序，排序稳定。
     */
    public List<FirmwareRequirement> findAllRequirements() {
        return jdbc.query("SELECT firmware_version, module, min_version FROM firmware_requirement"
                + " ORDER BY firmware_version, module", REQUIREMENT_MAPPER);
    }

    /**
     * 指定固件版本的直接需求，按模块名升序。
     */
    public List<FirmwareRequirement> findRequirementsByFirmware(String firmwareVersion) {
        return jdbc.query("SELECT firmware_version, module, min_version FROM firmware_requirement"
                + " WHERE firmware_version = ? ORDER BY module", REQUIREMENT_MAPPER, firmwareVersion);
    }

    /**
     * 全部模块依赖边，按（模块，依赖模块）升序，排序稳定。
     */
    public List<ModuleDependency> findAllDependencies() {
        return jdbc.query("SELECT module, depends_on, min_version FROM module_dependency"
                + " ORDER BY module, depends_on", DEPENDENCY_MAPPER);
    }

    public void insertRevision(MatrixRevision revision) {
        jdbc.update("INSERT INTO compat_matrix_revision"
                        + " (version, requirement_count, dependency_count, registered_at_utc)"
                        + " VALUES (?, ?, ?, ?)",
                revision.version(), revision.requirementCount(), revision.dependencyCount(),
                revision.registeredAtUtc());
    }

    /**
     * 登记历史，按版本升序。
     */
    public List<MatrixRevision> findAllRevisions() {
        return jdbc.query("SELECT version, requirement_count, dependency_count, registered_at_utc"
                + " FROM compat_matrix_revision ORDER BY version", REVISION_MAPPER);
    }
}
