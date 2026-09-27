package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReviewStatus;
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
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条。
 * 任务创建时固化拉取时刻的证书链（chain_id/chain_version），后续证书轮换与撤销不影响快照。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        String reviewStatus = rs.getString("review_status");
        long chainId = rs.getLong("chain_id");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                rs.wasNull() ? null : chainId,
                rs.getString("chain_version"),
                reviewStatus == null ? null : ReviewStatus.valueOf(reviewStatus));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " chain_id, chain_version, review_status";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建任务并固化拉取时刻的证书链；chainId 为 null 表示目标版本无证书链登记（兼容无证书投放）。
     */
    public long insert(long releaseId, String deviceId, Long chainId, String chainVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, chain_id, chain_version)"
                            + " VALUES (?, ?, 'PENDING', ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            if (chainId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, chainId);
            }
            if (chainVersion == null) {
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(4, chainVersion);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<RolloutTask> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<RolloutTask> findByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    /**
     * 首次回执终结任务；reviewStatus 非空时同时落复核标记（证书已撤销的回执）。
     */
    public void complete(long id, ReceiptResult result, ReviewStatus reviewStatus) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, review_status = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                result.name(), result.name(),
                reviewStatus == null ? null : reviewStatus.name(), id);
    }

    /**
     * 人工确认复核：仅当处于 CERTIFICATE_REVOKED_REVIEW 时转为 REVIEW_CONFIRMED，返回影响行数。
     */
    public int confirmReview(long id) {
        return jdbc.update("UPDATE rollout_task SET review_status = 'REVIEW_CONFIRMED',"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND review_status = 'CERTIFICATE_REVOKED_REVIEW'", id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status = 'PENDING'", releaseId);
    }

    public List<RolloutTask> findByRelease(long releaseId, TaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND status = ? ORDER BY id",
                MAPPER, releaseId, statusFilter.name());
    }

    /**
     * 发布单下带复核标记（含已确认）的任务，按任务ID升序。
     */
    public List<RolloutTask> findReviewsByRelease(long releaseId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task"
                + " WHERE release_id = ? AND review_status IS NOT NULL ORDER BY id", MAPPER, releaseId);
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }
}
