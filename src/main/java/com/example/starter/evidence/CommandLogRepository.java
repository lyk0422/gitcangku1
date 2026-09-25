package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等命令日志表访问。command_key 全局唯一，记录只追加。
 */
@Repository
public class CommandLogRepository {

    private static final CommandLogRowMapper ROW_MAPPER = new CommandLogRowMapper();

    private final JdbcTemplate jdbc;

    public CommandLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录命令首次执行结果；command_key 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String commandKey, String actorId, String operation, String requestHash,
                       int responseStatus, String responseBody, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO command_log
                            (command_key, actor_id, operation, request_hash, response_status, response_body, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                commandKey, actorId, operation, requestHash, responseStatus, responseBody, now);
    }

    /**
     * 事务开始时预占幂等键（响应状态 0、空响应体为占位值）。
     * 并发同键事务在唯一约束上等待先提交者；事务回滚时占位行一并回滚，失败不占键。
     */
    public void reserve(String commandKey, String actorId, String operation, String requestHash,
                        LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO command_log
                            (command_key, actor_id, operation, request_hash, response_status, response_body, created_at)
                        VALUES (?, ?, ?, ?, 0, '', ?)
                        """,
                commandKey, actorId, operation, requestHash, now);
    }

    /**
     * 事务提交前把预占行补全为首次执行的真实响应快照。
     */
    public void complete(String commandKey, int responseStatus, String responseBody,
                         LocalDateTime now) {
        jdbc.update("""
                        UPDATE command_log
                        SET response_status = ?, response_body = ?, created_at = ?
                        WHERE command_key = ?
                        """,
                responseStatus, responseBody, now, commandKey);
    }

    /**
     * 按命令键查询首次执行记录。
     */
    public Optional<CommandLog> findByKey(String commandKey) {
        List<CommandLog> rows = jdbc.query(
                "SELECT * FROM command_log WHERE command_key = ?", ROW_MAPPER, commandKey);
        return rows.stream().findFirst();
    }

    /**
     * 幂等命令日志记录。
     *
     * @param commandKey     幂等命令键
     * @param actorId        发起操作人
     * @param operation      操作类型
     * @param requestHash    请求参数规范化后的 SHA-256
     * @param responseStatus 首次执行的 HTTP 状态码
     * @param responseBody   首次执行的响应体 JSON
     * @param createdAt      首次执行时间（Asia/Shanghai）
     */
    public record CommandLog(
            String commandKey,
            String actorId,
            String operation,
            String requestHash,
            int responseStatus,
            String responseBody,
            LocalDateTime createdAt) {
    }

    private static final class CommandLogRowMapper implements RowMapper<CommandLog> {
        @Override
        public CommandLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CommandLog(
                    rs.getString("command_key"),
                    rs.getString("actor_id"),
                    rs.getString("operation"),
                    rs.getString("request_hash"),
                    rs.getInt("response_status"),
                    rs.getString("response_body"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
