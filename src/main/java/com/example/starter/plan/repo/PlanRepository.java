package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.EffectiveStationStop;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.PublishedStop;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.StationStop;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 日计划与占用的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class PlanRepository {

    private static final RowMapper<DayPlan> PLAN_MAPPER = (rs, n) -> new DayPlan(
            rs.getLong("id"),
            rs.getString("schedule_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getInt("version"),
            PlanStatus.valueOf(rs.getString("status")));

    private static final RowMapper<Occupancy> OCCUPANCY_MAPPER = (rs, n) -> new Occupancy(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private static final RowMapper<RescheduleLink> LINK_MAPPER = (rs, n) -> new RescheduleLink(
            rs.getLong("id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getLong("created_at"));

    private static final RowMapper<StationStop> STOP_MAPPER = (rs, n) -> {
        long arrival = rs.getLong("arrival_utc");
        Long arrivalMillis = rs.wasNull() ? null : arrival;
        long departure = rs.getLong("departure_utc");
        Long departureMillis = rs.wasNull() ? null : departure;
        return new StationStop(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                rs.getInt("seq"),
                rs.getString("train_no"),
                rs.getString("station_id"),
                arrivalMillis == null ? null : Instant.ofEpochMilli(arrivalMillis),
                departureMillis == null ? null : Instant.ofEpochMilli(departureMillis));
    };

    private static final RowMapper<PublishedStop> PUBLISHED_STOP_MAPPER = (rs, n) -> {
        long arrival = rs.getLong("arrival_utc");
        Long arrivalMillis = rs.wasNull() ? null : arrival;
        long departure = rs.getLong("departure_utc");
        Long departureMillis = rs.wasNull() ? null : departure;
        return new PublishedStop(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                rs.getObject("op_date", LocalDate.class),
                rs.getInt("seq"),
                rs.getString("train_no"),
                rs.getString("station_id"),
                arrivalMillis == null ? null : Instant.ofEpochMilli(arrivalMillis),
                departureMillis == null ? null : Instant.ofEpochMilli(departureMillis),
                rs.getInt("station_version"),
                rs.getInt("min_dwell_minutes"),
                rs.getInt("platform_capacity"),
                rs.getLong("published_at"));
    };

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新计划，返回自增主键。
     */
    public long insertPlan(String scheduleKey, LocalDate opDate, PlanStatus status, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_day_plan (schedule_key, op_date, version, status, created_at, updated_at)"
                            + " VALUES (?, ?, 1, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scheduleKey);
            ps.setDate(2, Date.valueOf(opDate));
            ps.setString(3, status.name());
            ps.setLong(4, nowMillis);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询计划（不加锁）。
     */
    public Optional<DayPlan> findByKey(String scheduleKey) {
        return jdbc.query("SELECT id, schedule_key, op_date, version, status FROM rail_day_plan"
                        + " WHERE schedule_key = ?",
                PLAN_MAPPER, scheduleKey).stream().findFirst();
    }

    /**
     * 按主键查询计划（不加锁），用于改签链遍历。
     */
    public Optional<DayPlan> findById(long planId) {
        return jdbc.query("SELECT id, schedule_key, op_date, version, status FROM rail_day_plan"
                        + " WHERE id = ?",
                PLAN_MAPPER, planId).stream().findFirst();
    }

    /**
     * 按业务键查询计划并加行级写锁，须在事务内调用，用于串行化同一计划的更新/发布/取消。
     */
    public Optional<DayPlan> findByKeyForUpdate(String scheduleKey) {
        return jdbc.query("SELECT id, schedule_key, op_date, version, status FROM rail_day_plan"
                        + " WHERE schedule_key = ? FOR UPDATE",
                PLAN_MAPPER, scheduleKey).stream().findFirst();
    }

    /**
     * 整体替换计划的占用清单：先删后插，保持提交顺序。
     */
    public void replaceOccupancies(long planId, List<Occupancy> occupancies) {
        jdbc.update("DELETE FROM rail_plan_occupancy WHERE plan_id = ?", planId);
        insertOccupancies(planId, occupancies);
    }

    /**
     * 批量插入占用。
     */
    public void insertOccupancies(long planId, List<Occupancy> occupancies) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_occupancy (plan_id, seq, train_no, section_id, start_utc, end_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                occupancies, occupancies.size(),
                (ps, o) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, o.seq());
                    ps.setString(3, o.trainNo());
                    ps.setString(4, o.sectionId());
                    ps.setLong(5, o.startUtc().toEpochMilli());
                    ps.setLong(6, o.endUtc().toEpochMilli());
                });
    }

    /**
     * 查询计划占用，按计划内序号升序。
     */
    public List<Occupancy> findOccupancies(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, train_no, section_id, start_utc, end_utc"
                        + " FROM rail_plan_occupancy WHERE plan_id = ? ORDER BY seq",
                OCCUPANCY_MAPPER, planId);
    }

    /**
     * 更新版本与状态。
     */
    public void updateVersionAndStatus(long planId, int version, PlanStatus status, long nowMillis) {
        jdbc.update("UPDATE rail_day_plan SET version = ?, status = ?, updated_at = ? WHERE id = ?",
                version, status.name(), nowMillis, planId);
    }

    /**
     * 仅更新状态（发布/取消不改变版本与占用）。
     */
    public void updateStatus(long planId, PlanStatus status, long nowMillis) {
        jdbc.update("UPDATE rail_day_plan SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), nowMillis, planId);
    }

    /**
     * 查询指定运营日、指定区段集合上其他已发布计划的生效时隙（排除给定计划集合）。
     */
    public List<PublishedSlot> findPublishedSlots(LocalDate opDate, Collection<String> sectionIds,
                                                  Collection<Long> excludePlanIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner sectionPlaceholders = new StringJoiner(", ");
        sectionIds.forEach(s -> sectionPlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT p.schedule_key, o.train_no, o.section_id, o.start_utc, o.end_utc"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND p.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" AND o.section_id IN (").append(sectionPlaceholders)
                .append(") ORDER BY o.section_id, o.start_utc");
        args.addAll(sectionIds);
        return jdbc.query(sql.toString(), (rs, n) -> new PublishedSlot(
                rs.getString("schedule_key"),
                rs.getString("train_no"),
                rs.getString("section_id"),
                Instant.ofEpochMilli(rs.getLong("start_utc")),
                Instant.ofEpochMilli(rs.getLong("end_utc"))), args.toArray());
    }

    /**
     * 追加改签前后继关联（不可变）；唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertRescheduleLink(long predecessorPlanId, long successorPlanId, long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_reschedule_link"
                        + " (predecessor_plan_id, successor_plan_id, created_at) VALUES (?, ?, ?)",
                predecessorPlanId, successorPlanId, nowMillis);
    }

    /**
     * 查询以指定计划为直接前驱的改签关联（即该计划的直接后继）。
     */
    public Optional<RescheduleLink> findLinkByPredecessor(long predecessorPlanId) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, created_at"
                        + " FROM rail_plan_reschedule_link WHERE predecessor_plan_id = ?",
                LINK_MAPPER, predecessorPlanId).stream().findFirst();
    }

    /**
     * 查询以指定计划为直接后继的改签关联（即该计划的直接前驱）。
     */
    public Optional<RescheduleLink> findLinkBySuccessor(long successorPlanId) {
        return jdbc.query("SELECT id, predecessor_plan_id, successor_plan_id, created_at"
                        + " FROM rail_plan_reschedule_link WHERE successor_plan_id = ?",
                LINK_MAPPER, successorPlanId).stream().findFirst();
    }

    /**
     * 获取发布全局互斥锁（单行 FOR UPDATE），串行化所有发布事务。
     */
    public void acquirePublishLock() {
        jdbc.queryForObject("SELECT id FROM publish_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    /**
     * 整体替换计划的站停登记：先删后插，须与占用替换处于同一事务。
     */
    public void replaceStationStops(long planId, List<StationStop> stops) {
        jdbc.update("DELETE FROM rail_plan_station_stop WHERE plan_id = ?", planId);
        insertStationStops(planId, stops);
    }

    /**
     * 批量插入站停登记。
     */
    public void insertStationStops(long planId, List<StationStop> stops) {
        if (stops.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_station_stop (plan_id, seq, train_no, station_id,"
                        + " arrival_utc, departure_utc) VALUES (?, ?, ?, ?, ?, ?)",
                stops, stops.size(),
                (ps, s) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, s.seq());
                    ps.setString(3, s.trainNo());
                    ps.setString(4, s.stationId());
                    if (s.arrivalUtc() == null) {
                        ps.setNull(5, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(5, s.arrivalUtc().toEpochMilli());
                    }
                    if (s.departureUtc() == null) {
                        ps.setNull(6, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(6, s.departureUtc().toEpochMilli());
                    }
                });
    }

    /**
     * 查询计划登记站停，按计划内序号升序，null 时刻原样返回。
     */
    public List<StationStop> findStationStops(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, train_no, station_id, arrival_utc, departure_utc"
                        + " FROM rail_plan_station_stop WHERE plan_id = ? ORDER BY seq",
                STOP_MAPPER, planId);
    }

    /**
     * 批量固化发布站停快照。重复发布同一计划同一序号时违反唯一约束并整体回滚。
     */
    public void insertPublishedStops(List<PublishedStop> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_published_stop (plan_id, op_date, seq, train_no, station_id,"
                        + " arrival_utc, departure_utc, station_version, min_dwell_minutes,"
                        + " platform_capacity, published_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshots, snapshots.size(),
                (ps, s) -> {
                    ps.setLong(1, s.planId());
                    ps.setDate(2, Date.valueOf(s.opDate()));
                    ps.setInt(3, s.seq());
                    ps.setString(4, s.trainNo());
                    ps.setString(5, s.stationId());
                    if (s.arrivalUtc() == null) {
                        ps.setNull(6, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(6, s.arrivalUtc().toEpochMilli());
                    }
                    if (s.departureUtc() == null) {
                        ps.setNull(7, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(7, s.departureUtc().toEpochMilli());
                    }
                    ps.setInt(8, s.stationVersion());
                    ps.setInt(9, s.minDwellMinutes());
                    ps.setInt(10, s.platformCapacity());
                    ps.setLong(11, s.publishedAt());
                });
    }

    /**
     * 查询计划的发布站停快照（取消后仍保留），按序号升序。
     */
    public List<PublishedStop> findPublishedStopsByPlan(long planId) {
        return jdbc.query("SELECT id, plan_id, op_date, seq, train_no, station_id, arrival_utc,"
                        + " departure_utc, station_version, min_dwell_minutes, platform_capacity,"
                        + " published_at FROM rail_published_stop WHERE plan_id = ? ORDER BY seq",
                PUBLISHED_STOP_MAPPER, planId);
    }

    /**
     * 查询指定运营日、给定站点集合上仍生效（计划状态为 PUBLISHED）的全部站停快照，
     * 携带计划业务键；按站点、到达时刻、id 升序返回，作为容量裁决与诊断查询的稳定排序。
     * 排除给定计划集合（发布排除自身，改签排除新旧两个计划）。
     */
    public List<EffectiveStationStop> findEffectiveStationStops(LocalDate opDate,
                                                                Collection<String> stationIds,
                                                                Collection<Long> excludePlanIds) {
        if (stationIds.isEmpty()) {
            return List.of();
        }
        StringJoiner stationPlaceholders = new StringJoiner(", ");
        stationIds.forEach(s -> stationPlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT s.plan_id, p.schedule_key, s.op_date, s.seq, s.train_no, s.station_id,"
                        + " s.arrival_utc, s.departure_utc, s.station_version,"
                        + " s.min_dwell_minutes, s.platform_capacity"
                        + " FROM rail_published_stop s JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND s.op_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND s.plan_id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" AND s.station_id IN (").append(stationPlaceholders)
                .append(") ORDER BY s.station_id, s.arrival_utc, s.id");
        args.addAll(stationIds);
        return jdbc.query(sql.toString(), (rs, n) -> {
            long arrival = rs.getLong("arrival_utc");
            Long arrivalMillis = rs.wasNull() ? null : arrival;
            long departure = rs.getLong("departure_utc");
            Long departureMillis = rs.wasNull() ? null : departure;
            return new EffectiveStationStop(
                    rs.getLong("plan_id"),
                    rs.getString("schedule_key"),
                    rs.getObject("op_date", LocalDate.class),
                    rs.getInt("seq"),
                    rs.getString("train_no"),
                    rs.getString("station_id"),
                    arrivalMillis == null ? null : Instant.ofEpochMilli(arrivalMillis),
                    departureMillis == null ? null : Instant.ofEpochMilli(departureMillis),
                    rs.getInt("station_version"),
                    rs.getInt("min_dwell_minutes"),
                    rs.getInt("platform_capacity"));
        }, args.toArray());
    }
}
