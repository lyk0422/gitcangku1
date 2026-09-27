package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * MODULE_BLOCKED 阻断诊断记录数据访问。只增不改、只查不写状态。
 */
@Repository
public class BlockRecordRepository {

    public record BlockRow(long id, long releaseId, String deviceId, String requestId,
                           Integer matrixVersion, Integer exemptionVersion, String deviceSummary,
                           String missingModulesJson, String blockedAtUtc) {
    }

    private static final org.springframework.jdbc.core.RowMapper<BlockRow> MAPPER = (rs, n) ->
            new BlockRow(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                    rs.getString("request_id"), rs.getObject("matrix_version", Integer.class),
                    rs.getObject("exemption_version", Integer.class), rs.getString("device_summary"),
                    rs.getString("missing_modules"), rs.getString("blocked_at_utc"));

    private final JdbcTemplate jdbc;

    public BlockRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId, String requestId, Integer matrixVersion,
                       Integer exemptionVersion, String deviceSummary,
                       String missingModulesJson, String blockedAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO module_block_record (release_id, device_id, request_id, matrix_version,"
                            + " exemption_version, device_summary, missing_modules, blocked_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setString(3, requestId);
            ps.setObject(4, matrixVersion);
            ps.setObject(5, exemptionVersion);
            ps.setString(6, deviceSummary);
            ps.setString(7, missingModulesJson);
            ps.setString(8, blockedAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<BlockRow> findByRelease(long releaseId) {
        return jdbc.query("SELECT id, release_id, device_id, request_id, matrix_version, exemption_version,"
                + " device_summary, missing_modules, blocked_at_utc FROM module_block_record"
                + " WHERE release_id = ? ORDER BY id", MAPPER, releaseId);
    }

    public List<BlockRow> findByDevice(String deviceId) {
        return jdbc.query("SELECT id, release_id, device_id, request_id, matrix_version, exemption_version,"
                + " device_summary, missing_modules, blocked_at_utc FROM module_block_record"
                + " WHERE device_id = ? ORDER BY id", MAPPER, deviceId);
    }
}
