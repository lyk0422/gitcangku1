package com.example.starter.evidence.repository;

import com.example.starter.evidence.domain.CommandLog;
import com.example.starter.evidence.domain.CommandType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 幂等命令日志访问。先插入占位行（唯一键拦截并发同键），业务成功后回填首次结果。
 */
@Repository
public class CommandLogRepository {

    private static final RowMapper<CommandLog> MAPPER = (rs, rowNum) -> new CommandLog(
            rs.getLong("id"),
            rs.getString("command_key"),
            CommandType.valueOf(rs.getString("command_type")),
            rs.getString("actor_id"),
            rs.getString("fingerprint"),
            rs.getObject("http_status") == null ? null : rs.getInt("http_status"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public CommandLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入命令占位行；同键并发时由唯一约束串行化并抛出 DuplicateKeyException。
     */
    public void insertPending(String commandKey, CommandType type, String actorId,
                              String fingerprint, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO command_log (command_key, command_type, actor_id, fingerprint, http_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, NULL, NULL, ?)",
                commandKey, type.name(), actorId, fingerprint, Timestamp.valueOf(now));
    }

    public Optional<CommandLog> findByKey(String commandKey) {
        List<CommandLog> rows = jdbc.query(
                "SELECT * FROM command_log WHERE command_key = ?", MAPPER, commandKey);
        return rows.stream().findFirst();
    }

    /**
     * 回填首次执行结果（HTTP 状态与响应体 JSON）。
     */
    public void complete(String commandKey, int httpStatus, String responseBody) {
        jdbc.update("UPDATE command_log SET http_status = ?, response_body = ? WHERE command_key = ?",
                httpStatus, responseBody, commandKey);
    }
}
