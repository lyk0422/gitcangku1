package com.example.starter.curtailment.idempotency;

import com.example.starter.curtailment.common.UtcTimes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 幂等命令记录存取：commandKey 主键保证同键并发只有一份记录。
 */
@Repository
public class IdempotentCommandRepository {

    private final JdbcTemplate jdbc;

    public IdempotentCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredCommand> findByKey(String commandKey) {
        return jdbc.query(
                        "SELECT operation, fingerprint, response_status, response_body FROM idempotent_command WHERE command_key = ?",
                        (rs, rowNum) -> new StoredCommand(
                                rs.getString("operation"),
                                rs.getString("fingerprint"),
                                (Integer) rs.getObject("response_status"),
                                rs.getString("response_body")),
                        commandKey)
                .stream()
                .findFirst();
    }

    /** 锁读：用于并发同键插入冲突后读取已提交的首次结果。 */
    public Optional<StoredCommand> findByKeyForUpdate(String commandKey) {
        return jdbc.query(
                        "SELECT operation, fingerprint, response_status, response_body FROM idempotent_command WHERE command_key = ? FOR UPDATE",
                        (rs, rowNum) -> new StoredCommand(
                                rs.getString("operation"),
                                rs.getString("fingerprint"),
                                (Integer) rs.getObject("response_status"),
                                rs.getString("response_body")),
                        commandKey)
                .stream()
                .findFirst();
    }

    public void insert(String commandKey, String operation, String fingerprint, Instant createdAt) {
        jdbc.update(
                "INSERT INTO idempotent_command (command_key, operation, fingerprint, created_at_utc) VALUES (?, ?, ?, ?)",
                commandKey, operation, fingerprint, UtcTimes.toUtcDateTime(createdAt));
    }

    public void complete(String commandKey, int responseStatus, String responseBody) {
        jdbc.update(
                "UPDATE idempotent_command SET response_status = ?, response_body = ? WHERE command_key = ?",
                responseStatus, responseBody, commandKey);
    }
}
