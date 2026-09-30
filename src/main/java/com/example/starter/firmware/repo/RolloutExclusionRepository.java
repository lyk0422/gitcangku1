package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ExclusionReason;
import com.example.starter.firmware.domain.RolloutExclusion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 硬件不兼容排除记录数据访问。MERGE 依赖 uk_exclusion_release_device 实现每发布单每设备至多一条，
 * 重复排除时刷新原因与画像版本；设备转为兼容并建任务后删除。
 */
@Repository
public class RolloutExclusionRepository {

    private final JdbcTemplate jdbc;

    public RolloutExclusionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入或刷新排除记录（同发布单同设备覆盖最新原因与画像版本）。
     */
    public void upsert(long releaseId, String deviceId, ExclusionReason reason, Integer profileVersion) {
        jdbc.update("MERGE INTO rollout_exclusion (release_id, device_id, reason_code, profile_version, created_at)"
                        + " KEY(release_id, device_id) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                releaseId, deviceId, reason.name(), profileVersion);
    }

    public int delete(long releaseId, String deviceId) {
        return jdbc.update("DELETE FROM rollout_exclusion WHERE release_id = ? AND device_id = ?",
                releaseId, deviceId);
    }

    public Optional<RolloutExclusion> find(long releaseId, String deviceId) {
        return jdbc.query("SELECT id, release_id, device_id, reason_code, profile_version"
                        + " FROM rollout_exclusion WHERE release_id = ? AND device_id = ?",
                (rs, rowNum) -> map(rs), releaseId, deviceId).stream().findFirst();
    }

    public List<RolloutExclusion> findByRelease(long releaseId) {
        return jdbc.query("SELECT id, release_id, device_id, reason_code, profile_version"
                + " FROM rollout_exclusion WHERE release_id = ? ORDER BY id", (rs, rowNum) -> map(rs), releaseId);
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_exclusion WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }

    private static RolloutExclusion map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RolloutExclusion(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                ExclusionReason.valueOf(rs.getString("reason_code")),
                (Integer) rs.getObject("profile_version"));
    }
}
