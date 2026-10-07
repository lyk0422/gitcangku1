package com.example.starter.plan.repo;

import com.example.starter.plan.model.ActualEvent;
import com.example.starter.plan.model.ActualLock;
import com.example.starter.plan.model.ActualOccupancy;
import com.example.starter.plan.model.ActualStatus;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 实际进出状态与轨迹事件的 JDBC 持久化。所有时刻以 UTC 毫秒存储。
 */
@Repository
public class ActualRepository {

    private static final String OCCUPANCY_COLUMNS =
            "id, plan_id, seq, train_no, section_id, start_utc, end_utc,"
                    + " actual_status, entered_at, exited_at, exit_plan_version";

    private static final RowMapper<ActualOccupancy> OCCUPANCY_MAPPER = (rs, n) -> new ActualOccupancy(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            ActualStatus.valueOf(rs.getString("actual_status")),
            instantOrNull(rs, "entered_at"),
            instantOrNull(rs, "exited_at"),
            rs.getObject("exit_plan_version", Integer.class));

    private static final RowMapper<ActualEvent> EVENT_MAPPER = (rs, n) -> new ActualEvent(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getLong("occupancy_id"),
            rs.getInt("seq"),
            rs.getString("event_type"),
            rs.getLong("server_utc"),
            rs.getInt("plan_version"),
            rs.getString("operator_name"),
            rs.getString("request_id"));

    private final JdbcTemplate jdbc;

    public ActualRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按计划与占用序号查询占用并加行级写锁，须在事务内调用。
     */
    public Optional<ActualOccupancy> findOccupancyForUpdate(long planId, int seq) {
        return jdbc.query("SELECT " + OCCUPANCY_COLUMNS + " FROM rail_plan_occupancy"
                        + " WHERE plan_id = ? AND seq = ? FOR UPDATE",
                OCCUPANCY_MAPPER, planId, seq).stream().findFirst();
    }

    /**
     * 查询计划全部占用的实际状态，按计划内序号升序。
     */
    public List<ActualOccupancy> findOccupancies(long planId) {
        return jdbc.query("SELECT " + OCCUPANCY_COLUMNS + " FROM rail_plan_occupancy"
                        + " WHERE plan_id = ? ORDER BY seq",
                OCCUPANCY_MAPPER, planId);
    }

    /**
     * 指定运营日与区段上是否存在 INSIDE 占用（实际锁）。
     */
    public boolean existsInsideOnSection(LocalDate opDate, String sectionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.op_date = ? AND o.section_id = ? AND o.actual_status = 'INSIDE'",
                Integer.class, Date.valueOf(opDate), sectionId);
        return count != null && count > 0;
    }

    /**
     * 查询指定运营日与区段上当前 INSIDE 的实际锁。
     */
    public List<ActualLock> findInsideLocks(LocalDate opDate, String sectionId) {
        return jdbc.query(
                "SELECT p.schedule_key, o.seq, o.train_no, o.section_id, o.entered_at"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.op_date = ? AND o.section_id = ? AND o.actual_status = 'INSIDE'"
                        + " ORDER BY o.entered_at",
                (rs, n) -> new ActualLock(
                        rs.getString("schedule_key"),
                        rs.getInt("seq"),
                        rs.getString("train_no"),
                        rs.getString("section_id"),
                        Instant.ofEpochMilli(rs.getLong("entered_at"))),
                Date.valueOf(opDate), sectionId);
    }

    /**
     * 标记占用为 INSIDE 并记录实际进入时刻。
     */
    public void markInside(long occupancyId, long enteredAtMillis) {
        jdbc.update("UPDATE rail_plan_occupancy SET actual_status = 'INSIDE', entered_at = ?"
                + " WHERE id = ?", enteredAtMillis, occupancyId);
    }

    /**
     * 标记占用为 EXITED，记录驶出时刻与驶出时原计划版本；计划时隙不回改。
     */
    public void markExited(long occupancyId, long exitedAtMillis, int planVersion) {
        jdbc.update("UPDATE rail_plan_occupancy SET actual_status = 'EXITED', exited_at = ?,"
                        + " exit_plan_version = ? WHERE id = ?",
                exitedAtMillis, planVersion, occupancyId);
    }

    /**
     * 追加实际进出轨迹事件（与状态变更同事务）。
     */
    public void insertEvent(long planId, long occupancyId, int seq, String eventType,
                            long serverUtc, int planVersion, String operator, String requestId,
                            long createdAtMillis) {
        jdbc.update("INSERT INTO rail_actual_event"
                        + " (plan_id, occupancy_id, seq, event_type, server_utc, plan_version,"
                        + " operator_name, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                planId, occupancyId, seq, eventType, serverUtc, planVersion, operator, requestId,
                createdAtMillis);
    }

    /**
     * 查询计划的全部轨迹事件，按主键升序（即发生顺序）。
     */
    public List<ActualEvent> findEventsByPlan(long planId) {
        return jdbc.query("SELECT id, plan_id, occupancy_id, seq, event_type, server_utc,"
                        + " plan_version, operator_name, request_id FROM rail_actual_event"
                        + " WHERE plan_id = ? ORDER BY id",
                EVENT_MAPPER, planId);
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
