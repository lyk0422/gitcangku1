package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务尝试数据访问。同设备同发布单每代尝试一条，
 * 由唯一约束 uk_task_release_device_attempt 保证；历史代次只增不改。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                rs.getInt("attempt_no"), TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.getString("aggregate_digest"));
    };

    private static final String COLUMNS = "id, release_id, device_id, attempt_no, status, first_result,"
            + " aggregate_digest";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId, int attemptNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, attempt_no, status)"
                            + " VALUES (?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setInt(3, attemptNo);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RolloutTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 设备在发布单上的最新一代尝试（attempt_no 最大）；重新拉取建立新代次后旧代次仍可查。
     */
    public Optional<RolloutTask> findLatestByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task"
                        + " WHERE release_id = ? AND device_id = ? ORDER BY attempt_no DESC LIMIT 1",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    /**
     * 可安装判定：任务转为 INSTALLABLE 并固化判定时刻的聚合摘要。
     */
    public void markInstallable(long id, String aggregateDigest) {
        jdbc.update("UPDATE rollout_task SET status = 'INSTALLABLE', aggregate_digest = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", aggregateDigest, id);
    }

    /**
     * 完整性失败判定：任务转为 INTEGRITY_FAILED，禁止安装与成功回执，不计设备执行失败率。
     */
    public void markIntegrityFailed(long id) {
        jdbc.update("UPDATE rollout_task SET status = 'INTEGRITY_FAILED',"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), id);
    }

    /**
     * 发布单取消：未终结任务（分片接收中 PENDING、可安装 INSTALLABLE）转 CANCELLED；
     * 已终结（SUCCESS/FAILED/INTEGRITY_FAILED）与已取消任务保持不变。
     */
    public int cancelOpenByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status IN ('PENDING', 'INSTALLABLE')", releaseId);
    }

    public List<RolloutTask> findByRelease(long releaseId, TaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND status = ? ORDER BY id",
                MAPPER, releaseId, statusFilter.name());
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }

    public long countByRelease(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }
}
