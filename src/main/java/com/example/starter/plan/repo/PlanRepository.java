package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.dao.DuplicateKeyException;
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
            PlanStatus.valueOf(rs.getString("status")),
            rs.getObject("plan_level", Integer.class));

    private static final RowMapper<Occupancy> OCCUPANCY_MAPPER = (rs, n) -> new Occupancy(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

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
        return jdbc.query("SELECT id, schedule_key, op_date, version, status, plan_level FROM rail_day_plan"
                        + " WHERE schedule_key = ?",
                PLAN_MAPPER, scheduleKey).stream().findFirst();
    }

    /**
     * 按业务键查询计划并加行级写锁，须在事务内调用，用于串行化同一计划的更新/发布/取消。
     */
    public Optional<DayPlan> findByKeyForUpdate(String scheduleKey) {
        return jdbc.query("SELECT id, schedule_key, op_date, version, status, plan_level FROM rail_day_plan"
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
     * 发布时更新状态并固化继承的计划等级。
     */
    public void updateStatusAndLevel(long planId, PlanStatus status, int planLevel, long nowMillis) {
        jdbc.update("UPDATE rail_day_plan SET status = ?, plan_level = ?, updated_at = ? WHERE id = ?",
                status.name(), planLevel, nowMillis, planId);
    }

    /**
     * 原子降级：仅当计划仍处于 PUBLISHED 时转为 PREEMPTED 终态。
     * 返回受影响行数，0 表示目标已被并发事务改变，由上层裁决为 409。
     */
    public int markPreemptedIfPublished(long planId, long nowMillis) {
        return jdbc.update("UPDATE rail_day_plan SET status = 'PREEMPTED', updated_at = ?"
                        + " WHERE id = ? AND status = 'PUBLISHED'",
                nowMillis, planId);
    }

    /**
     * 查询指定运营日、指定区段集合上其他已发布计划的生效时隙（排除给定计划）。
     */
    public List<PublishedSlot> findPublishedSlots(LocalDate opDate, Collection<String> sectionIds,
                                                  long excludePlanId) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        String sql = "SELECT p.id AS plan_id, p.schedule_key, o.train_no, o.section_id,"
                + " o.start_utc, o.end_utc, p.plan_level"
                + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                + " WHERE p.status = 'PUBLISHED' AND p.op_date = ? AND p.id <> ?"
                + " AND o.section_id IN (" + placeholders + ") ORDER BY o.section_id, o.start_utc";
        Object[] args = new Object[sectionIds.size() + 2];
        args[0] = Date.valueOf(opDate);
        args[1] = excludePlanId;
        int i = 2;
        for (String sectionId : sectionIds) {
            args[i++] = sectionId;
        }
        return jdbc.query(sql, (rs, n) -> new PublishedSlot(
                rs.getLong("plan_id"),
                rs.getString("schedule_key"),
                rs.getString("train_no"),
                rs.getString("section_id"),
                Instant.ofEpochMilli(rs.getLong("start_utc")),
                Instant.ofEpochMilli(rs.getLong("end_utc")),
                rs.getObject("plan_level", Integer.class)), args);
    }

    /**
     * 登记或更新区段走廊等级（1～5）。先更新，未命中再插入，并发插入冲突时重试更新。
     */
    public void upsertSection(String sectionId, int priority, long nowMillis) {
        int updated = jdbc.update("UPDATE rail_section SET priority = ?, updated_at = ? WHERE section_id = ?",
                priority, nowMillis, sectionId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO rail_section (section_id, priority, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?)", sectionId, priority, nowMillis, nowMillis);
        } catch (DuplicateKeyException e) {
            jdbc.update("UPDATE rail_section SET priority = ?, updated_at = ? WHERE section_id = ?",
                    priority, nowMillis, sectionId);
        }
    }

    /**
     * 按区段 ID 查询登记等级，未登记返回空（上层按 1 级处理）。
     */
    public Optional<Integer> findSectionPriority(String sectionId) {
        return jdbc.query("SELECT priority FROM rail_section WHERE section_id = ?",
                (rs, n) -> rs.getInt("priority"), sectionId).stream().findFirst();
    }

    /**
     * 批量查询区段登记等级，返回区段 ID → 等级；未登记的区段不在结果中。
     */
    public Map<String, Integer> findSectionPriorities(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        Map<String, Integer> levels = new HashMap<>();
        jdbc.query("SELECT section_id, priority FROM rail_section WHERE section_id IN ("
                        + placeholders + ")",
                rs -> {
                    levels.put(rs.getString("section_id"), rs.getInt("priority"));
                }, sectionIds.toArray());
        return levels;
    }

    /**
     * 获取发布全局互斥锁（单行 FOR UPDATE），串行化所有发布事务。
     */
    public void acquirePublishLock() {
        jdbc.queryForObject("SELECT id FROM publish_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }
}
