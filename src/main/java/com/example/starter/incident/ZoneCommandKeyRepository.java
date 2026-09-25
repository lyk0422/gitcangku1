package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 疏散域命令幂等键仓储，使用独立的 zone_command_keys 表与既有 command_keys 物理隔离。
 * 语义与 {@link CommandKeyRepository} 一致：先占位插入、同事务内补写响应，
 * 并发同键插入由唯一约束串行化，业务失败随事务回滚，不占用幂等键。
 */
@Repository
public class ZoneCommandKeyRepository implements CommandKeyStore {

    private final JdbcTemplate jdbc;

    public ZoneCommandKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommandKeyRecord> MAPPER = (rs, n) -> map(rs);

    private static CommandKeyRecord map(ResultSet rs) throws SQLException {
        int status = rs.getInt("response_status");
        return new CommandKeyRecord(rs.getLong("id"), rs.getString("command_key"),
                rs.getString("operation"), rs.getString("request_hash"),
                rs.wasNull() ? null : status, rs.getString("response_body"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 按命令键查询（普通读）。
     */
    public Optional<CommandKeyRecord> find(String commandKey) {
        List<CommandKeyRecord> rows = jdbc.query(
                "SELECT * FROM zone_command_keys WHERE command_key = ?", MAPPER, commandKey);
        return rows.stream().findFirst();
    }

    /**
     * 按命令键锁定读（SELECT ... FOR UPDATE），用于唯一冲突后的重查。
     */
    public Optional<CommandKeyRecord> findForUpdate(String commandKey) {
        List<CommandKeyRecord> rows = jdbc.query(
                "SELECT * FROM zone_command_keys WHERE command_key = ? FOR UPDATE", MAPPER, commandKey);
        return rows.stream().findFirst();
    }

    /**
     * 占位插入幂等键（响应列为空，同事务内随后补写）。
     */
    public void insertPlaceholder(String commandKey, String operation, String requestHash, Instant now) {
        jdbc.update("INSERT INTO zone_command_keys (command_key, operation, request_hash,"
                + " response_status, response_body, created_at) VALUES (?,?,?,NULL,NULL,?)",
                commandKey, operation, requestHash, Timestamp.from(now));
    }

    /**
     * 补写首次成功的响应，与业务写入同事务提交。
     */
    public void fillResponse(String commandKey, int responseStatus, String responseBody) {
        jdbc.update("UPDATE zone_command_keys SET response_status = ?, response_body = ?"
                + " WHERE command_key = ?", responseStatus, responseBody, commandKey);
    }
}
