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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务数据访问。同设备同发布单由唯一约束 uk_task_release_device 保证最多一条。
 * 任务在创建与完成时分别固化发布单版本快照；冻结扫荡把命中 PENDING 任务转为 RELEASE_FROZEN
 * 并在同一 UPDATE 固化冻结令快照。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<RolloutTask> MAPPER = (rs, rowNum) -> {
        String firstResult = rs.getString("first_result");
        Long frozenFreezeId = (Long) rs.getObject("frozen_freeze_id");
        RolloutTask.FreezeSnapshot snapshot = null;
        if (frozenFreezeId != null) {
            snapshot = new RolloutTask.FreezeSnapshot(frozenFreezeId, rs.getInt("frozen_freeze_version"),
                    rs.getString("frozen_models"), rs.getString("frozen_release_ids"),
                    rs.getString("frozen_start_utc"), rs.getString("frozen_end_utc"),
                    rs.getString("frozen_at_utc"));
        }
        Integer versionAtCreate = (Integer) rs.getObject("release_version_at_create");
        Integer versionAtComplete = (Integer) rs.getObject("release_version_at_complete");
        return new RolloutTask(rs.getLong("id"), rs.getLong("release_id"), rs.getString("device_id"),
                TaskStatus.valueOf(rs.getString("status")),
                firstResult == null ? null : ReceiptResult.valueOf(firstResult),
                versionAtCreate, versionAtComplete, snapshot, (Long) rs.getObject("emergency_freeze_id"));
    };

    private static final String COLUMNS = "id, release_id, device_id, status, first_result,"
            + " release_version_at_create, release_version_at_complete, frozen_freeze_id,"
            + " frozen_freeze_version, frozen_models, frozen_release_ids, frozen_start_utc,"
            + " frozen_end_utc, frozen_at_utc, emergency_freeze_id";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long releaseId, String deviceId, int releaseVersionAtCreate, Long emergencyFreezeId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, release_version_at_create,"
                            + " emergency_freeze_id) VALUES (?, ?, 'PENDING', ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            ps.setInt(3, releaseVersionAtCreate);
            if (emergencyFreezeId == null) {
                ps.setObject(4, null);
            } else {
                ps.setLong(4, emergencyFreezeId);
            }
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

    public void complete(long id, ReceiptResult result, Integer releaseVersionAtComplete) {
        jdbc.update("UPDATE rollout_task SET status = ?, first_result = ?, release_version_at_complete = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                result.name(), result.name(), releaseVersionAtComplete, id);
    }

    public int cancelPendingByRelease(long releaseId) {
        return jdbc.update("UPDATE rollout_task SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP"
                + " WHERE release_id = ? AND status = 'PENDING'", releaseId);
    }

    /**
     * 冻结扫荡：把命中型号或发布单范围、仍为 PENDING 的任务转为 RELEASE_FROZEN 并固化冻结令快照。
     * 已终结（SUCCESS/FAILED/CANCELLED/RELEASE_FROZEN）任务不改写。
     * 调用方持有冻结令行锁，按冻结令提交顺序串行扫荡。
     *
     * @return 本次转为 RELEASE_FROZEN 的任务数
     */
    public int freezePendingByScope(long freezeId, int freezeVersion, String models, String releaseIds,
                                    String startUtc, String endUtc, List<String> modelScope,
                                    List<Long> releaseScope, String frozenAtUtc) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE rollout_task SET status = 'RELEASE_FROZEN',")
                .append(" frozen_freeze_id = ?, frozen_freeze_version = ?, frozen_models = ?,")
                .append(" frozen_release_ids = ?, frozen_start_utc = ?, frozen_end_utc = ?,")
                .append(" frozen_at_utc = ?, updated_at = CURRENT_TIMESTAMP")
                .append(" WHERE status = 'PENDING' AND (emergency_freeze_id IS NULL")
                .append(" OR emergency_freeze_id <> ?) AND (");
        args.add(freezeId);
        args.add(freezeVersion);
        args.add(models);
        args.add(releaseIds);
        args.add(startUtc);
        args.add(endUtc);
        args.add(frozenAtUtc);
        args.add(freezeId);
        boolean hasRelease = !releaseScope.isEmpty();
        boolean hasModel = !modelScope.isEmpty();
        if (hasRelease) {
            sql.append("release_id IN (").append(placeholders(releaseScope.size())).append(")");
            args.addAll(releaseScope);
        }
        if (hasRelease && hasModel) {
            sql.append(" OR ");
        }
        if (hasModel) {
            sql.append("release_id IN (SELECT id FROM release_order WHERE model IN (")
                    .append(placeholders(modelScope.size())).append("))");
            args.addAll(modelScope);
        }
        sql.append(")");
        return jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * 撤销冻结令后，命中其范围且尚未开始（PENDING）的任务恢复可投放语义：
     * 任务本身保持 PENDING，本方法仅用于统计受影响任务数，不改写数据。
     */
    public long countPendingByScope(List<String> models, List<Long> releaseIds) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM rollout_task t WHERE t.status = 'PENDING' AND (");
        boolean hasRelease = !releaseIds.isEmpty();
        boolean hasModel = !models.isEmpty();
        if (hasRelease) {
            sql.append("t.release_id IN (").append(placeholders(releaseIds.size())).append(")");
            args.addAll(releaseIds);
        }
        if (hasRelease && hasModel) {
            sql.append(" OR ");
        }
        if (hasModel) {
            sql.append("t.release_id IN (SELECT id FROM release_order WHERE model IN (")
                    .append(placeholders(models.size())).append("))");
            args.addAll(models);
        }
        sql.append(")");
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<RolloutTask> findByRelease(long releaseId, TaskStatus statusFilter) {
        if (statusFilter == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? ORDER BY id",
                    MAPPER, releaseId);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM rollout_task WHERE release_id = ? AND status = ? ORDER BY id",
                MAPPER, releaseId, statusFilter.name());
    }

    public long countByReleaseAndDevice(long releaseId, String deviceId) {        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId);
        return count == null ? 0 : count;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
