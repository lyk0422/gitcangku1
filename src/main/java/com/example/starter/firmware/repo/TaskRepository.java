package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RolloutTask;
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
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条。
 * 增量任务在创建时冻结基底/补丁/目标摘要与设备镜像代次，之后不再随发布单或设备变化。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        int generation = rs.getInt("image_generation");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getString("base_digest"), rs.getString("patch_digest"), rs.getString("target_digest"),
                rs.wasNull() ? null : generation);
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " base_digest, patch_digest, target_digest, image_generation";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建任务并冻结增量摘要与设备镜像代次；普通发布任务四个冻结字段均为 null。
     */
    public long insert(long releaseId, String deviceId, String baseDigest, String patchDigest,
                       String targetDigest, Integer imageGeneration) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, base_digest, patch_digest,"
                            + " target_digest, image_generation) VALUES (?, ?, 'PENDING', ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setString(3, baseDigest);
            ps.setString(4, patchDigest);
            ps.setString(5, targetDigest);
            if (imageGeneration == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, imageGeneration);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RolloutTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status = 'PENDING'", releaseId);
    }

    public List<RolloutTask> findByRelease(long releaseId, TaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND status = ? ORDER BY id",
                MAPPER, releaseId, statusFilter.name());
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }
}