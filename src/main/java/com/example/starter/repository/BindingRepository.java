package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.TaskBinding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 任务绑定仓库：负责绑定写入、幂等查询、重叠占用检查与作废影响更新。
 */
@Repository
public class BindingRepository {

    private static final RowMapper<TaskBinding> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public BindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static TaskBinding map(ResultSet rs) throws SQLException {
        return new TaskBinding(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getLong("task_id"),
                rs.getLong("antenna_id"),
                rs.getLong("calibration_id"),
                rs.getBigDecimal("azimuth_deviation"),
                rs.getBigDecimal("elevation_deviation"),
                JdbcTimes.read(rs, "calibration_valid_until"),
                BindingStatus.valueOf(rs.getString("status")),
                rs.getBoolean("voided_mark"),
                JdbcTimes.read(rs, "bound_at"),
                JdbcTimes.read(rs, "updated_at"));
    }

    /** 插入绑定并返回自增主键；request_id 唯一约束冲突时抛出异常由上层裁决。 */
    public long insert(String requestId, long taskId, long antennaId, long calibrationId,
            java.math.BigDecimal azimuth, java.math.BigDecimal elevation,
            Instant calibrationValidUntil, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task_binding (request_id, task_id, antenna_id, calibration_id,"
                            + " azimuth_deviation, elevation_deviation, calibration_valid_until,"
                            + " status, voided_mark, bound_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,'BOUND',FALSE,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, taskId);
            ps.setLong(3, antennaId);
            ps.setLong(4, calibrationId);
            ps.setBigDecimal(5, azimuth);
            ps.setBigDecimal(6, elevation);
            ps.setTimestamp(7, JdbcTimes.toSql(calibrationValidUntil));
            ps.setTimestamp(8, JdbcTimes.toSql(now));
            ps.setTimestamp(9, JdbcTimes.toSql(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<TaskBinding> findById(long id) {
        return jdbc.query("SELECT * FROM task_binding WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<TaskBinding> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM task_binding WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 查询任务当前绑定。 */
    public Optional<TaskBinding> findByTaskId(long taskId) {
        return jdbc.query("SELECT * FROM task_binding WHERE task_id = ? ORDER BY id",
                MAPPER, taskId).stream().findFirst();
    }

    /** 统计同一天线上与新任务区间重叠的有效绑定数（仅 BOUND 占用天线）。 */
    public int countOverlapping(long antennaId, Instant startAt, Instant endAt) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_binding b JOIN receive_task t ON b.task_id = t.id"
                        + " WHERE b.antenna_id = ? AND b.status = 'BOUND'"
                        + " AND t.start_at < ? AND ? < t.end_at",
                Integer.class, antennaId, JdbcTimes.toSql(endAt), JdbcTimes.toSql(startAt));
        return count == null ? 0 : count;
    }

    /** 查询某校准的全部绑定（含任务区间），用于作废影响计算与查询展示。 */
    public List<BindingWithTask> findByCalibrationId(long calibrationId) {
        return jdbc.query(
                "SELECT b.*, t.start_at AS task_start, t.end_at AS task_end, t.task_code AS task_code"
                        + " FROM task_binding b JOIN receive_task t ON b.task_id = t.id"
                        + " WHERE b.calibration_id = ? ORDER BY b.id",
                (rs, rowNum) -> new BindingWithTask(map(rs),
                        rs.getString("task_code"),
                        JdbcTimes.read(rs, "task_start"),
                        JdbcTimes.read(rs, "task_end")),
                calibrationId);
    }

    /** 作废影响：未开始的绑定转为待重配。 */
    public int markPendingReassign(long bindingId, Instant now) {
        return jdbc.update(
                "UPDATE task_binding SET status = 'PENDING_REASSIGN', updated_at = ? WHERE id = ?",
                JdbcTimes.toSql(now), bindingId);
    }

    /** 作废影响：已完成/进行中的绑定保留旧依据，仅置作废标记。 */
    public int markVoidedBasis(long bindingId, Instant now) {
        return jdbc.update(
                "UPDATE task_binding SET voided_mark = TRUE, updated_at = ? WHERE id = ?",
                JdbcTimes.toSql(now), bindingId);
    }

    /** 绑定及其任务区间的组合视图。 */
    public record BindingWithTask(TaskBinding binding, String taskCode, Instant taskStart,
            Instant taskEnd) {
    }
}