package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ProfileTask;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 画像投放任务数据访问。同设备同投放单由唯一约束 uk_ptask_launch_device 保证最多一条。
 */
@Repository
public class ProfileTaskRepository {

    private static final RowMapper<ProfileTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new ProfileTask(rs.getLong("id"), rs.getLong("launch_id"), rs.getString("device_id"),
                rs.getInt("profile_version"), TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult));
    };

    private static final String COLUMNS = "id, launch_id, device_id, profile_version, status, first_result";

    private final JdbcTemplate jdbc;

    public ProfileTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long launchId, String deviceId, int profileVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO profile_task (launch_id, device_id, profile_version, status)"
                            + " VALUES (?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, launchId);
            ps.setString(2, deviceId);
            ps.setInt(3, profileVersion);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ProfileTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM profile_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ProfileTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM profile_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public List<ProfileTask> findByLaunch(long launchId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM profile_task WHERE launch_id = ? ORDER BY id",
                MAPPER, launchId);
    }

    public List<ProfileTask> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM profile_task WHERE device_id = ? ORDER BY id",
                MAPPER, deviceId);
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE profile_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), id);
    }

    public long countByLaunchAndStatus(long launchId, TaskStatus status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM profile_task WHERE launch_id = ? AND status = ?",
                Long.class, launchId, status.name());
        return count == null ? 0 : count;
    }
}
