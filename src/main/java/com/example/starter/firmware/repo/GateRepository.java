package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleaseGate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 正式投放门禁数据访问。uk_gate_model_version 保证同型号同目标版本至多一条结论，结论只增不改。
 */
@Repository
public class GateRepository {

    private static final RowMapper<ReleaseGate> MAPPER = (rs, rowNum) -> new ReleaseGate(
            rs.getLong("id"), rs.getString("model"), rs.getString("target_version"),
            rs.getString("status"), rs.getLong("rehearsal_id"), rs.getInt("required_device_count"),
            rs.getInt("actual_success_count"), rs.getString("granted_at_utc"));

    private static final String COLUMNS = "id, model, target_version, status, rehearsal_id,"
            + " required_device_count, actual_success_count, granted_at_utc";

    private final JdbcTemplate jdbc;

    public GateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String targetVersion, long rehearsalId, int requiredDeviceCount,
                       int actualSuccessCount, String grantedAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_gate (model, target_version, status, rehearsal_id,"
                            + " required_device_count, actual_success_count, granted_at_utc)"
                            + " VALUES (?, ?, 'READY_FOR_RELEASE', ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, targetVersion);
            ps.setLong(3, rehearsalId);
            ps.setInt(4, requiredDeviceCount);
            ps.setInt(5, actualSuccessCount);
            ps.setString(6, grantedAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReleaseGate> findByModelVersion(String model, String targetVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_gate WHERE model = ? AND target_version = ?",
                MAPPER, model, targetVersion).stream().findFirst();
    }

    public List<ReleaseGate> findAll(String model, String targetVersion) {
        if (model == null && targetVersion == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM release_gate ORDER BY id", MAPPER);
        }
        if (model != null && targetVersion != null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM release_gate WHERE model = ? AND target_version = ?"
                    + " ORDER BY id", MAPPER, model, targetVersion);
        }
        if (model != null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM release_gate WHERE model = ? ORDER BY id",
                    MAPPER, model);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM release_gate WHERE target_version = ? ORDER BY id",
                MAPPER, targetVersion);
    }
}
