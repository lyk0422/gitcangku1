package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ProfileExclusion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 投放排除记录数据访问。不兼容设备只记录原因，不创建任务、不计入失败率。
 */
@Repository
public class ProfileExclusionRepository {

    private static final RowMapper<ProfileExclusion> MAPPER = (rs, rowNum) -> new ProfileExclusion(
            rs.getLong("id"), rs.getLong("launch_id"), rs.getString("device_id"),
            (Integer) rs.getObject("profile_version"), rs.getString("reason"));

    private static final String COLUMNS = "id, launch_id, device_id, profile_version, reason";

    private final JdbcTemplate jdbc;

    public ProfileExclusionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long launchId, String deviceId, Integer profileVersion, String reason) {
        jdbc.update("INSERT INTO profile_exclusion (launch_id, device_id, profile_version, reason)"
                + " VALUES (?, ?, ?, ?)", launchId, deviceId, profileVersion, reason);
    }

    public List<ProfileExclusion> findByLaunch(long launchId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM profile_exclusion WHERE launch_id = ?"
                + " ORDER BY id", MAPPER, launchId);
    }

    public long countByLaunch(long launchId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile_exclusion WHERE launch_id = ?", Long.class, launchId);
        return count == null ? 0 : count;
    }
}
