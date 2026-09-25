package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.StockSegment;
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

    private static final String PLAN_COLUMNS =
            "id, schedule_key, op_date, version, status, stock_no, origin_station,"
                    + " destination_station, rearrange_pending";

    private static final RowMapper<DayPlan> PLAN_MAPPER = (rs, n) -> new DayPlan(
            rs.getLong("id"),
            rs.getString("schedule_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getInt("version"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getString("stock_no"),
            rs.getString("origin_station"),
            rs.getString("destination_station"),
            rs.getBoolean("rearrange_pending"));

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

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新计划，返回自增主键。
     */
    public long insertPlan(String scheduleKey, LocalDate opDate, PlanStatus status, long nowMillis) {
        return insertPlan(scheduleKey, opDate, status, null, null, null, nowMillis);
    }

    /**
     * 插入新计划并登记车底与首末站，返回自增主键。
     */
    public long insertPlan(String scheduleKey, LocalDate opDate, PlanStatus status,
                           String stockNo, String originStation, String destinationStation,
                           long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_day_plan (schedule_key, op_date, version, status, stock_no,"
                            + " origin_station, destination_station, created_at, updated_at)"
                            + " VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scheduleKey);
            ps.setDate(2, Date.valueOf(opDate));
            ps.setString(3, status.name());
            ps.setString(4, stockNo);
            ps.setString(5, originStation);
            ps.setString(6, destinationStation);
            ps.setLong(7, nowMillis);
            ps.setLong(8, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询计划（不加锁）。
     */
    public Optional<DayPlan> findByKey(String scheduleKey) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM rail_day_plan"
                        + " WHERE schedule_key = ?",
                PLAN_MAPPER, scheduleKey).stream().findFirst();
    }

    /**
     * 按主键查询计划（不加锁），用于改签链遍历。
     */
    public Optional<DayPlan> findById(long planId) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM rail_day_plan"
                        + " WHERE id = ?",
                PLAN_MAPPER, planId).stream().findFirst();
    }

    /**
     * 按业务键查询计划并加行级写锁，须在事务内调用，用于串行化同一计划的更新/发布/取消。
     */
    public Optional<DayPlan> findByKeyForUpdate(String scheduleKey) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM rail_day_plan"
                        + " WHERE schedule_key = ? FOR UPDATE",
                PLAN_MAPPER, scheduleKey).stream().findFirst();
    }

    /**
     * 按业务键批量查询计划并对各行加写锁（整批发布使用）。
     */
    public List<DayPlan> findByKeysForUpdate(Collection<String> scheduleKeys) {
        if (scheduleKeys.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        scheduleKeys.forEach(k -> placeholders.add("?"));
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM rail_day_plan"
                        + " WHERE schedule_key IN (" + placeholders + ") FOR UPDATE",
                PLAN_MAPPER, scheduleKeys.toArray());
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
     * 查询某车底在指定运营日的全部已发布段（含待重排标记），按始发时刻升序，
     * 可排除给定计划 id。
     */
    public List<StockSegment> findPublishedSegments(String stockNo, LocalDate opDate,
                                                    Collection<Long> excludePlanIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT p.id AS plan_id, p.schedule_key, p.op_date, p.origin_station,"
                        + " p.destination_station, p.rearrange_pending, MIN(o.start_utc) AS seg_start,"
                        + " MAX(o.end_utc) AS seg_end"
                        + " FROM rail_day_plan p JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND p.stock_no = ? AND p.op_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(stockNo);
        args.add(Date.valueOf(opDate));
        if (excludePlanIds != null && !excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND p.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" GROUP BY p.id, p.schedule_key, p.op_date, p.origin_station, p.destination_station,"
                + " p.rearrange_pending ORDER BY seg_start, p.id");
        return jdbc.query(sql.toString(), SEGMENT_MAPPER, args.toArray());
    }

    /**
     * 查询某车底全部运营日的已发布交路段（按运营日、始发时刻升序），用于周转参数全量重校验。
     */
    public List<StockSegment> findPublishedSegments(String stockNo) {
        return jdbc.query(
                "SELECT p.id AS plan_id, p.schedule_key, p.op_date, p.origin_station,"
                        + " p.destination_station, p.rearrange_pending, MIN(o.start_utc) AS seg_start,"
                        + " MAX(o.end_utc) AS seg_end"
                        + " FROM rail_day_plan p JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND p.stock_no = ?"
                        + " GROUP BY p.id, p.schedule_key, p.op_date, p.origin_station, p.destination_station,"
                        + " p.rearrange_pending ORDER BY p.op_date, seg_start, p.id",
                SEGMENT_MAPPER, stockNo);
    }

    /**
     * 将计划标记/取消待重排。
     */
    public void updateRearrangePending(long planId, boolean pending, long nowMillis) {
        jdbc.update("UPDATE rail_day_plan SET rearrange_pending = ?, updated_at = ? WHERE id = ?",
                pending, nowMillis, planId);
    }

    private static final RowMapper<StockSegment> SEGMENT_MAPPER = (rs, n) -> new StockSegment(
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getString("origin_station"),
            rs.getString("destination_station"),
            Instant.ofEpochMilli(rs.getLong("seg_start")),
            Instant.ofEpochMilli(rs.getLong("seg_end")),
            rs.getBoolean("rearrange_pending"));
}
