package com.example.starter.incident;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 命令幂等记录表访问，全部使用参数化 SQL。
 */
@Repository
public class CommandRecordRepository {

    private static final RowMapper<CommandRecord> MAPPER = (rs, rowNum) -> new CommandRecord(
            rs.getLong("id"),
            rs.getLong("incident_id"),
            rs.getString("command_key"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getInt("response_status"),
            rs.getString("response_body"),
            UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CommandRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CommandRecord> find(long incidentId, String commandKey) {
        return jdbc.query(
                        "SELECT id, incident_id, command_key, operation, fingerprint, response_status,"
                                + " response_body, created_at FROM command_record"
                                + " WHERE incident_id = ? AND command_key = ?",
                        MAPPER, incidentId, commandKey)
                .stream()
                .findFirst();
    }

    public void insert(CommandRecord record) {
        jdbc.update(
                "INSERT INTO command_record (incident_id, command_key, operation, fingerprint, response_status,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                record.incidentId(),
                record.commandKey(),
                record.operation(),
                record.fingerprint(),
                record.responseStatus(),
                record.responseBody(),
                UtcTimes.toDb(record.createdAt()));
    }
}
