package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ForwardDeployment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备已完成正向投放历史查询：取 SUCCESS 任务并关联发布单的来源/目标版本，
 * 按任务ID（即派发先后）排序，供回退计划构造连续反向路径。
 */
@Repository
public class ForwardHistoryRepository {

    private final JdbcTemplate jdbc;

    public ForwardHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ForwardDeployment> findSuccessHistory(String deviceId) {
        return jdbc.query("SELECT t.release_id, t.id AS task_id, r.from_version, r.to_version"
                        + " FROM rollout_task t JOIN release_order r ON r.id = t.release_id"
                        + " WHERE t.device_id = ? AND t.status = 'SUCCESS'"
                        + " ORDER BY t.id",
                (rs, rowNum) -> new ForwardDeployment(rs.getLong("release_id"), rs.getLong("task_id"),
                        rs.getString("from_version"), rs.getString("to_version"), rs.getLong("task_id")),
                deviceId);
    }

    /**
     * 设备是否存在未终结（PENDING）的正向投放任务；存在则历史仍在变动，不允许创建回退计划。
     */
    public boolean existsPendingForwardTask(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND status = 'PENDING'",
                Long.class, deviceId);
        return count != null && count > 0;
    }

    /**
     * 设备是否参与过指定投放单（存在任意状态任务）；回退计划的设备集合必须完整来自来源投放单。
     */
    public boolean existsTaskForRelease(String deviceId, long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND release_id = ?",
                Long.class, deviceId, releaseId);
        return count != null && count > 0;
    }
}
