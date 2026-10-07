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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条。
 * 租约字段（lease_*）与终结冻结字段（result_*）只在持有任务行锁（SELECT ... FOR UPDATE）的事务内更新。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        String leaseExpiresAt = rs.getString("lease_expires_at");
        Integer leaseToken = rs.getObject("lease_token", Integer.class);
        Integer resultToken = rs.getObject("result_token", Integer.class);
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getString("lease_agent_id"), leaseToken,
                leaseExpiresAt == null ? null : Instant.parse(leaseExpiresAt),
                rs.getString("result_agent_id"), resultToken);
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " lease_agent_id, lease_token, lease_expires_at, result_agent_id, result_token";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status) VALUES (?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
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

    /**
     * 领取新租约：令牌在历史最新值上加一（从未领取则为 1），并写入持有人与到期时刻。
     * 调用方必须已持有任务行锁。
     */
    public void grantLease(long id, String agentId, int token, Instant expiresAt) {
        jdbc.update("UPDATE rollout_task SET lease_agent_id = ?, lease_token = ?, lease_expires_at = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                agentId, token, expiresAt.toString(), id);
    }

    /**
     * 续租：仅刷新到期时刻，持有人与令牌不变。调用方必须已持有任务行锁。
     */
    public void renewLease(long id, Instant expiresAt) {
        jdbc.update("UPDATE rollout_task SET lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                expiresAt.toString(), id);
    }

    /**
     * 终结任务：写入首次结果，并冻结实际写回结果的代理与令牌。调用方必须已持有任务行锁。
     * 从未领取任务的旧入口回执，agentId/token 为 null，冻结列写入 NULL。
     */
    public void complete(long id, ReceiptResult result, String agentId, Integer token) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?,"
                + " result_agent_id = ?, result_token = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                result.name(), result.name(), agentId, token, id);
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
