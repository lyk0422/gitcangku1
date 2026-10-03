package com.example.starter.calibration;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 任务绑定访问。
 */
@Repository
public class BindingRepository {

    private static final String COLUMNS =
            "id, request_id, task_id, calibration_id, antenna_id, azimuth_deviation_deg,"
            + " elevation_deviation_deg, status, calibration_invalidated, created_at";

    private static final RowMapper<TaskBinding> MAPPER = (rs, n) -> new TaskBinding(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("task_id"),
            rs.getLong("calibration_id"),
            rs.getLong("antenna_id"),
            rs.getBigDecimal("azimuth_deviation_deg"),
            rs.getBigDecimal("elevation_deviation_deg"),
            BindingStatus.valueOf(rs.getString("status")),
            rs.getBoolean("calibration_invalidated"),
            JdbcTimes.toInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public BindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入绑定并返回主键。 */
    public long insert(TaskBinding b) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task_binding (request_id, task_id, calibration_id, antenna_id,"
                    + " azimuth_deviation_deg, elevation_deviation_deg, status, calibration_invalidated,"
                    + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, b.requestId());
            ps.setLong(2, b.taskId());
            ps.setLong(3, b.calibrationId());
            ps.setLong(4, b.antennaId());
            ps.setBigDecimal(5, b.azimuthDeviationDeg());
            ps.setBigDecimal(6, b.elevationDeviationDeg());
            ps.setString(7, b.status().name());
            ps.setBoolean(8, b.calibrationInvalidated());
            ps.setTimestamp(9, JdbcTimes.toParam(b.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按幂等键查询。 */
    public Optional<TaskBinding> findByRequestId(String requestId) {
        List<TaskBinding> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM task_binding WHERE request_id = ?", MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /** 查询任务当前生效的绑定（BOUND 或 PENDING_REASSIGN）。 */
    public Optional<TaskBinding> findCurrentByTaskId(long taskId) {
        List<TaskBinding> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM task_binding WHERE task_id = ? AND status IN ('BOUND', 'PENDING_REASSIGN')",
                MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /** 查询任务的全部绑定历史。 */
    public List<TaskBinding> findByTaskId(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_binding WHERE task_id = ? ORDER BY id",
                MAPPER, taskId);
    }

    /** 查询引用指定校准的全部绑定。 */
    public List<TaskBinding> findByCalibrationId(long calibrationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_binding WHERE calibration_id = ? ORDER BY id",
                MAPPER, calibrationId);
    }

    /** 查询同一天线上与给定区间重叠的 BOUND 绑定。 */
    public List<TaskBinding> findActiveOverlapping(long antennaId, Instant start, Instant end) {
        return jdbc.query("SELECT b." + COLUMNS.replace(", ", ", b.")
                + " FROM task_binding b JOIN receive_task t ON t.id = b.task_id"
                + " WHERE b.antenna_id = ? AND b.status = 'BOUND'"
                + " AND t.start_time < ? AND t.end_time > ?",
                MAPPER, antennaId, JdbcTimes.toParam(end), JdbcTimes.toParam(start));
    }

    /** 作废影响：未开始任务的 BOUND 绑定转为待重配并标记作废，返回受影响主键。 */
    public List<Long> demoteNotStartedToPendingReassign(long calibrationId, Instant now) {
        List<Long> ids = jdbc.query(
                "SELECT b.id FROM task_binding b JOIN receive_task t ON t.id = b.task_id"
                + " WHERE b.calibration_id = ? AND b.status = 'BOUND'"
                + " AND t.status = 'SCHEDULED' AND t.start_time > ?",
                (rs, n) -> rs.getLong("id"), calibrationId, JdbcTimes.toParam(now));
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE task_binding SET status = 'PENDING_REASSIGN', calibration_invalidated = TRUE"
                    + " WHERE calibration_id = ? AND status = 'BOUND' AND id IN ("
                    + "SELECT b.id FROM task_binding b JOIN receive_task t ON t.id = b.task_id"
                    + " WHERE b.calibration_id = ? AND t.status = 'SCHEDULED' AND t.start_time > ?)",
                    calibrationId, calibrationId, JdbcTimes.toParam(now));
        }
        return ids;
    }

    /** 作废影响：其余 BOUND 绑定（已完成或进行中任务）保留旧依据并标记作废，返回受影响主键。 */
    public List<Long> flagRemainingBoundAsInvalidated(long calibrationId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM task_binding WHERE calibration_id = ? AND status = 'BOUND'",
                (rs, n) -> rs.getLong("id"), calibrationId);
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE task_binding SET calibration_invalidated = TRUE"
                    + " WHERE calibration_id = ? AND status = 'BOUND'", calibrationId);
        }
        return ids;
    }

    /** 将旧绑定标记为已被重配替换。 */
    public void markSuperseded(long bindingId) {
        jdbc.update("UPDATE task_binding SET status = 'SUPERSEDED' WHERE id = ?", bindingId);
    }
}