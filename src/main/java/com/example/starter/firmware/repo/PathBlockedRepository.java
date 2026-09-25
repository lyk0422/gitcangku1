package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.PathBlockedRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * PATH_BLOCKED 拦截历史数据访问。记录只增不改，与拉取判定在同一事务提交。
 */
@Repository
public class PathBlockedRepository {

    private static final RowMapper<PathBlockedRecord> MAPPER = (rs, rowNum) -> new PathBlockedRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getString("current_version"), rs.getString("required_version"),
            rs.getString("target_version"), rs.getString("blocked_at_utc"));

    private final JdbcTemplate jdbc;

    public PathBlockedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, String deviceId, String currentVersion,
                       String requiredVersion, String targetVersion, String blockedAtUtc) {
        jdbc.update("INSERT INTO path_blocked_record"
                        + " (release_id, device_id, current_version, required_version, target_version, blocked_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, deviceId, currentVersion, requiredVersion, targetVersion, blockedAtUtc);
    }

    public List<PathBlockedRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT id, release_id, device_id, current_version, required_version,"
                        + " target_version, blocked_at_utc FROM path_blocked_record"
                        + " WHERE release_id = ? ORDER BY id", MAPPER, releaseId);
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM path_blocked_record WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }
}
