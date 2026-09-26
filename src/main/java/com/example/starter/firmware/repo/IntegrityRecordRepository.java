package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.IntegrityFailureReason;
import com.example.starter.firmware.domain.IntegrityRecord;
import com.example.starter.firmware.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分片完整性判定记录数据访问。记录只增不改，按 id 升序即判定时间顺序。
 */
@Repository
public class IntegrityRecordRepository {

    private static final RowMapper<IntegrityRecord> MAPPER = (rs, rowNum) -> {
        String reason = rs.getString("reason");
        return new IntegrityRecord(rs.getLong("id"), rs.getLong("task_id"), rs.getInt("attempt"),
                rs.getLong("release_id"), rs.getString("firmware_version"),
                TaskStatus.valueOf(rs.getString("result")),
                reason == null ? null : IntegrityFailureReason.valueOf(reason),
                rs.getInt("received_count"), rs.getInt("required_count"),
                rs.getString("computed_package_digest"), rs.getString("expected_package_digest"),
                rs.getString("decided_at_utc"));
    };

    private static final String COLUMNS = "id, task_id, attempt, release_id, firmware_version, result,"
            + " reason, received_count, required_count, computed_package_digest, expected_package_digest,"
            + " decided_at_utc";

    private final JdbcTemplate jdbc;

    public IntegrityRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, int attempt, long releaseId, String firmwareVersion, TaskStatus result,
                       IntegrityFailureReason reason, int receivedCount, int requiredCount,
                       String computedPackageDigest, String expectedPackageDigest, String decidedAtUtc) {
        jdbc.update("INSERT INTO task_integrity_record"
                        + " (task_id, attempt, release_id, firmware_version, result, reason,"
                        + " received_count, required_count, computed_package_digest,"
                        + " expected_package_digest, decided_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, attempt, releaseId, firmwareVersion, result.name(),
                reason == null ? null : reason.name(), receivedCount, requiredCount,
                computedPackageDigest, expectedPackageDigest, decidedAtUtc);
    }

    /**
     * 任务全部代次的判定记录，按判定顺序（id）升序。
     */
    public List<IntegrityRecord> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_integrity_record WHERE task_id = ? ORDER BY id",
                MAPPER, taskId);
    }
}
