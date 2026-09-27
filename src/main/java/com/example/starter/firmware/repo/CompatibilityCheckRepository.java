package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.BlockedModule;
import com.example.starter.firmware.domain.CompatibilityCheck;
import com.example.starter.firmware.domain.CompatibilityOutcome;
import com.example.starter.firmware.domain.ModuleSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 兼容判定证据数据访问。每次下发、阻断或豁免放行插入一条不可变记录；
 * request_id 唯一，同参重放由幂等层重放首次响应，不新增记录。
 */
@Repository
public class CompatibilityCheckRepository {

    private static final TypeReference<List<ModuleSnapshot>> SNAPSHOTS = new TypeReference<>() {
    };
    private static final TypeReference<List<BlockedModule>> BLOCKED = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> MODULE_NAMES = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CompatibilityCheckRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long insert(CompatibilityCheck check) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO compatibility_check (release_id, device_id, outcome, matrix_version,"
                            + " exemption_version, device_modules, blocked_items, exempted_modules,"
                            + " request_id, checked_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, check.releaseId());
            ps.setString(2, check.deviceId());
            ps.setString(3, check.outcome().name());
            ps.setInt(4, check.matrixVersion());
            ps.setInt(5, check.exemptionVersion());
            ps.setString(6, write(check.deviceModules()));
            ps.setString(7, check.blockedModules().isEmpty() ? null : write(check.blockedModules()));
            ps.setString(8, write(check.exemptedModules()));
            ps.setString(9, check.requestId());
            ps.setString(10, check.checkedAtUtc());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<CompatibilityCheck> findById(long id) {
        return jdbc.query("SELECT * FROM compatibility_check WHERE id = ?", (rs, n) -> new CompatibilityCheck(
                rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                CompatibilityOutcome.valueOf(rs.getString("outcome")),
                rs.getInt("matrix_version"), rs.getInt("exemption_version"),
                read(rs.getString("device_modules"), SNAPSHOTS),
                readNullable(rs.getString("blocked_items"), BLOCKED),
                read(rs.getString("exempted_modules"), MODULE_NAMES),
                rs.getString("request_id"), rs.getString("checked_at_utc")), id).stream().findFirst();
    }

    public List<CompatibilityCheck> findByDevice(String deviceId) {
        return jdbc.query("SELECT * FROM compatibility_check WHERE device_id = ? ORDER BY id",
                (rs, n) -> mapRow(rs), deviceId);
    }

    public List<CompatibilityCheck> findByRelease(long releaseId) {
        return jdbc.query("SELECT * FROM compatibility_check WHERE release_id = ? ORDER BY id",
                (rs, n) -> mapRow(rs), releaseId);
    }

    private CompatibilityCheck mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CompatibilityCheck(
                rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                CompatibilityOutcome.valueOf(rs.getString("outcome")),
                rs.getInt("matrix_version"), rs.getInt("exemption_version"),
                read(rs.getString("device_modules"), SNAPSHOTS),
                readNullable(rs.getString("blocked_items"), BLOCKED),
                read(rs.getString("exempted_modules"), MODULE_NAMES),
                rs.getString("request_id"), rs.getString("checked_at_utc"));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("兼容证据序列化失败", e);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("兼容证据反序列化失败", e);
        }
    }

    private <T> List<T> readNullable(String json, TypeReference<List<T>> type) {
        return json == null ? List.of() : read(json, type);
    }
}
