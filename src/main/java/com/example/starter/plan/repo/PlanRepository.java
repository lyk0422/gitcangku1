package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PlatformBooking;
import com.example.starter.plan.model.PlatformCapability;
import com.example.starter.plan.model.PowerSupplyType;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 日计划、占用、站台能力版本与站台占用登记的 JDBC 持久化。
 * 所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class PlanRepository {

    private static final String PLAN_COLUMNS =
            "id, schedule_key, op_date, version, status, consist_length_m, power_supply_type, accessible_required";

    private static final RowMapper<DayPlan> PLAN_MAPPER = (rs, n) -> {
        Integer length = rs.getObject("consist_length_m", Integer.class);
        String power = rs.getString("power_supply_type");
        Boolean accessible = rs.getObject("accessible_required", Boolean.class);
        return new DayPlan(
                rs.getLong("id"),
                rs.getString("schedule_key"),
                rs.getObject("op_date", LocalDate.class),
                rs.getInt("version"),
                PlanStatus.valueOf(rs.getString("status")),
                length,
                power == null ? null : PowerSupplyType.valueOf(power),
                accessible);
    };

    private static final RowMapper<Occupancy> OCCUPANCY_MAPPER = (rs, n) -> new Occupancy(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("section_id"),
            rs.getString("platform_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private static final RowMapper<RescheduleLink> LINK_MAPPER = (rs, n) -> new RescheduleLink(
            rs.getLong("id"),
            rs.getLong("predecessor_plan_id"),
            rs.getLong("successor_plan_id"),
            rs.getLong("created_at"));

    private static final RowMapper<PlatformCapability> CAPABILITY_MAPPER = (rs, n) ->
            new PlatformCapability(
                    rs.getString("platform_id"),
                    rs.getInt("version"),
                    rs.getInt("max_length_m"),
                    parsePowerTypes(rs.getString("supported_power_types")),
                    rs.getBoolean("accessible"));

    private static final RowMapper<PlatformBooking> BOOKING_MAPPER = (rs, n) -> new PlatformBooking(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("train_no"),
            rs.getString("platform_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getInt("capability_version"),
            PlanStatus.valueOf(rs.getString("plan_status")),
            rs.getString("schedule_key"));

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新计划，返回自增主键。编组三属性为 null 时表示不做站台适配。
     */
    public long insertPlan(String scheduleKey, LocalDate opDate, PlanStatus status,
                           Integer consistLengthM, PowerSupplyType powerSupplyType,
                           Boolean accessibleRequired, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_day_plan (schedule_key, op_date, version, status,"
                            + " consist_length_m, power_supply_type, accessible_required,"
                            + " created_at, updated_at)"
                            + " VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, scheduleKey);
            ps.setDate(2, Date.valueOf(opDate));
            ps.setString(3, status.name());
            if (consistLengthM == null) {
                ps.setObject(4, null);
                ps.setObject(5, null);
                ps.setObject(6, null);
            } else {
                ps.setInt(4, consistLengthM);
                ps.setString(5, powerSupplyType.name());
                ps.setBoolean(6, accessibleRequired);
            }
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
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM rail_day_plan WHERE schedule_key = ?",
                PLAN_MAPPER, scheduleKey).stream().findFirst();
    }

    /**
     * 按主键查询计划（不加锁），用于改签链遍历。
     */
    public Optional<DayPlan> findById(long planId) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM rail_day_plan WHERE id = ?",
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
                "INSERT INTO rail_plan_occupancy (plan_id, seq, train_no, section_id,"
                        + " platform_id, start_utc, end_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                occupancies, occupancies.size(),
                (ps, o) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, o.seq());
                    ps.setString(3, o.trainNo());
                    ps.setString(4, o.sectionId());
                    ps.setString(5, o.platformId());
                    ps.setLong(6, o.startUtc().toEpochMilli());
                    ps.setLong(7, o.endUtc().toEpochMilli());
                });
    }

    /**
     * 查询计划占用，按计划内序号升序。
     */
    public List<Occupancy> findOccupancies(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, train_no, section_id, platform_id,"
                        + " start_utc, end_utc FROM rail_plan_occupancy WHERE plan_id = ? ORDER BY seq",
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
     * 获取发布全局互斥锁（单行 FOR UPDATE），串行化所有发布、改签与能力登记事务。
     */
    public void acquirePublishLock() {
        jdbc.queryForObject("SELECT id FROM publish_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    // ---------- 站台能力版本 ----------

    /**
     * 追加站台能力新版本，返回新版本号（同站台最大版本加一）。须在持锁事务内调用。
     */
    public int insertCapabilityVersion(String platformId, int maxLengthM,
                                       Set<PowerSupplyType> supportedPowerTypes,
                                       boolean accessible, long nowMillis) {
        Integer current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM rail_platform_capability"
                        + " WHERE platform_id = ?", Integer.class, platformId);
        int nextVersion = (current == null ? 0 : current) + 1;
        jdbc.update("INSERT INTO rail_platform_capability"
                        + " (platform_id, version, max_length_m, supported_power_types, accessible, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                platformId, nextVersion, maxLengthM, formatPowerTypes(supportedPowerTypes),
                accessible, nowMillis);
        return nextVersion;
    }

    /**
     * 查询站台当前有效能力（最大版本），不存在返回空。
     */
    public Optional<PlatformCapability> findLatestCapability(String platformId) {
        return jdbc.query("SELECT platform_id, version, max_length_m, supported_power_types, accessible"
                        + " FROM rail_platform_capability WHERE platform_id = ?"
                        + " ORDER BY version DESC FETCH FIRST 1 ROWS ONLY",
                CAPABILITY_MAPPER, platformId).stream().findFirst();
    }

    /**
     * 查询所有站台的当前有效能力（每站台最大版本）。
     */
    public List<PlatformCapability> findAllLatestCapabilities() {
        return jdbc.query("SELECT c.platform_id, c.version, c.max_length_m, c.supported_power_types,"
                + " c.accessible FROM rail_platform_capability c"
                + " JOIN (SELECT platform_id, MAX(version) AS max_version"
                + " FROM rail_platform_capability GROUP BY platform_id) m"
                + " ON m.platform_id = c.platform_id AND m.max_version = c.version"
                + " ORDER BY c.platform_id", CAPABILITY_MAPPER);
    }

    /**
     * 按站台与版本精确查询能力（历史快照），不存在返回空。
     */
    public Optional<PlatformCapability> findCapability(String platformId, int version) {
        return jdbc.query("SELECT platform_id, version, max_length_m, supported_power_types, accessible"
                        + " FROM rail_platform_capability WHERE platform_id = ? AND version = ?",
                CAPABILITY_MAPPER, platformId, version).stream().findFirst();
    }

    // ---------- 站台到发占用登记 ----------

    /**
     * 批量登记站台到发占用，发布/改签事务内一次性写入。
     */
    public void insertPlatformBookings(long planId, List<PlatformBooking> bookings) {
        jdbc.batchUpdate(
                "INSERT INTO rail_platform_booking (plan_id, seq, train_no, platform_id,"
                        + " start_utc, end_utc, capability_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bookings, bookings.size(),
                (ps, b) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, b.seq());
                    ps.setString(3, b.trainNo());
                    ps.setString(4, b.platformId());
                    ps.setLong(5, b.startUtc().toEpochMilli());
                    ps.setLong(6, b.endUtc().toEpochMilli());
                    ps.setInt(7, b.capabilityVersion());
                    ps.setLong(8, System.currentTimeMillis());
                });
    }

    /**
     * 查询计划的全部站台登记（含历史，按序号升序）。
     */
    public List<PlatformBooking> findBookingsByPlan(long planId) {
        return jdbc.query(
                "SELECT b.id, b.plan_id, b.seq, b.train_no, b.platform_id, b.start_utc, b.end_utc,"
                        + " b.capability_version, p.status AS plan_status, p.schedule_key"
                        + " FROM rail_platform_booking b JOIN rail_day_plan p ON p.id = b.plan_id"
                        + " WHERE b.plan_id = ? ORDER BY b.seq",
                BOOKING_MAPPER, planId);
    }

    /**
     * 查询一组站台上其他已发布计划的生效占用（排除给定计划集合），区间重叠由上层判定。
     */
    public List<PlatformBooking> findActiveBookings(Collection<String> platformIds,
                                                    Collection<Long> excludePlanIds) {
        if (platformIds.isEmpty()) {
            return List.of();
        }
        StringJoiner platformPlaceholders = new StringJoiner(", ");
        platformIds.forEach(s -> platformPlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT b.id, b.plan_id, b.seq, b.train_no, b.platform_id, b.start_utc, b.end_utc,"
                        + " b.capability_version, p.status AS plan_status, p.schedule_key"
                        + " FROM rail_platform_booking b JOIN rail_day_plan p ON p.id = b.plan_id"
                        + " WHERE p.status = 'PUBLISHED'");
        List<Object> args = new ArrayList<>();
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND b.plan_id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" AND b.platform_id IN (").append(platformPlaceholders)
                .append(") ORDER BY b.platform_id, b.start_utc");
        args.addAll(platformIds);
        return jdbc.query(sql.toString(), BOOKING_MAPPER, args.toArray());
    }

    /**
     * 站台管理方查询某站台的占用登记，仅返回当前生效（来源计划 PUBLISHED）记录，按开始时刻升序。
     */
    public List<PlatformBooking> findActiveBookingsByPlatform(String platformId) {
        return jdbc.query(
                "SELECT b.id, b.plan_id, b.seq, b.train_no, b.platform_id, b.start_utc, b.end_utc,"
                        + " b.capability_version, p.status AS plan_status, p.schedule_key"
                        + " FROM rail_platform_booking b JOIN rail_day_plan p ON p.id = b.plan_id"
                        + " WHERE b.platform_id = ? AND p.status = 'PUBLISHED'"
                        + " ORDER BY b.start_utc",
                BOOKING_MAPPER, platformId);
    }

    /** 逗号分隔供电制式串解析为集合，空串返回空集合。 */
    public static Set<PowerSupplyType> parsePowerTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(PowerSupplyType::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String formatPowerTypes(Set<PowerSupplyType> types) {
        return types.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
