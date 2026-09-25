package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.PathBlockedRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * PATH_BLOCKED 判定历史数据访问。历史只增不改，不计入失败率样本。
 */
@Repository
public class PathBlockedRecordRepository {

    private static final RowMapper<PathBlockedRecord> MAPPER = (rs, rowNum) -> new PathBlockedRecord(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getString("device_version"), rs.getString("target_version"),
            rs.getString("next_version"), rs.getString("blocked_at_utc"));

    private static final String COLUMNS = "id, release_id, device_id, device_version, target_version,"
            + " next_version, blocked_at_utc";

    private final JdbcTemplate jdbc;

    public PathBlockedRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long releaseId, String deviceId, String deviceVersion, String targetVersion,
                       String nextVersion, String blockedAtUtc) {
        jdbc.update("INSERT INTO path_blocked_record"
                        + " (release_id, device_id, device_version, target_version, next_version, blocked_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                releaseId, deviceId, deviceVersion, targetVersion, nextVersion, blockedAtUtc);
    }

    public List<PathBlockedRecord> findByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM path_blocked_record WHERE release_id = ? ORDER BY id",
                MAPPER, releaseId);
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM path_blocked_record WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }
}
