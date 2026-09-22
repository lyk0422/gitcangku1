package com.example.starter.firmware.service;

import com.example.starter.firmware.dto.PullRequest;
import com.example.starter.firmware.dto.ReceiptRequest;
import com.example.starter.firmware.dto.TaskResponse;
import com.example.starter.firmware.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 投放任务：设备拉取、回执收敛与明细查询。
 * 拉取与取消通过对发布单行加锁串行化；回执与取消通过对任务行加锁/行级更新串行化，
 * 两种并发都形成一致的提交顺序，已成功设备不回滚。
 */
@Service
public class TaskService {

    private static final RowMapper<TaskResponse> MAPPER = (rs, i) -> new TaskResponse(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
            rs.getString("model"), rs.getString("from_version"), rs.getString("to_version"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;

    public TaskService(JdbcTemplate jdbc, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    /**
     * 设备拉取任务：先返回该设备在该型号 ACTIVE 发布单下的已有任务（含 FAILED，本轮不重新投放）；
     * 否则仅当型号与当前版本匹配、分桶号小于投放比例时创建任务。无匹配发布单或不满足条件时返回 404。
     */
    @Transactional
    public TaskResponse pull(PullRequest req) {
        return idempotency.execute(req.requestId(), "TASK_PULL", req.deviceId(), TaskResponse.class, () -> {
            Map<String, Object> device = jdbc.query(
                    "SELECT device_id, model, current_version, bucket FROM device WHERE device_id=?",
                    rs -> rs.next() ? Map.of(
                            "device_id", rs.getString("device_id"),
                            "model", rs.getString("model"),
                            "current_version", rs.getString("current_version"),
                            "bucket", rs.getInt("bucket")) : null,
                    req.deviceId());
            if (device == null) {
                throw ApiException.notFound("DEVICE_NOT_FOUND", "device not found: " + req.deviceId());
            }
            // 锁定该型号 ACTIVE 发布单，与取消操作串行化。
            List<Map<String, Object>> releases = jdbc.queryForList(
                    "SELECT id, from_version, to_version, ratio FROM release_order"
                            + " WHERE model=? AND status='ACTIVE' FOR UPDATE",
                    device.get("model"));
            if (releases.isEmpty()) {
                throw ApiException.notFound("NO_TASK_AVAILABLE", "no ACTIVE release for device model");
            }
            Map<String, Object> release = releases.get(0);
            long releaseId = ((Number) release.get("id")).longValue();

            List<TaskResponse> existing = jdbc.query(
                    "SELECT * FROM rollout_task WHERE release_id=? AND device_id=?",
                    MAPPER, releaseId, req.deviceId());
            if (!existing.isEmpty()) {
                return existing.get(0);
            }

            int ratio = ((Number) release.get("ratio")).intValue();
            String fromVersion = (String) release.get("from_version");
            boolean eligible = fromVersion.equals(device.get("current_version"))
                    && ((Number) device.get("bucket")).intValue() < ratio;
            if (!eligible) {
                throw ApiException.notFound("NO_TASK_AVAILABLE",
                        "device is not eligible for the ACTIVE release");
            }

            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO rollout_task(release_id, device_id, model, from_version, to_version, status)"
                                + " VALUES (?,?,?,?,?,'PENDING')",
                        new String[]{"id"});
                ps.setLong(1, releaseId);
                ps.setString(2, req.deviceId());
                ps.setString(3, (String) device.get("model"));
                ps.setString(4, fromVersion);
                ps.setString(5, (String) release.get("to_version"));
                return ps;
            }, keyHolder);
            long taskId = keyHolder.getKey().longValue();
            return new TaskResponse(taskId, releaseId, req.deviceId(), (String) device.get("model"),
                    fromVersion, (String) release.get("to_version"), "PENDING");
        });
    }

    /**
     * 设备回执：首次回执终结任务，仅 SUCCESS 更新设备当前版本；同结果重复回执成功，
     * 不同结果返回 409；已取消任务的迟到回执返回 409 且不更新设备版本。
     */
    @Transactional
    public TaskResponse receipt(long taskId, ReceiptRequest req) {
        String hash = String.join("|", String.valueOf(taskId), req.result());
        return idempotency.execute(req.requestId(), "TASK_RECEIPT", hash, TaskResponse.class, () -> {
            List<TaskResponse> rows = jdbc.query(
                    "SELECT * FROM rollout_task WHERE id=? FOR UPDATE", MAPPER, taskId);
            if (rows.isEmpty()) {
                throw ApiException.notFound("TASK_NOT_FOUND", "task not found: " + taskId);
            }
            TaskResponse task = rows.get(0);
            switch (task.status()) {
                case "PENDING" -> {
                    jdbc.update("UPDATE rollout_task SET status=?, updated_at=? WHERE id=?",
                            req.result(), Timestamp.from(Instant.now()), taskId);
                    if ("SUCCESS".equals(req.result())) {
                        jdbc.update("UPDATE device SET current_version=? WHERE device_id=?",
                                task.toVersion(), task.deviceId());
                    }
                    return new TaskResponse(task.id(), task.releaseId(), task.deviceId(), task.model(),
                            task.fromVersion(), task.toVersion(), req.result());
                }
                case "SUCCESS", "FAILED" -> {
                    if (task.status().equals(req.result())) {
                        return task;
                    }
                    throw ApiException.conflict("RECEIPT_CONFLICT",
                            "task already finalized as " + task.status());
                }
                default -> throw ApiException.conflict("TASK_CANCELLED",
                        "task was cancelled, receipt rejected");
            }
        });
    }

    /**
     * 任务明细查询，支持按发布单与设备过滤。
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> query(Long releaseId, String deviceId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM rollout_task WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (releaseId != null) {
            sql.append(" AND release_id=?");
            args.add(releaseId);
        }
        if (deviceId != null) {
            sql.append(" AND device_id=?");
            args.add(deviceId);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
