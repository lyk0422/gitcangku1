package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PlatformOccupancy;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
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

    private static final String OCCUPANCY_COLUMNS =
            "id, plan_id, seq, train_no, section_id, start_utc, end_utc,"
                    + " platform_id, consist_length_meters, power_system, accessible_required,"
                    + " capability_version";

    private static final RowMapper<Occupancy> OCCUPANCY_MAPPER = (rs, n) -> new Occupancy(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getString("platform_id"),
            (Integer) rs.getObject("consist_length_meters"),
            rs.getString("power_system"),
            (Boolean) rs.getObject("accessible_required"),
            (Integer) rs.getObject("capability_version"));

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
     * 批量插入占用（含站台到发占用的编组三要素；capability_version 留空，发布成功时盖章）。
     */
    public void insertOccupancies(long planId, List<Occupancy> occupancies) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_occupancy (plan_id, seq, train_no, section_id, start_utc, end_utc,"
                        + " platform_id, consist_length_meters, power_system, accessible_required)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                occupancies, occupancies.size(),
                (ps, o) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, o.seq());
                    ps.setString(3, o.trainNo());
                    ps.setString(4, o.sectionId());
                    ps.setLong(5, o.startUtc().toEpochMilli());
                    ps.setLong(6, o.endUtc().toEpochMilli());
                    if (o.platformId() == null) {
                        ps.setNull(7, java.sql.Types.VARCHAR);
                        ps.setNull(8, java.sql.Types.INTEGER);
                        ps.setNull(9, java.sql.Types.VARCHAR);
                        ps.setNull(10, java.sql.Types.BOOLEAN);
                    } else {
                        ps.setString(7, o.platformId());
                        ps.setInt(8, o.consistLengthMeters());
                        ps.setString(9, o.powerSystem());
                        ps.setBoolean(10, o.accessibleRequired());
                    }
                });
    }

    /**
     * 查询计划占用，按计划内序号升序。
     */
    public List<Occupancy> findOccupancies(long planId) {
        return jdbc.query("SELECT " + OCCUPANCY_COLUMNS
                        + " FROM rail_plan_occupancy WHERE plan_id = ? ORDER BY seq",
                OCCUPANCY_MAPPER, planId);
    }

    /**
     * 发布/改签成功后为一条站台占用固化当时的站台能力版本（历史发布保留当时能力版本）。
     */
    public void stampCapabilityVersion(long occupancyId, int capabilityVersion) {
        jdbc.update("UPDATE rail_plan_occupancy SET capability_version = ? WHERE id = ?",
                capabilityVersion, occupancyId);
    }

    /**
     * 查询指定站台上与 [startUtc, endUtc) 重叠的当前生效（已发布、未取消）站台占用，
     * 排除给定计划集合（发布排除自身；改签排除新旧两计划）。左闭右开，相邻不重叠。
     */
    public List<PlatformOccupancy> findPlatformOccupancies(String platformId,
                                                           Collection<Long> excludePlanIds,
                                                           Instant startUtc, Instant endUtc) {
        StringBuilder sql = new StringBuilder(
                "SELECT o.platform_id, o.plan_id, p.schedule_key, o.train_no, o.section_id,"
                        + " o.start_utc, o.end_utc, o.consist_length_meters, o.power_system,"
                        + " o.accessible_required, o.capability_version"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND o.platform_id = ?"
                        + " AND o.start_utc < ? AND ? < o.end_utc");
        List<Object> args = new ArrayList<>();
        args.add(platformId);
        args.add(endUtc.toEpochMilli());
        args.add(startUtc.toEpochMilli());
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND o.plan_id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" ORDER BY o.start_utc");
        return jdbc.query(sql.toString(), (rs, n) -> new PlatformOccupancy(
                rs.getString("platform_id"),
                rs.getLong("plan_id"),
                rs.getString("schedule_key"),
                rs.getString("train_no"),
                rs.getString("section_id"),
                Instant.ofEpochMilli(rs.getLong("start_utc")),
                Instant.ofEpochMilli(rs.getLong("end_utc")),
                rs.getInt("consist_length_meters"),
                rs.getString("power_system"),
                rs.getBoolean("accessible_required"),
                rs.getInt("capability_version")), args.toArray());
    }

    /**
     * 查询指定站台全部当前生效（已发布）站台占用，按开始时刻升序，供站台管理方查看。
     */
    public List<PlatformOccupancy> findAllPlatformOccupancies(String platformId) {
        String sql = "SELECT o.platform_id, o.plan_id, p.schedule_key, o.train_no, o.section_id,"
                + " o.start_utc, o.end_utc, o.consist_length_meters, o.power_system,"
                + " o.accessible_required, o.capability_version"
                + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                + " WHERE p.status = 'PUBLISHED' AND o.platform_id = ? ORDER BY o.start_utc";
        return jdbc.query(sql, (rs, n) -> new PlatformOccupancy(
                rs.getString("platform_id"),
                rs.getLong("plan_id"),
                rs.getString("schedule_key"),
                rs.getString("train_no"),
                rs.getString("section_id"),
                Instant.ofEpochMilli(rs.getLong("start_utc")),
                Instant.ofEpochMilli(rs.getLong("end_utc")),
                rs.getInt("consist_length_meters"),
                rs.getString("power_system"),
                rs.getBoolean("accessible_required"),
                rs.getInt("capability_version")), platformId);
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
}
