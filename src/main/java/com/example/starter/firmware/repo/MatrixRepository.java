package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareMatrix;
import com.example.starter.firmware.domain.ModuleDependencyEdge;
import com.example.starter.firmware.domain.ModuleRequirement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 固件模块需求矩阵与模块依赖图数据访问。
 * 矩阵按固件版本全量替换需求行；依赖边全局唯一，重复登记取更高最小版本。
 */
@Repository
public class MatrixRepository {

    private final JdbcTemplate jdbc;

    public MatrixRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 持有模块依赖图命名锁至事务结束，串行化所有矩阵登记，保证环检测看到完整后态、版本按提交顺序递增。
     */
    public void lockGraph() {
        jdbc.queryForObject("SELECT lock_name FROM schema_lock WHERE lock_name = 'module_dependency_graph'"
                + " FOR UPDATE", String.class);
    }

    public Optional<FirmwareMatrix> findMatrix(String firmwareVersion) {
        return jdbc.query("SELECT firmware_version, matrix_version, updated_at_utc FROM firmware_matrix"
                        + " WHERE firmware_version = ?",
                (rs, n) -> new FirmwareMatrix(rs.getString("firmware_version"),
                        rs.getInt("matrix_version"), rs.getString("updated_at_utc")),
                firmwareVersion).stream().findFirst();
    }

    /**
     * 登记新矩阵版本：不存在则插入版本 1，存在则版本加一，返回新版本号。调用方在事务内先做完整校验。
     */
    public int upsertMatrixVersion(String firmwareVersion, String updatedAtUtc) {
        int updated = jdbc.update("UPDATE firmware_matrix SET matrix_version = matrix_version + 1,"
                + " updated_at_utc = ? WHERE firmware_version = ?", updatedAtUtc, firmwareVersion);
        if (updated == 0) {
            jdbc.update("INSERT INTO firmware_matrix (firmware_version, matrix_version, updated_at_utc)"
                    + " VALUES (?, 1, ?)", firmwareVersion, updatedAtUtc);
            return 1;
        }
        return jdbc.queryForObject("SELECT matrix_version FROM firmware_matrix WHERE firmware_version = ?",
                Integer.class, firmwareVersion);
    }

    public void replaceRequirements(String firmwareVersion, List<ModuleRequirement> requirements) {
        jdbc.update("DELETE FROM firmware_module_requirement WHERE firmware_version = ?", firmwareVersion);
        jdbc.batchUpdate("INSERT INTO firmware_module_requirement (firmware_version, module_name, min_version)"
                + " VALUES (?, ?, ?)", requirements, requirements.size(), (ps, req) -> {
            ps.setString(1, firmwareVersion);
            ps.setString(2, req.moduleName());
            ps.setString(3, req.minVersion());
        });
    }

    public List<ModuleRequirement> findRequirements(String firmwareVersion) {
        return jdbc.query("SELECT firmware_version, module_name, min_version"
                + " FROM firmware_module_requirement WHERE firmware_version = ? ORDER BY module_name",
                (rs, n) -> new ModuleRequirement(rs.getString("firmware_version"),
                        rs.getString("module_name"), rs.getString("min_version")),
                firmwareVersion);
    }

    public List<ModuleDependencyEdge> findAllEdges() {
        return jdbc.query("SELECT module_name, depends_on, min_version FROM module_dependency",
                (rs, n) -> new ModuleDependencyEdge(rs.getString("module_name"),
                        rs.getString("depends_on"), rs.getString("min_version")));
    }

    public Optional<ModuleDependencyEdge> findEdge(String moduleName, String dependsOn) {
        return jdbc.query("SELECT module_name, depends_on, min_version FROM module_dependency"
                        + " WHERE module_name = ? AND depends_on = ?",
                (rs, n) -> new ModuleDependencyEdge(rs.getString("module_name"),
                        rs.getString("depends_on"), rs.getString("min_version")),
                moduleName, dependsOn).stream().findFirst();
    }

    /**
     * 插入新依赖边。同边已存在（含并发登记）时抛唯一约束冲突，由调用方决定是否抬高版本。
     */
    public void insertEdge(ModuleDependencyEdge edge) {
        jdbc.update("INSERT INTO module_dependency (module_name, depends_on, min_version)"
                + " VALUES (?, ?, ?)", edge.moduleName(), edge.dependsOn(), edge.minVersion());
    }

    public void updateEdgeMinVersion(ModuleDependencyEdge edge) {
        jdbc.update("UPDATE module_dependency SET min_version = ? WHERE module_name = ? AND depends_on = ?",
                edge.minVersion(), edge.moduleName(), edge.dependsOn());
    }
}
