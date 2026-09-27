package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.PublishedDwell;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.SnapshotDwell;
import com.example.starter.plan.model.StationDwell;
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

    private static final RowMapper<StationDwell> DWELL_MAPPER = (rs, n) -> new StationDwell(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("station_id"),
            Instant.ofEpochMilli(rs.getLong("arrival_utc")),
            Instant.ofEpochMilli(rs.getLong("departure_utc")));

    private static final RowMapper<PublishedDwell> PUBLISHED_DWELL_MAPPER = (rs, n) ->
            new PublishedDwell(
                    rs.getString("schedule_key"),
                    rs.getString("train_no"),
                    rs.getString("station_id"),
                    Instant.ofEpochMilli(rs.getLong("arrival_utc")),
                    Instant.ofEpochMilli(rs.getLong("departure_utc")));

    private static final RowMapper<PublishSnapshot> SNAPSHOT_MAPPER = (rs, n) -> new PublishSnapshot(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getString("published_by"),
            rs.getLong("published_at"));

    private static final RowMapper<SnapshotDwell> SNAPSHOT_DWELL_MAPPER = (rs, n) ->
            new SnapshotDwell(
                    rs.getLong("snapshot_id"),
                    rs.getInt("seq"),
                    rs.getString("train_no"),
                    rs.getString("station_id"),
                    Instant.ofEpochMilli(rs.getLong("arrival_utc")),
                    Instant.ofEpochMilli(rs.getLong("departure_utc")),
                    rs.getInt("station_version"),
                    rs.getInt("min_dwell_minutes"),
                    rs.getInt("platform_capacity"),
                    rs.getInt("actual_dwell_minutes"));

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
     * 整体替换计划的中间站到发登记：先删后插，保持提交顺序。
     */
    public void replaceStationDwells(long planId, List<StationDwell> dwells) {
        jdbc.update("DELETE FROM rail_plan_station_dwell WHERE plan_id = ?", planId);
        insertStationDwells(planId, dwells);
    }

    /**
     * 批量插入中间站到发登记。
     */
    public void insertStationDwells(long planId, List<StationDwell> dwells) {
        if (dwells.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_station_dwell"
                        + " (plan_id, seq, train_no, station_id, arrival_utc, departure_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                dwells, dwells.size(),
                (ps, d) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, d.seq());
                    ps.setString(3, d.trainNo());
                    ps.setString(4, d.stationId());
                    ps.setLong(5, d.arrivalUtc().toEpochMilli());
                    ps.setLong(6, d.departureUtc().toEpochMilli());
                });
    }

    /**
     * 查询计划的中间站到发登记，按计划内序号升序。
     */
    public List<StationDwell> findStationDwells(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, train_no, station_id, arrival_utc, departure_utc"
                        + " FROM rail_plan_station_dwell WHERE plan_id = ? ORDER BY seq",
                DWELL_MAPPER, planId);
    }

    /**
     * 查询指定运营日、指定站点集合上其他已发布计划的生效站停（排除给定计划集合），
     * 按站点与到达时刻排序。区间左闭右开 [arrival, departure)。
     */
    public List<PublishedDwell> findPublishedDwells(LocalDate opDate, Collection<String> stationIds,
                                                    Collection<Long> excludePlanIds) {
        if (stationIds.isEmpty()) {
            return List.of();
        }
        StringJoiner stationPlaceholders = new StringJoiner(", ");
        stationIds.forEach(s -> stationPlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT p.schedule_key, d.train_no, d.station_id, d.arrival_utc, d.departure_utc"
                        + " FROM rail_plan_station_dwell d JOIN rail_day_plan p ON p.id = d.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND p.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" AND d.station_id IN (").append(stationPlaceholders)
                .append(") ORDER BY d.station_id, d.arrival_utc");
        args.addAll(stationIds);
        return jdbc.query(sql.toString(), PUBLISHED_DWELL_MAPPER, args.toArray());
    }

    /**
     * 写入发布快照主记录，返回自增快照 id。
     */
    public long insertSnapshot(long planId, String scheduleKey, String publishedBy, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_publish_snapshot"
                            + " (plan_id, schedule_key, published_by, published_at)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            ps.setString(2, scheduleKey);
            ps.setString(3, publishedBy);
            ps.setLong(4, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量写入快照站停固化行（不可变）。
     */
    public void insertSnapshotDwells(long snapshotId, List<SnapshotDwell> dwells) {
        if (dwells.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_snapshot_station_dwell"
                        + " (snapshot_id, seq, train_no, station_id, arrival_utc, departure_utc,"
                        + " station_version, min_dwell_minutes, platform_capacity, actual_dwell_minutes)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                dwells, dwells.size(),
                (ps, d) -> {
                    ps.setLong(1, snapshotId);
                    ps.setInt(2, d.seq());
                    ps.setString(3, d.trainNo());
                    ps.setString(4, d.stationId());
                    ps.setLong(5, d.arrivalUtc().toEpochMilli());
                    ps.setLong(6, d.departureUtc().toEpochMilli());
                    ps.setInt(7, d.stationVersion());
                    ps.setInt(8, d.minDwellMinutes());
                    ps.setInt(9, d.platformCapacity());
                    ps.setInt(10, d.actualDwellMinutes());
                });
    }

    /**
     * 按计划 id 查询全部发布快照，按快照 id 升序（发布提交顺序，稳定排序）。
     */
    public List<PublishSnapshot> findSnapshotsByPlan(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, published_by, published_at"
                        + " FROM rail_publish_snapshot WHERE plan_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, planId);
    }

    /**
     * 按站点查询发布快照主记录（经快照站停关联），按快照 id 升序去重。
     */
    public List<PublishSnapshot> findSnapshotsByStation(String stationId) {
        return jdbc.query(
                "SELECT s.id, s.plan_id, s.schedule_key, s.published_by, s.published_at"
                        + " FROM rail_publish_snapshot s"
                        + " WHERE s.id IN (SELECT DISTINCT snapshot_id FROM rail_snapshot_station_dwell"
                        + " WHERE station_id = ?) ORDER BY s.id",
                SNAPSHOT_MAPPER, stationId);
    }

    /**
     * 按快照 id 集合批量查询快照站停固化行，按快照 id、序号升序。
     */
    public List<SnapshotDwell> findSnapshotDwells(Collection<Long> snapshotIds) {
        if (snapshotIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        snapshotIds.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT snapshot_id, seq, train_no, station_id, arrival_utc, departure_utc,"
                        + " station_version, min_dwell_minutes, platform_capacity, actual_dwell_minutes"
                        + " FROM rail_snapshot_station_dwell WHERE snapshot_id IN ("
                        + placeholders + ") ORDER BY snapshot_id, seq",
                SNAPSHOT_DWELL_MAPPER, snapshotIds.toArray());
    }
}
