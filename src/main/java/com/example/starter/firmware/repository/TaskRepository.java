package com.example.starter.firmware.repository;

import com.example.starter.firmware.dto.TaskResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务数据访问。
 */
@Repository
public class TaskRepository {

    private static final String COLUMNS = "id, rollout_id, device_id, from_version, to_version, status";

    private static final RowMapper<TaskResponse> MAPPER = (rs, rowNum) -> new TaskResponse(
            rs.getLong("id"),
            rs.getLong("rollout_id"),
            rs.getString("device_id"),
            rs.getString("from_version"),
            rs.getString("to_version"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建 PENDING 任务；(rollout_id, device_id) 唯一冲突时抛出 DuplicateKeyException。
     *
     * @return 任务自增主键
     */
    public long insert(long rolloutId, String deviceId, String fromVersion, String toVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (rollout_id, device_id, from_version, to_version, status)"
                            + " VALUES (?, ?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, rolloutId);
            ps.setString(2, deviceId);
            ps.setString(3, fromVersion);
            ps.setString(4, toVersion);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（无锁）。
     */
    public Optional<TaskResponse> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁，用于回执与取消的串行化。
     */
    public Optional<TaskResponse> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询设备在指定发布单下的已有任务。
     */
    public Optional<TaskResponse> findByRolloutAndDevice(long rolloutId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE rollout_id = ? AND device_id = ?",
                        MAPPER, rolloutId, deviceId)
                .stream().findFirst();
    }

    /**
     * 按发布单查询全部任务明细。
     */
    public List<TaskResponse> findByRolloutId(long rolloutId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE rollout_id = ? ORDER BY id",
                MAPPER, rolloutId);
    }

    /**
     * 终结任务（PENDING -> SUCCESS/FAILED）。
     */
    public void finish(long id, String status) {
        jdbc.update("UPDATE rollout_task SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, id);
    }

    /**
     * 将发布单下所有未终结任务置为 CANCELLED，返回受影响行数。
     */
    public int cancelPendingByRollout(long rolloutId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE rollout_id = ? AND status = 'PENDING'", rolloutId);
    }
}
