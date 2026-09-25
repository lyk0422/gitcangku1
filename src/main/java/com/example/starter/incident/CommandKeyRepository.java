package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 命令幂等键仓储。(domain, command_key) 域内唯一；
 * 先占位插入、同事务内补写响应，并发同键插入由唯一约束串行化。
 * REAL/DRILL 两域同名键互不冲突，重放绝不跨域。
 */
@Repository
public class CommandKeyRepository {

    private final JdbcTemplate jdbc;

    public CommandKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CommandKeyRecord> MAPPER = (rs, n) -> map(rs);

    private static CommandKeyRecord map(ResultSet rs) throws SQLException {
        int status = rs.getInt("response_status");
        return new CommandKeyRecord(rs.getLong("id"), Domain.valueOf(rs.getString("domain")),
                rs.getString("command_key"), rs.getString("operation"), rs.getString("request_hash"),
                rs.wasNull() ? null : status, rs.getString("response_body"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 按域与命令键查询（普通读）。
     */
    public Optional<CommandKeyRecord> find(Domain domain, String commandKey) {
        List<CommandKeyRecord> rows = jdbc.query(
                "SELECT * FROM command_keys WHERE domain = ? AND command_key = ?",
                MAPPER, domain.name(), commandKey);
        return rows.stream().findFirst();
    }

    /**
     * 按域与命令键锁定读（SELECT ... FOR UPDATE），读取最新已提交数据，用于唯一冲突后的重查。
     */
    public Optional<CommandKeyRecord> findForUpdate(Domain domain, String commandKey) {
        List<CommandKeyRecord> rows = jdbc.query(
                "SELECT * FROM command_keys WHERE domain = ? AND command_key = ? FOR UPDATE",
                MAPPER, domain.name(), commandKey);
        return rows.stream().findFirst();
    }

    /**
     * 占位插入幂等键（响应列为空，同事务内随后补写）。
     *
     * @throws DuplicateKeyException 同域同键已存在（含并发事务已提交）时抛出
     */
    public void insertPlaceholder(Domain domain, String commandKey, String operation,
                                  String requestHash, Instant now) {
        jdbc.update("INSERT INTO command_keys (domain, command_key, operation, request_hash,"
                + " response_status, response_body, created_at) VALUES (?,?,?,?,NULL,NULL,?)",
                domain.name(), commandKey, operation, requestHash, Timestamp.from(now));
    }

    /**
     * 补写首次成功的响应，与业务写入同事务提交。
     */
    public void fillResponse(Domain domain, String commandKey, int responseStatus, String responseBody) {
        jdbc.update("UPDATE command_keys SET response_status = ?, response_body = ?"
                        + " WHERE domain = ? AND command_key = ?",
                responseStatus, responseBody, domain.name(), commandKey);
    }
}
