package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 任务画像冻结快照数据访问。快照随任务创建写入、永不修改，供失败率审计核对筛选时所用画像。
 */
@Repository
public class TaskProfileSnapshotRepository {

    /**
     * 任务画像冻结快照。
     *
     * @param taskId         任务ID
     * @param profileVersion 冻结画像版本号
     * @param model          冻结画像型号，可能为 null
     * @param memoryMb       冻结画像内存（MB），可能为 null
     * @param capabilityTags 冻结画像能力标签JSON数组（如 ["BLE"]）
     */
    public record SnapshotRow(long taskId, int profileVersion, String model, Integer memoryMb,
                              String capabilityTags) {
    }

    private final JdbcTemplate jdbc;

    public TaskProfileSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SnapshotRow> findByTaskId(long taskId) {
        return jdbc.query("SELECT task_id, profile_version, model, memory_mb, capability_tags"
                        + " FROM rollout_task_profile_snapshot WHERE task_id = ?",
                (rs, rowNum) -> new SnapshotRow(rs.getLong("task_id"), rs.getInt("profile_version"),
                        rs.getString("model"), (Integer) rs.getObject("memory_mb"),
                        rs.getString("capability_tags")),
                taskId).stream().findFirst();
    }
}
