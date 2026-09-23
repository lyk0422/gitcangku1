package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.AssignmentCommand;
import com.example.starter.firmware.domain.CohortAssignment;
import com.example.starter.firmware.domain.CommandStatus;
import com.example.starter.firmware.domain.ReceiptHistory;
import com.example.starter.firmware.domain.ReceiptResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 设备队列分配、按代次下发的指令与回执历史数据访问。
 *
 * <p>并发顺序：迁移、回执、暂停/恢复均先锁活动行（release_order ... FOR UPDATE），再锁本活动内的
 * 分配/指令行，因此同一活动内所有变更按提交顺序串行，设备归属、统计与活动状态来自同一结果。
 * 同设备同活动同代次由唯一约束 uk_command_release_device_generation 保证至多一条指令。
 */
@Repository
public class AssignmentRepository {

    private static final RowMapper<CohortAssignment> ASSIGNMENT_MAPPER = (rs, rowNum) -> new CohortAssignment(
            rs.getLong("release_id"), rs.getString("device_id"), rs.getLong("cohort_id"),
            rs.getInt("assignment_version"), rs.getInt("current_generation"),
            rs.getBoolean("install_confirmed"));

    private static final RowMapper<AssignmentCommand> COMMAND_MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        Long migrationId = (Long) rs.getObject("migration_id");
        return new AssignmentCommand(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                rs.getLong("cohort_id"), rs.getInt("generation"),
                CommandStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult), migrationId);
    };

    private static final RowMapper<ReceiptHistory> HISTORY_MAPPER = (rs, rowNum) -> new ReceiptHistory(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"), rs.getLong("command_id"),
            rs.getLong("cohort_id"), rs.getInt("generation"), rs.getString("result"),
            rs.getBoolean("settled"), rs.getBoolean("duplicate"), rs.getString("receipt_at"));

    private static final String ASSIGNMENT_COLUMNS = "release_id, device_id, cohort_id, assignment_version,"
            + " current_generation, install_confirmed";

    private static final String COMMAND_COLUMNS = "id, release_id, device_id, cohort_id, generation, status,"
            + " first_result, migration_id";

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAssignment(long releaseId, String deviceId, long cohortId) {
        jdbc.update("INSERT INTO cohort_assignment (release_id, device_id, cohort_id, assignment_version,"
                + " current_generation, install_confirmed) VALUES (?, ?, ?, 1, 1, FALSE)",
                releaseId, deviceId, cohortId);
    }

    public Optional<CohortAssignment> findAssignment(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + ASSIGNMENT_COLUMNS + " FROM cohort_assignment"
                + " WHERE release_id = ? AND device_id = ?", ASSIGNMENT_MAPPER, releaseId, deviceId)
                .stream().findFirst();
    }

    public Optional<CohortAssignment> findAssignmentForUpdate(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + ASSIGNMENT_COLUMNS + " FROM cohort_assignment"
                + " WHERE release_id = ? AND device_id = ? FOR UPDATE", ASSIGNMENT_MAPPER, releaseId, deviceId)
                .stream().findFirst();
    }

    /**
     * 迁移提交：原子切换队列、分配版本加一、代次加一。仅在当前版本与预期一致时生效（乐观兜底）。
     */
    public int applyMigration(long releaseId, String deviceId, long toCohortId, int expectedVersion) {
        return jdbc.update("UPDATE cohort_assignment SET cohort_id = ?, assignment_version = assignment_version + 1,"
                + " current_generation = current_generation + 1, updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND device_id = ? AND assignment_version = ?",
                toCohortId, releaseId, deviceId, expectedVersion);
    }

    public void markInstallConfirmed(long releaseId, String deviceId) {
        jdbc.update("UPDATE cohort_assignment SET install_confirmed = TRUE, updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND device_id = ?", releaseId, deviceId);
    }

    public long insertCommand(long releaseId, String deviceId, long cohortId, int generation, Long migrationId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO assignment_command (release_id, device_id, cohort_id, generation, status,"
                            + " migration_id) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setLong(3, cohortId);
            ps.setInt(4, generation);
            if (migrationId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, migrationId);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<AssignmentCommand> findCommandById(long id) {
        return jdbc.query("SELECT " + COMMAND_COLUMNS + " FROM assignment_command WHERE id = ?",
                COMMAND_MAPPER, id).stream().findFirst();
    }

    public Optional<AssignmentCommand> findCommandByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COMMAND_COLUMNS + " FROM assignment_command WHERE id = ? FOR UPDATE",
                COMMAND_MAPPER, id).stream().findFirst();
    }

    /**
     * 设备当前唯一的未决（PENDING）指令；迁移将其原子置为 SUPERSEDED。
     */
    public Optional<AssignmentCommand> findPendingCommandForUpdate(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COMMAND_COLUMNS + " FROM assignment_command"
                + " WHERE release_id = ? AND device_id = ? AND status = 'PENDING' ORDER BY generation DESC"
                + " FOR UPDATE", COMMAND_MAPPER, releaseId, deviceId).stream().findFirst();
    }

    /**
     * 将设备全部未决指令置为 SUPERSEDED，返回影响行数。
     */
    public int supersedePending(long releaseId, String deviceId) {
        return jdbc.update("UPDATE assignment_command SET status = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND device_id = ? AND status = 'PENDING'", releaseId, deviceId);
    }

    public void completeCommand(long commandId, ReceiptResult result) {
        jdbc.update("UPDATE assignment_command SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), commandId);
    }

    public void insertHistory(long releaseId, String deviceId, long commandId, long cohortId, int generation,
                              String result, boolean settled, boolean duplicate, String receiptAt) {
        jdbc.update("INSERT INTO receipt_history (release_id, device_id, command_id, cohort_id, generation,"
                + " result, settled, duplicate, receipt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                releaseId, deviceId, commandId, cohortId, generation, result, settled, duplicate, receiptAt);
    }

    public List<ReceiptHistory> findHistoryByMigrationDevice(long migrationId, String deviceId) {
        return jdbc.query("SELECT rh.id, rh.release_id, rh.device_id, rh.command_id, rh.cohort_id,"
                + " rh.generation, rh.result, rh.settled, rh.duplicate, rh.receipt_at"
                + " FROM receipt_history rh"
                + " JOIN cohort_migration_item mi ON mi.migration_id = ?"
                + " WHERE rh.device_id = mi.device_id AND rh.device_id = ? ORDER BY rh.id",
                HISTORY_MAPPER, migrationId, deviceId);
    }

    public List<ReceiptHistory> findHistoryByRelease(long releaseId) {
        return jdbc.query("SELECT id, release_id, device_id, command_id, cohort_id, generation, result,"
                + " settled, duplicate, receipt_at FROM receipt_history WHERE release_id = ? ORDER BY id",
                HISTORY_MAPPER, releaseId);
    }

    /**
     * 迁移单设备在提交后到达的旧代次迟到（LATE）回执，作为只读查询证据。
     */
    public List<ReceiptHistory> findLateHistoryByMigration(long migrationId) {
        return jdbc.query("SELECT rh.id, rh.release_id, rh.device_id, rh.command_id, rh.cohort_id,"
                + " rh.generation, rh.result, rh.settled, rh.duplicate, rh.receipt_at"
                + " FROM receipt_history rh"
                + " JOIN cohort_migration_item mi ON mi.device_id = rh.device_id"
                + " AND mi.from_cohort_id = rh.cohort_id"
                + " WHERE mi.migration_id = ? AND rh.result = 'LATE' AND rh.settled = FALSE ORDER BY rh.id",
                HISTORY_MAPPER, migrationId);
    }
}
