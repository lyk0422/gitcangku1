package com.example.starter.plan.repo;

import com.example.starter.plan.model.ActualEvent;
import com.example.starter.plan.model.ActualLock;
import com.example.starter.plan.model.ActualStatus;
import com.example.starter.plan.model.OccupancyActual;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 实际进出状态与轨迹的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class ActualRepository {

    private static final RowMapper<OccupancyActual> OCCUPANCY_ACTUAL_MAPPER = (rs, n) ->
            new OccupancyActual(
                    rs.getLong("id"),
                    rs.getLong("plan_id"),
                    rs.getInt("seq"),
                    rs.getString("train_no"),
                    rs.getString("section_id"),
                    Instant.ofEpochMilli(rs.getLong("start_utc")),
                    Instant.ofEpochMilli(rs.getLong("end_utc")),
                    ActualStatus.valueOf(rs.getString("actual_status")));

    private static final RowMapper<ActualEvent> EVENT_MAPPER = (rs, n) -> new ActualEvent(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getLong("occupancy_id"),
            rs.getInt("seq"),
            rs.getString("event_type"),
            Instant.ofEpochMilli(rs.getLong("server_utc")),
            rs.getInt("plan_version"),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    private static final RowMapper<ActualLock> LOCK_MAPPER = (rs, n) -> new ActualLock(
            rs.getString("schedule_key"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("entered_at")));

    private final JdbcTemplate jdbc;

    public ActualRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按计划 id 与占用号查询占用（含实际状态）并加行级写锁，须在事务内调用。
     */
    public Optional<OccupancyActual> findOccupancyActualForUpdate(long planId, int seq) {
        return jdbc.query("SELECT id, plan_id, seq, train_no, section_id, start_utc, end_utc,"
                        + " actual_status FROM rail_plan_occupancy"
                        + " WHERE plan_id = ? AND seq = ? FOR UPDATE",
                OCCUPANCY_ACTUAL_MAPPER, planId, seq).stream().findFirst();
    }

    /**
     * 查询计划全部占用（含实际状态），按计划内序号升序。
     */
    public List<OccupancyActual> findOccupancyActuals(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, train_no, section_id, start_utc, end_utc,"
                        + " actual_status FROM rail_plan_occupancy WHERE plan_id = ? ORDER BY seq",
                OCCUPANCY_ACTUAL_MAPPER, planId);
    }

    /**
     * 统计计划内指定实际状态的占用条数。
     */
    public int countByPlanAndStatus(long planId, ActualStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_occupancy WHERE plan_id = ? AND actual_status = ?",
                Integer.class, planId, status.name());
        return count == null ? 0 : count;
    }

    /**
     * 更新占用实际状态（NOT_ENTERED → INSIDE → EXITED 单向推进）。
     */
    public void updateActualStatus(long occupancyId, ActualStatus status) {
        jdbc.update("UPDATE rail_plan_occupancy SET actual_status = ? WHERE id = ?",
                status.name(), occupancyId);
    }

    /**
     * 追加实际进出轨迹事件（不可变）。
     */
    public void insertEvent(long planId, long occupancyId, int seq, String eventType,
                            long serverUtcMillis, int planVersion, String operator,
                            String requestId, long nowMillis) {
        jdbc.update("INSERT INTO rail_actual_event"
                        + " (plan_id, occupancy_id, seq, event_type, server_utc, plan_version,"
                        + " operator, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                planId, occupancyId, seq, eventType, serverUtcMillis, planVersion, operator,
                requestId, nowMillis);
    }

    /**
     * 查询计划的全部实际轨迹事件，按事件 id 升序。
     */
    public List<ActualEvent> findEventsByPlan(long planId) {
        return jdbc.query("SELECT id, plan_id, occupancy_id, seq, event_type, server_utc,"
                        + " plan_version, operator, request_id, created_at"
                        + " FROM rail_actual_event WHERE plan_id = ? ORDER BY id",
                EVENT_MAPPER, planId);
    }

    /**
     * 查询指定区段当前的全部实际锁（INSIDE 占用及其进入时刻），按进入时刻升序。
     */
    public List<ActualLock> findInsideLocksBySection(String sectionId) {
        return jdbc.query("SELECT p.schedule_key, o.seq, o.train_no, o.section_id,"
                        + " e.server_utc AS entered_at"
                        + " FROM rail_plan_occupancy o"
                        + " JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " JOIN rail_actual_event e ON e.occupancy_id = o.id"
                        + " AND e.event_type = 'ENTER'"
                        + " WHERE o.section_id = ? AND o.actual_status = 'INSIDE'"
                        + " ORDER BY e.server_utc",
                LOCK_MAPPER, sectionId);
    }
}
