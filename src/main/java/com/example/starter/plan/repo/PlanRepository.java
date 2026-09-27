package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishSnapshot;
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

    private static final RowMapper<HazardCar> HAZARD_CAR_MAPPER = (rs, n) -> new HazardCar(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("train_no"),
            rs.getInt("position"),
            rs.getString("hazard_class"));

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
     * 整体替换计划的危险品编组：先删后插，与占用替换保持同一提交顺序。
     */
    public void replaceHazardCars(long planId, List<HazardCar> cars) {
        jdbc.update("DELETE FROM rail_plan_hazard_car WHERE plan_id = ?", planId);
        insertHazardCars(planId, cars);
    }

    /**
     * 批量插入危险品车厢。
     */
    public void insertHazardCars(long planId, List<HazardCar> cars) {
        if (cars.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_hazard_car (plan_id, train_no, position, hazard_class)"
                        + " VALUES (?, ?, ?, ?)",
                cars, cars.size(),
                (ps, c) -> {
                    ps.setLong(1, planId);
                    ps.setString(2, c.trainNo());
                    ps.setInt(3, c.position());
                    ps.setString(4, c.hazardClass());
                });
    }

    /**
     * 查询计划危险品编组，按列车、位置升序稳定排列。
     */
    public List<HazardCar> findHazardCars(long planId) {
        return jdbc.query("SELECT id, plan_id, train_no, position, hazard_class"
                + " FROM rail_plan_hazard_car WHERE plan_id = ?"
                + " ORDER BY train_no, position", HAZARD_CAR_MAPPER, planId);
    }

    /**
     * 追加发布快照主记录，返回自增主键。配置版本为空（无生效配置）时各版本字段为 null。
     */
    public long insertPublishSnapshot(long planId, Long configVersionId, Integer configVersionNo,
                                      String configFingerprint, String conclusion, String operator,
                                      long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO plan_publish_snapshot"
                            + " (plan_id, config_version_id, config_version_no, config_fingerprint,"
                            + " conclusion, operator, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            if (configVersionId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, configVersionId);
            }
            if (configVersionNo == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, configVersionNo);
            }
            ps.setString(4, configFingerprint);
            ps.setString(5, conclusion);
            ps.setString(6, operator);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量插入快照危险品车厢副本。
     */
    public void insertSnapshotCars(long snapshotId, List<HazardCar> cars) {
        if (cars.isEmpty()) {
            return;
        }
        List<PublishSnapshot.SnapshotHazardCar> snapshotCars = cars.stream()
                .map(c -> new PublishSnapshot.SnapshotHazardCar(c.trainNo(), c.position(),
                        c.hazardClass()))
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO plan_publish_snapshot_car (snapshot_id, train_no, position, hazard_class)"
                        + " VALUES (?, ?, ?, ?)",
                snapshotCars, snapshotCars.size(),
                (ps, c) -> {
                    ps.setLong(1, snapshotId);
                    ps.setString(2, c.trainNo());
                    ps.setInt(3, c.position());
                    ps.setString(4, c.hazardClass());
                });
    }

    /**
     * 批量插入快照限制区段开放清单副本。
     */
    public void insertSnapshotSections(long snapshotId,
                                       List<PublishSnapshot.SnapshotOpenSection> sections) {
        if (sections.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO plan_publish_snapshot_section (snapshot_id, section_id, hazard_class)"
                        + " VALUES (?, ?, ?)",
                sections, sections.size(),
                (ps, s) -> {
                    ps.setLong(1, snapshotId);
                    ps.setString(2, s.sectionId());
                    ps.setString(3, s.hazardClass());
                });
    }

    /**
     * 按计划查询发布快照（每计划最多一行，追加后不可变）。
     */
    public Optional<PublishSnapshot> findSnapshotByPlan(long planId) {
        List<PublishSnapshot> snapshots = jdbc.query(
                "SELECT id, plan_id, config_version_id, config_version_no, config_fingerprint,"
                        + " conclusion, operator, created_at FROM plan_publish_snapshot"
                        + " WHERE plan_id = ?",
                (rs, n) -> new PublishSnapshot(
                        rs.getLong("id"),
                        rs.getLong("plan_id"),
                        rs.getObject("config_version_id", Long.class),
                        rs.getObject("config_version_no", Integer.class),
                        rs.getString("config_fingerprint"),
                        rs.getString("conclusion"),
                        rs.getString("operator"),
                        rs.getLong("created_at"),
                        List.of(),
                        List.of()),
                planId);
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        PublishSnapshot snapshot = snapshots.get(0);
        return Optional.of(new PublishSnapshot(snapshot.id(), snapshot.planId(),
                snapshot.configVersionId(), snapshot.configVersionNo(), snapshot.configFingerprint(),
                snapshot.conclusion(), snapshot.operator(), snapshot.createdAt(),
                findSnapshotCars(snapshot.id()), findSnapshotSections(snapshot.id())));
    }

    /**
     * 查询发布历史，按创建时刻升序、快照 id 升序稳定排列；查询不修改状态。
     */
    public List<PublishSnapshot> findSnapshots(LocalDate fromOpDate, LocalDate toOpDate,
                                               Integer limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.plan_id, s.config_version_id, s.config_version_no,"
                        + " s.config_fingerprint, s.conclusion, s.operator, s.created_at"
                        + " FROM plan_publish_snapshot s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (fromOpDate != null) {
            sql.append(" AND p.op_date >= ?");
            args.add(Date.valueOf(fromOpDate));
        }
        if (toOpDate != null) {
            sql.append(" AND p.op_date <= ?");
            args.add(Date.valueOf(toOpDate));
        }
        sql.append(" ORDER BY s.created_at, s.id");
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        List<PublishSnapshot> result = jdbc.query(sql.toString(), (rs, n) ->
                new PublishSnapshot(
                        rs.getLong("id"),
                        rs.getLong("plan_id"),
                        rs.getObject("config_version_id", Long.class),
                        rs.getObject("config_version_no", Integer.class),
                        rs.getString("config_fingerprint"),
                        rs.getString("conclusion"),
                        rs.getString("operator"),
                        rs.getLong("created_at"),
                        List.of(),
                        List.of()), args.toArray());
        for (int i = 0; i < result.size(); i++) {
            PublishSnapshot s = result.get(i);
            result.set(i, new PublishSnapshot(s.id(), s.planId(), s.configVersionId(),
                    s.configVersionNo(), s.configFingerprint(), s.conclusion(), s.operator(),
                    s.createdAt(), findSnapshotCars(s.id()), findSnapshotSections(s.id())));
        }
        return result;
    }

    private List<PublishSnapshot.SnapshotHazardCar> findSnapshotCars(long snapshotId) {
        return jdbc.query("SELECT train_no, position, hazard_class FROM plan_publish_snapshot_car"
                        + " WHERE snapshot_id = ? ORDER BY train_no, position",
                (rs, n) -> new PublishSnapshot.SnapshotHazardCar(
                        rs.getString("train_no"), rs.getInt("position"),
                        rs.getString("hazard_class")),
                snapshotId);
    }

    private List<PublishSnapshot.SnapshotOpenSection> findSnapshotSections(long snapshotId) {
        return jdbc.query("SELECT section_id, hazard_class FROM plan_publish_snapshot_section"
                        + " WHERE snapshot_id = ? ORDER BY section_id, hazard_class",
                (rs, n) -> new PublishSnapshot.SnapshotOpenSection(
                        rs.getString("section_id"), rs.getString("hazard_class")),
                snapshotId);
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
