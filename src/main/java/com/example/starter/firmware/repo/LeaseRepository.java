package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.TaskLease;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 任务执行租约数据访问。领取、接管、续租与回执均在持有任务行锁的事务内进行，
 * 租约行的状态变更与任务终结、设备版本更新、统计累计同事务提交。
 */
@Repository
public class LeaseRepository {

    private static final RowMapper<TaskLease> MAPPER = (rs, rowNum) -> {
        Integer terminalToken = rs.getObject("terminal_token", Integer.class);
        return new TaskLease(rs.getLong("task_id"), rs.getInt("current_token"),
                rs.getString("current_agent_id"), rs.getString("expires_at_utc"),
                rs.getInt("terminal") == 1, rs.getString("terminal_agent_id"), terminalToken);
    };

    private static final String COLUMNS = "task_id, current_token, current_agent_id, expires_at_utc,"
            + " terminal, terminal_agent_id, terminal_token";

    private final JdbcTemplate jdbc;

    public LeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TaskLease> findByTaskId(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_lease WHERE task_id = ?", MAPPER, taskId)
                .stream().findFirst();
    }

    public Optional<TaskLease> findByTaskIdForUpdate(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_lease WHERE task_id = ? FOR UPDATE", MAPPER, taskId)
                .stream().findFirst();
    }

    /**
     * 首次领取：写入 token=1 的租约；调用方已持有任务行锁。
     */
    public void insert(long taskId, String agentId, String expiresAtUtc) {
        jdbc.update("INSERT INTO task_lease (task_id, current_token, current_agent_id, expires_at_utc)"
                + " VALUES (?, 1, ?, ?)", taskId, agentId, expiresAtUtc);
    }

    /**
     * 到期接管：令牌加一并更换持有人与到期时刻；仅在未终结时允许，返回影响行数。
     */
    public int takeOverExpired(long taskId, String agentId, String expiresAtUtc) {
        return jdbc.update("UPDATE task_lease SET current_token = current_token + 1, current_agent_id = ?,"
                + " expires_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE task_id = ? AND terminal = 0", agentId, expiresAtUtc, taskId);
    }

    /**
     * 续租：本人凭当前令牌延长到期时刻；仅在未终结时允许，返回影响行数。
     */
    public int renew(long taskId, String agentId, int token, String expiresAtUtc) {
        return jdbc.update("UPDATE task_lease SET expires_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE task_id = ? AND current_agent_id = ? AND current_token = ? AND terminal = 0",
                expiresAtUtc, taskId, agentId, token);
    }

    /**
     * 终结冻结：记录首次终结回执的实际代理与令牌，terminal 置 1。
     */
    public void markTerminal(long taskId, String agentId, int token) {
        jdbc.update("UPDATE task_lease SET terminal = 1, terminal_agent_id = ?, terminal_token = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", agentId, token, taskId);
    }
}
