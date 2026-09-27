package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.CertReviewStatus;
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
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条。
 * 证书纳管固件的任务在创建时固化证书链版本与指纹，后续轮换/撤销不改变该快照。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        Integer certChainVersion = rs.getObject("cert_chain_version", Integer.class);
        String reviewStatus = rs.getString("review_status");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                certChainVersion, rs.getString("cert_fingerprint"),
                reviewStatus == null ? null : CertReviewStatus.valueOf(reviewStatus),
                rs.getString("review_reason"), rs.getString("reviewed_by"),
                rs.getString("reviewed_at_utc"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " cert_chain_version, cert_fingerprint, review_status, review_reason, reviewed_by, reviewed_at_utc";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建未纳管证书固件的任务，证书快照字段为 NULL。
     */
    public long insert(long releaseId, String deviceId) {
        return insert(releaseId, deviceId, null, null);
    }

    /**
     * 创建证书纳管固件的任务，固化拉取时刻使用的证书链版本与指纹。
     */
    public long insert(long releaseId, String deviceId, Integer certChainVersion, String certFingerprint) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, cert_chain_version,"
                            + " cert_fingerprint) VALUES (?, ?, 'PENDING', ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            if (certChainVersion == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, certChainVersion);
            }
            ps.setString(4, certFingerprint);
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

    public Optional<RolloutTask> findByReleaseAndDevice(long releaseId, String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND device_id = ?",
                MAPPER, releaseId, deviceId).stream().findFirst();
    }

    public void complete(long id, ReceiptResult result) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", result.name(), result.name(), id);
    }

    /**
     * 撤销链在途任务回执：终结任务但标记人工复核，不自动更新设备版本。
     */
    public void completeForReview(long id, ReceiptResult result, String reason) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?,"
                + " review_status = 'CERTIFICATE_REVOKED_REVIEW', review_reason = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                result.name(), result.name(), reason, id);
    }

    /**
     * 人工复核：更新复核状态、原因、操作者与UTC时刻。
     */
    public void updateReview(long id, CertReviewStatus reviewStatus, String reason,
                             String reviewedBy, String reviewedAtUtc) {
        jdbc.update("UPDATE rollout_task SET review_status = ?, review_reason = ?, reviewed_by = ?,"
                + " reviewed_at_utc = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                reviewStatus.name(), reason, reviewedBy, reviewedAtUtc, id);
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

    public long countByReleaseAndDevice(long releaseId, String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }

    public long countByReleaseId(long releaseId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
        return count == null ? 0 : count;
    }

    public long countReviewByRelease(long releaseId, CertReviewStatus reviewStatus) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND review_status = ?",
                Long.class, releaseId, reviewStatus.name());
        return count == null ? 0 : count;
    }
}
