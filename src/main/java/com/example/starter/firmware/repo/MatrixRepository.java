package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareRequirement;
import com.example.starter.firmware.domain.ModuleDependency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 兼容矩阵数据访问：单行版本状态、固件模块需求、模块间依赖边。
 * 登记成功一次矩阵版本加一；失败回滚，版本不增加。
 */
@Repository
public class MatrixRepository {

    private final JdbcTemplate jdbc;

    public MatrixRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取当前矩阵版本（无登记为0）。
     */
    public int currentVersion() {
        Integer version = jdbc.queryForObject("SELECT version FROM matrix_state WHERE id = 0", Integer.class);
        return version == null ? 0 : version;
    }

    public Optional<String> updatedAtUtc() {
        return jdbc.query("SELECT updated_at_utc FROM matrix_state WHERE id = 0",
                (rs, n) -> Optional.ofNullable(rs.getString("updated_at_utc"))).stream().findFirst()
                .orElse(Optional.empty());
    }

    /**
     * 版本加一并记录登记时刻，调用方必须已完成全部矩阵行写入（同一事务）。
     */
    public void bumpVersion(String nowUtc) {
        jdbc.update("UPDATE matrix_state SET version = version + 1, updated_at_utc = ? WHERE id = 0", nowUtc);
    }

    /**
     * 登记或覆盖一条固件模块需求（同 型号+固件版本+模块 唯一）。
     */
    public void upsertFirmwareRequirement(String model, String firmwareVersion, String moduleName,
                                          String minVersion, String nowUtc) {
        jdbc.update("MERGE INTO firmware_requirement"
                        + " (model, firmware_version, module_name, min_version, updated_at_utc)"
                        + " KEY (model, firmware_version, module_name) VALUES (?, ?, ?, ?, ?)",
                model, firmwareVersion, moduleName, minVersion, nowUtc);
    }

    /**
     * 登记或覆盖一条模块依赖边。
     */
    public void upsertModuleDependency(String moduleName, String dependsOn, String minVersion, String nowUtc) {
        jdbc.update("MERGE INTO module_dependency (module_name, depends_on, min_version, updated_at_utc)"
                        + " KEY (module_name, depends_on) VALUES (?, ?, ?, ?)",
                moduleName, dependsOn, minVersion, nowUtc);
    }

    public List<FirmwareRequirement> findFirmwareRequirements(String model, String firmwareVersion) {
        return jdbc.query("SELECT model, firmware_version, module_name, min_version, updated_at_utc"
                        + " FROM firmware_requirement WHERE model = ? AND firmware_version = ?",
                (rs, n) -> new FirmwareRequirement(rs.getString("model"), rs.getString("firmware_version"),
                        rs.getString("module_name"), rs.getString("min_version"), rs.getString("updated_at_utc")),
                model, firmwareVersion);
    }

    public List<ModuleDependency> findAllDependencies() {
        return jdbc.query("SELECT module_name, depends_on, min_version, updated_at_utc FROM module_dependency",
                (rs, n) -> new ModuleDependency(rs.getString("module_name"), rs.getString("depends_on"),
                        rs.getString("min_version"), rs.getString("updated_at_utc")));
    }

    public List<FirmwareRequirement> findAllFirmwareRequirements() {
        return jdbc.query("SELECT model, firmware_version, module_name, min_version, updated_at_utc"
                        + " FROM firmware_requirement ORDER BY model, firmware_version, module_name",
                (rs, n) -> new FirmwareRequirement(rs.getString("model"), rs.getString("firmware_version"),
                        rs.getString("module_name"), rs.getString("min_version"), rs.getString("updated_at_utc")));
    }

    public long countFirmwareRequirements() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM firmware_requirement", Long.class);
        return count == null ? 0 : count;
    }

    public long countModuleDependencies() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM module_dependency", Long.class);
        return count == null ? 0 : count;
    }
}
