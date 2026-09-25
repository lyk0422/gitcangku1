package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FirmwareCompat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 固件硬件兼容矩阵数据访问。allowed_models 以升序去重的 JSON 数组存储，
 * 矩阵修改通过 SELECT ... FOR UPDATE 行锁与版本校验串行化。
 */
@Repository
public class FirmwareCompatRepository {

    private static final TypeReference<List<String>> MODEL_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FirmwareCompatRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<FirmwareCompat> find(String firmwareVersion) {
        return jdbc.query("SELECT firmware_version, matrix_version, allowed_models"
                        + " FROM firmware_compat WHERE firmware_version = ?",
                (rs, rowNum) -> new FirmwareCompat(rs.getString("firmware_version"),
                        rs.getInt("matrix_version"), readModels(rs.getString("allowed_models"))),
                firmwareVersion).stream().findFirst();
    }

    public Optional<FirmwareCompat> findForUpdate(String firmwareVersion) {
        return jdbc.query("SELECT firmware_version, matrix_version, allowed_models"
                        + " FROM firmware_compat WHERE firmware_version = ? FOR UPDATE",
                (rs, rowNum) -> new FirmwareCompat(rs.getString("firmware_version"),
                        rs.getInt("matrix_version"), readModels(rs.getString("allowed_models"))),
                firmwareVersion).stream().findFirst();
    }

    /**
     * 首次配置：矩阵版本从 1 开始。
     */
    public void insert(String firmwareVersion, List<String> allowedModels) {
        jdbc.update("INSERT INTO firmware_compat (firmware_version, matrix_version, allowed_models)"
                + " VALUES (?, 1, ?)", firmwareVersion, writeModels(allowedModels));
    }

    /**
     * 乐观修改：仅当当前矩阵版本匹配时生效，版本加一，返回影响行数。
     */
    public int update(String firmwareVersion, int expectedVersion, List<String> allowedModels) {
        return jdbc.update("UPDATE firmware_compat SET matrix_version = matrix_version + 1,"
                        + " allowed_models = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE firmware_version = ? AND matrix_version = ?",
                writeModels(allowedModels), firmwareVersion, expectedVersion);
    }

    private List<String> readModels(String json) {
        try {
            return objectMapper.readValue(json, MODEL_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("兼容矩阵反序列化失败", e);
        }
    }

    private String writeModels(List<String> models) {
        try {
            return objectMapper.writeValueAsString(models);
        } catch (Exception e) {
            throw new IllegalStateException("兼容矩阵序列化失败", e);
        }
    }
}
