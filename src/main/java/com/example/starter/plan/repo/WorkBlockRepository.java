package com.example.starter.plan.repo;

import com.example.starter.plan.model.AffectedPlanDisposition;
import com.example.starter.plan.model.AffectedPlanRecord;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.WorkBlock;
import com.example.starter.plan.model.WorkBlockStatus;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 区段维护封锁、受影响计划处理记录及相交占用快照的 JDBC 持久化。
 * 所有时刻以 UTC 毫秒存储，区间左闭右开。
 */
@Repository
public class WorkBlockRepository {

    private static final RowMapper<WorkBlock> BLOCK_MAPPER = (rs, n) -> new WorkBlock(
            rs.getLong("id"),
            rs.getString("block_key"),
            rs.getString("section_id"),
            rs.getObject("op_date", LocalDate.class),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            WorkBlockStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public WorkBlockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入封锁窗口，返回自增主键。
     */
    public long insertBlock(String blockKey, String sectionId, LocalDate opDate,
                            Instant startUtc, Instant endUtc, WorkBlockStatus status, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_work_block (block_key, section_id, op_date, start_utc, end_utc,"
                            + " status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, blockKey);
            ps.setString(2, sectionId);
            ps.setDate(3, Date.valueOf(opDate));
            ps.setLong(4, startUtc.toEpochMilli());
            ps.setLong(5, endUtc.toEpochMilli());
            ps.setString(6, status.name());
            ps.setLong(7, nowMillis);
            ps.setLong(8, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询封锁（不加锁）。
     */
    public Optional<WorkBlock> findByKey(String blockKey) {
        return jdbc.query("SELECT id, block_key, section_id, op_date, start_utc, end_utc, status"
                        + " FROM rail_work_block WHERE block_key = ?",
                BLOCK_MAPPER, blockKey).stream().findFirst();
    }

    /**
     * 按主键查询封锁（不加锁）。
     */
    public Optional<WorkBlock> findById(long blockId) {
        return jdbc.query("SELECT id, block_key, section_id, op_date, start_utc, end_utc, status"
                        + " FROM rail_work_block WHERE id = ?",
                BLOCK_MAPPER, blockId).stream().findFirst();
    }

    /**
     * 按业务键查询封锁并加行级写锁，须在事务内调用。
     */
    public Optional<WorkBlock> findByKeyForUpdate(String blockKey) {
        return jdbc.query("SELECT id, block_key, section_id, op_date, start_utc, end_utc, status"
                        + " FROM rail_work_block WHERE block_key = ? FOR UPDATE",
                BLOCK_MAPPER, blockKey).stream().findFirst();
    }

    /**
     * 查询指定运营日、指定区段集合上全部已生效封锁窗口（调用方在全局锁事务内使用）。
     */
    public List<WorkBlock> findEffectiveBlocks(LocalDate opDate, Collection<String> sectionIds) {        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        args.add(WorkBlockStatus.EFFECTIVE.name());
        sectionIds.forEach(args::add);
        return jdbc.query("SELECT id, block_key, section_id, op_date, start_utc, end_utc, status"
                        + " FROM rail_work_block WHERE op_date = ? AND status = ?"
                        + " AND section_id IN (" + placeholders + ")"
                        + " ORDER BY section_id, start_utc",
                BLOCK_MAPPER, args.toArray());
    }

    /**
     * 查询指定运营日全部区段的已生效封锁窗口，按区段、开始时刻升序。
     */
    public List<WorkBlock> findEffectiveBlocksByDate(LocalDate opDate) {
        return jdbc.query("SELECT id, block_key, section_id, op_date, start_utc, end_utc, status"
                        + " FROM rail_work_block WHERE op_date = ? AND status = ?"
                        + " ORDER BY section_id, start_utc",
                BLOCK_MAPPER, Date.valueOf(opDate), WorkBlockStatus.EFFECTIVE.name());
    }

    /**
     * 追加一条受影响计划处理记录，返回自增主键。
     */
    public long insertAffectedPlan(long blockId, long planId, String scheduleKey,
                                   AffectedPlanDisposition disposition, Long successorPlanId,
                                   long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_work_block_affected_plan"
                            + " (block_id, plan_id, schedule_key, disposition, successor_plan_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, blockId);
            ps.setLong(2, planId);
            ps.setString(3, scheduleKey);
            ps.setString(4, disposition.name());
            if (successorPlanId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, successorPlanId);
            }
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量追加受影响计划与封锁相交的原占用快照。
     */
    public void insertAffectedSlots(long affectedPlanId, List<Occupancy> slots) {
        if (slots.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_work_block_affected_slot"
                        + " (affected_plan_id, train_no, section_id, start_utc, end_utc)"
                        + " VALUES (?, ?, ?, ?, ?)",
                slots, slots.size(),
                (ps, o) -> {
                    ps.setLong(1, affectedPlanId);
                    ps.setString(2, o.trainNo());
                    ps.setString(3, o.sectionId());
                    ps.setLong(4, o.startUtc().toEpochMilli());
                    ps.setLong(5, o.endUtc().toEpochMilli());
                });
    }

    /**
     * 查询某封锁下全部受影响计划处理记录（不含快照时隙），按记录 id 升序。
     */
    public List<AffectedPlanRecord> findAffectedPlans(long blockId) {
        return jdbc.query("SELECT id, block_id, plan_id, schedule_key, disposition, successor_plan_id"
                        + " FROM rail_work_block_affected_plan WHERE block_id = ? ORDER BY id",
                (rs, n) -> new AffectedPlanRecord(
                        rs.getLong("id"),
                        rs.getLong("block_id"),
                        rs.getLong("plan_id"),
                        rs.getString("schedule_key"),
                        AffectedPlanDisposition.valueOf(rs.getString("disposition")),
                        (Long) rs.getObject("successor_plan_id"),
                        List.of()),
                blockId);
    }

    /**
     * 为多条受影响计划记录装配相交占用快照，按受影响记录 id 分组、组内按开始时刻升序。
     */
    public Map<Long, List<Occupancy>> findSlotsByAffectedPlanIds(Collection<Long> affectedPlanIds) {
        if (affectedPlanIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        affectedPlanIds.forEach(id -> placeholders.add("?"));
        List<Occupancy> slots = jdbc.query(
                "SELECT id, affected_plan_id, train_no, section_id, start_utc, end_utc"
                        + " FROM rail_work_block_affected_slot"
                        + " WHERE affected_plan_id IN (" + placeholders + ")"
                        + " ORDER BY affected_plan_id, start_utc",
                (rs, n) -> new Occupancy(
                        rs.getLong("id"),
                        rs.getLong("affected_plan_id"),
                        0,
                        rs.getString("train_no"),
                        rs.getString("section_id"),
                        Instant.ofEpochMilli(rs.getLong("start_utc")),
                        Instant.ofEpochMilli(rs.getLong("end_utc"))),
                affectedPlanIds.toArray());
        return slots.stream().collect(Collectors.groupingBy(Occupancy::planId));
    }
}
