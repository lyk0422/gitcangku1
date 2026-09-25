package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CompatMatrix;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 固件硬件兼容矩阵数据访问。按固件版本唯一，models 以字典序 JSON 数组存储，
 * 空数组 [] 表示兼容全部硬件型号；行锁保证矩阵修改与拉取/回执/暂停按提交顺序裁决。
 */
@Repository
public class CompatMatrixRepository {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CompatMatrixRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<CompatMatrix> findByFirmware(String firmwareVersion) {
        return jdbc.query("SELECT firmware_version, version, models FROM firmware_compat_matrix"
                        + " WHERE firmware_version = ?",
                (rs, n) -> mapRow(rs.getString("firmware_version"), rs.getInt("version"),
                        rs.getString("models")),
                firmwareVersion).stream().findFirst();
    }

    public Optional<CompatMatrix> findByFirmwareForUpdate(String firmwareVersion) {
        return jdbc.query("SELECT firmware_version, version, models FROM firmware_compat_matrix"
                        + " WHERE firmware_version = ? FOR UPDATE",
                (rs, n) -> mapRow(rs.getString("firmware_version"), rs.getInt("version"),
                        rs.getString("models")),
                firmwareVersion).stream().findFirst();
    }

    /**
     * 首次配置：版本从 1 开始。
     */
    public void insert(String firmwareVersion, List<String> sortedModels) {
        jdbc.update("INSERT INTO firmware_compat_matrix (firmware_version, version, models)"
                        + " VALUES (?, 1, ?)",
                firmwareVersion, toJson(sortedModels));
    }

    /**
     * 修改配置：仅当版本匹配时版本加一并更新型号集合，返回影响行数（乐观并发兜底）。
     */
    public int update(String firmwareVersion, int expectedVersion, List<String> sortedModels) {
        return jdbc.update("UPDATE firmware_compat_matrix SET version = version + 1, models = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE firmware_version = ? AND version = ?",
                toJson(sortedModels), firmwareVersion, expectedVersion);
    }

    private CompatMatrix mapRow(String firmwareVersion, int version, String modelsJson) {
        try {
            return new CompatMatrix(firmwareVersion, version, objectMapper.readValue(modelsJson, LIST_TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("兼容矩阵型号集合反序列化失败: " + firmwareVersion, e);
        }
    }

    private String toJson(List<String> sortedModels) {
        try {
            return objectMapper.writeValueAsString(sortedModels);
        } catch (Exception e) {
            throw new IllegalStateException("兼容矩阵型号集合序列化失败", e);
        }
    }
}
