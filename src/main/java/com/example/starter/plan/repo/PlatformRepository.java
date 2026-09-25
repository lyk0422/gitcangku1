package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Platform;
import com.example.starter.plan.model.PlatformOccupancy;
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
 * 站台、站台占用与风险快照的 JDBC 持久化。
 */
@Repository
public class PlatformRepository {

    private static final RowMapper<Platform> PLATFORM_MAPPER = (rs, n) -> new Platform(
            rs.getLong("id"),
            rs.getString("platform_code"),
            rs.getInt("effective_length"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public PlatformRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记新站台（版本 1），返回自增主键；代码重复抛 DuplicateKeyException。
     */
    public long insertPlatform(String platformCode, int effectiveLength, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_platform (platform_code, effective_length, version,"
                            + " created_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, platformCode);
            ps.setInt(2, effectiveLength);
            ps.setLong(3, nowMillis);
            ps.setLong(4, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按代码查询站台（不加锁）。
     */
    public Optional<Platform> findByCode(String platformCode) {
        return jdbc.query("SELECT id, platform_code, effective_length, version FROM rail_platform"
                        + " WHERE platform_code = ?",
                PLATFORM_MAPPER, platformCode).stream().findFirst();
    }

    /**
     * 按代码查询站台并加行级写锁，须在事务内调用。
     */
    public Optional<Platform> findByCodeForUpdate(String platformCode) {
        return jdbc.query("SELECT id, platform_code, effective_length, version FROM rail_platform"
                        + " WHERE platform_code = ? FOR UPDATE",
                PLATFORM_MAPPER, platformCode).stream().findFirst();
    }

    /**
     * 调整站台有效长度并版本加一。
     */
    public void updateLength(long platformId, int effectiveLength, int version, long nowMillis) {
        jdbc.update("UPDATE rail_platform SET effective_length = ?, version = ?, updated_at = ?"
                        + " WHERE id = ?",
                effectiveLength, version, nowMillis, platformId);
    }

    /**
     * 查询指定运营日（含）以后、停靠指定站台集合、已发布且尚未带风险标记的计划，
     * 加行级写锁以在同一事务内固化风险；须在事务内调用。
     */
    public List<DayPlan> findFuturePublishedPlansForUpdate(LocalDate fromDate,
                                                            Collection<String> platformCodes) {
        if (platformCodes.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        platformCodes.forEach(c -> placeholders.add("?"));
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Date.valueOf(fromDate));
        args.addAll(platformCodes);
        return jdbc.query(
                "SELECT p.id, p.schedule_key, p.op_date, p.version, p.status, p.platform_risk"
                        + " FROM rail_day_plan p JOIN rail_plan_consist c ON c.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND p.platform_risk = 0"
                        + " AND p.op_date >= ? AND c.platform_code IN (" + placeholders + ")"
                        + " FOR UPDATE",
                PlanRepository.PLAN_MAPPER, args.toArray());
    }

    /**
     * 查询计划编组停靠站台在指定时段内其他已发布计划的占用（左闭右开，相邻合法）。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；批量/改签为涉及的全部计划）
     */
    public List<PlatformOccupancy> findPlatformOccupancies(LocalDate opDate,
                                                            Collection<String> platformCodes,
                                                            Collection<Long> excludePlanIds,
                                                            Instant dayStart, Instant dayEnd) {
        if (platformCodes.isEmpty()) {
            return List.of();
        }
        StringJoiner codePlaceholders = new StringJoiner(", ");
        platformCodes.forEach(c -> codePlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT p.schedule_key, c.platform_code, o.start_utc AS start_utc,"
                        + " o.end_utc AS end_utc"
                        + " FROM rail_day_plan p"
                        + " JOIN rail_plan_consist c ON c.plan_id = p.id"
                        + " JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ?"
                        + " AND o.start_utc >= ? AND o.end_utc <= ?");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Date.valueOf(opDate));
        args.add(dayStart.toEpochMilli());
        args.add(dayEnd.toEpochMilli());
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND p.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" AND c.platform_code IN (").append(codePlaceholders)
                .append(") ORDER BY o.start_utc");
        args.addAll(platformCodes);
        return jdbc.query(sql.toString(), (rs, n) -> new PlatformOccupancy(
                rs.getString("schedule_key"),
                rs.getString("platform_code"),
                Instant.ofEpochMilli(rs.getLong("start_utc")),
                Instant.ofEpochMilli(rs.getLong("end_utc"))), args.toArray());
    }

    /**
     * 查询某站台上指定运营日当前已发布计划的占用时段，供占用查询接口使用。
     */
    public List<PlatformOccupancy> findOccupancyByPlatform(LocalDate opDate, String platformCode,
                                                            Instant dayStart, Instant dayEnd) {
        return jdbc.query(
                "SELECT p.schedule_key, c.platform_code, o.start_utc AS start_utc,"
                        + " o.end_utc AS end_utc"
                        + " FROM rail_day_plan p"
                        + " JOIN rail_plan_consist c ON c.plan_id = p.id"
                        + " JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ?"
                        + " AND o.start_utc >= ? AND o.end_utc <= ? AND c.platform_code = ?"
                        + " ORDER BY o.start_utc",
                (rs, n) -> new PlatformOccupancy(
                        rs.getString("schedule_key"),
                        rs.getString("platform_code"),
                        Instant.ofEpochMilli(rs.getLong("start_utc")),
                        Instant.ofEpochMilli(rs.getLong("end_utc"))),
                java.sql.Date.valueOf(opDate), dayStart.toEpochMilli(), dayEnd.toEpochMilli(),
                platformCode);
    }
}
