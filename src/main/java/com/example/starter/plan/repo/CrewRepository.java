package com.example.starter.plan.repo;

import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.CrewRiskRecord;
import com.example.starter.plan.model.CrewRole;
import com.example.starter.plan.model.PlanCrew;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 乘务资质、计划乘务指派与风险记录的 JDBC 持久化。所有时刻以 UTC 毫秒存储。
 * 覆盖区段集合以规范化（排序去重）逗号分隔串存储，换序视为同参。
 */
@Repository
public class CrewRepository {

    private static final RowMapper<CrewQualification> QUAL_MAPPER = (rs, n) -> new CrewQualification(
            rs.getLong("id"),
            rs.getString("qual_code"),
            rs.getString("crew_id"),
            parseSections(rs.getString("sections")),
            Instant.ofEpochMilli(rs.getLong("expires_utc")),
            rs.getInt("version"),
            rs.getInt("terminated") != 0);

    private static final RowMapper<PlanCrew> PLAN_CREW_MAPPER = (rs, n) -> new PlanCrew(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            CrewRole.valueOf(rs.getString("role")),
            rs.getString("crew_id"),
            rs.getString("qual_code"));

    private static final RowMapper<CrewRiskRecord> RISK_MAPPER = (rs, n) -> new CrewRiskRecord(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            CrewRole.valueOf(rs.getString("role")),
            rs.getString("crew_id"),
            rs.getString("qual_code"),
            rs.getString("reason"),
            rs.getLong("recorded_at"));

    private final JdbcTemplate jdbc;

    public CrewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static List<String> parseSections(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(stored.split(","));
    }

    private static String joinSections(List<String> sections) {
        return String.join(",", sections);
    }

    /**
     * 插入新资质，返回自增主键。sections 须已规范化（排序去重）。
     */
    public long insertQualification(String qualCode, String crewId, List<String> sections,
                                    Instant expiresUtc, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_crew_qualification"
                            + " (qual_code, crew_id, sections, expires_utc, version, terminated, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 1, 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, qualCode);
            ps.setString(2, crewId);
            ps.setString(3, joinSections(sections));
            ps.setLong(4, expiresUtc.toEpochMilli());
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按资质代码查询（不加锁）。
     */
    public Optional<CrewQualification> findQualByCode(String qualCode) {
        return jdbc.query("SELECT id, qual_code, crew_id, sections, expires_utc, version, terminated"
                        + " FROM rail_crew_qualification WHERE qual_code = ?",
                QUAL_MAPPER, qualCode).stream().findFirst();
    }

    /**
     * 按资质代码查询并加行级写锁，须在事务内调用，用于串行化资质修改与提前终止。
     */
    public Optional<CrewQualification> findQualByCodeForUpdate(String qualCode) {
        return jdbc.query("SELECT id, qual_code, crew_id, sections, expires_utc, version, terminated"
                        + " FROM rail_crew_qualification WHERE qual_code = ? FOR UPDATE",
                QUAL_MAPPER, qualCode).stream().findFirst();
    }

    /**
     * 查询乘务员的全部资质（含已终止），按资质代码排序保证稳定输出。
     */
    public List<CrewQualification> findQualsByCrewId(String crewId) {
        return jdbc.query("SELECT id, qual_code, crew_id, sections, expires_utc, version, terminated"
                        + " FROM rail_crew_qualification WHERE crew_id = ? ORDER BY qual_code",
                QUAL_MAPPER, crewId);
    }

    /**
     * 修改资质覆盖区段与到期时刻，版本加一。
     */
    public void updateQualification(long id, List<String> sections, Instant expiresUtc,
                                    int newVersion, long nowMillis) {
        jdbc.update("UPDATE rail_crew_qualification"
                        + " SET sections = ?, expires_utc = ?, version = ?, updated_at = ? WHERE id = ?",
                joinSections(sections), expiresUtc.toEpochMilli(), newVersion, nowMillis, id);
    }

    /**
     * 提前终止资质（不可逆），版本加一。
     */
    public void terminateQualification(long id, int newVersion, long nowMillis) {
        jdbc.update("UPDATE rail_crew_qualification"
                        + " SET terminated = 1, version = ?, updated_at = ? WHERE id = ?",
                newVersion, nowMillis, id);
    }

    /**
     * 覆盖写入计划某角色的乘务指派（先删后插，保证同一计划同一角色仅一条）。
     */
    public void upsertPlanCrew(long planId, CrewRole role, String crewId, String qualCode,
                               long nowMillis) {
        jdbc.update("DELETE FROM rail_plan_crew WHERE plan_id = ? AND role = ?",
                planId, role.name());
        jdbc.update("INSERT INTO rail_plan_crew (plan_id, role, crew_id, qual_code, assigned_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                planId, role.name(), crewId, qualCode, nowMillis);
    }

    /**
     * 查询计划的乘务指派，按角色排序（CONDUCTOR 在 DRIVER 前，字典序稳定）。
     */
    public List<PlanCrew> findPlanCrew(long planId) {
        return jdbc.query("SELECT id, plan_id, role, crew_id, qual_code"
                        + " FROM rail_plan_crew WHERE plan_id = ? ORDER BY role",
                PLAN_CREW_MAPPER, planId);
    }

    /**
     * 查询指定乘务员当前被指派到的全部计划乘务记录（回查用）。
     */
    public List<PlanCrew> findPlanCrewByCrewId(String crewId) {
        return jdbc.query("SELECT id, plan_id, role, crew_id, qual_code"
                        + " FROM rail_plan_crew WHERE crew_id = ? ORDER BY plan_id, role",
                PLAN_CREW_MAPPER, crewId);
    }

    /**
     * 追加不可变风险记录；唯一约束（plan_id, role, qual_code）冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertRiskRecord(long planId, String scheduleKey, CrewRole role, String crewId,
                                 String qualCode, String reason, long nowMillis) {
        jdbc.update("INSERT INTO rail_crew_risk_record"
                        + " (plan_id, schedule_key, role, crew_id, qual_code, reason, recorded_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                planId, scheduleKey, role.name(), crewId, qualCode, reason, nowMillis);
    }

    /**
     * 查询计划的风险记录，按主键升序（写入顺序）。
     */
    public List<CrewRiskRecord> findRiskRecordsByPlan(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, role, crew_id, qual_code, reason, recorded_at"
                        + " FROM rail_crew_risk_record WHERE plan_id = ? ORDER BY id",
                RISK_MAPPER, planId);
    }

    /**
     * 查询指定资质代码产生的全部风险记录，按主键升序。
     */
    public List<CrewRiskRecord> findRiskRecordsByQualCode(String qualCode) {
        return jdbc.query("SELECT id, plan_id, schedule_key, role, crew_id, qual_code, reason, recorded_at"
                        + " FROM rail_crew_risk_record WHERE qual_code = ? ORDER BY id",
                RISK_MAPPER, qualCode);
    }

    /**
     * 计划是否存在未消除的风险记录（风险记录不可变，存在即处于风险状态）。
     */
    public boolean hasRiskRecord(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_crew_risk_record WHERE plan_id = ?",
                Integer.class, planId);
        return count != null && count > 0;
    }

    /**
     * 回查提前终止资质影响的未来已发布计划乘务指派：
     * 计划状态为 PUBLISHED、指派使用该资质代码，且计划仍有未结束的占用（max(end_utc) &gt; now）。
     * 按 (plan_id, role) 排序保证稳定写入顺序。
     */
    public List<PlanCrew> findFuturePublishedAssignments(String qualCode, long nowMillis) {
        return jdbc.query("SELECT c.id, c.plan_id, c.role, c.crew_id, c.qual_code"
                        + " FROM rail_plan_crew c JOIN rail_day_plan p ON p.id = c.plan_id"
                        + " WHERE c.qual_code = ? AND p.status = 'PUBLISHED'"
                        + " AND (SELECT MAX(o.end_utc) FROM rail_plan_occupancy o WHERE o.plan_id = p.id) > ?"
                        + " ORDER BY c.plan_id, c.role",
                PLAN_CREW_MAPPER, qualCode, nowMillis);
    }

    /**
     * 查询计划当前生效的风险记录：风险记录存在，且对应角色当前指派仍为记录中的
     * 乘务员与资质组合（两角色均替换后风险消除，记录保留）。
     */
    public List<CrewRiskRecord> findActiveRiskRecords(long planId) {
        return jdbc.query("SELECT r.id, r.plan_id, r.schedule_key, r.role, r.crew_id,"
                        + " r.qual_code, r.reason, r.recorded_at"
                        + " FROM rail_crew_risk_record r JOIN rail_plan_crew c"
                        + " ON c.plan_id = r.plan_id AND c.role = r.role"
                        + " AND c.crew_id = r.crew_id AND c.qual_code = r.qual_code"
                        + " WHERE r.plan_id = ? ORDER BY r.id",
                RISK_MAPPER, planId);
    }

    /**
     * 同车底新增段门禁：给定列车编号中，是否存在其他已发布计划占用同一列车且仍处于风险状态。
     */
    public boolean existsRiskyPublishedTrain(List<String> trainNos, long excludePlanId) {
        if (trainNos.isEmpty()) {
            return false;
        }
        StringJoiner placeholders = new StringJoiner(", ");
        trainNos.forEach(t -> placeholders.add("?"));
        String sql = "SELECT COUNT(*) FROM rail_crew_risk_record r"
                + " JOIN rail_day_plan p ON p.id = r.plan_id AND p.status = 'PUBLISHED'"
                + " JOIN rail_plan_crew c ON c.plan_id = r.plan_id AND c.role = r.role"
                + " AND c.crew_id = r.crew_id AND c.qual_code = r.qual_code"
                + " JOIN rail_plan_occupancy o ON o.plan_id = r.plan_id"
                + " WHERE o.train_no IN (" + placeholders + ") AND r.plan_id <> ?";
        List<Object> args = new ArrayList<>(trainNos);
        args.add(excludePlanId);
        Integer count = jdbc.queryForObject(sql, Integer.class, args.toArray());
        return count != null && count > 0;
    }

    /**
     * 按计划 id 查询业务键（风险记录冗余字段写入用）。
     */
    public Optional<String> findScheduleKey(long planId) {
        return jdbc.query("SELECT schedule_key FROM rail_day_plan WHERE id = ?",
                (rs, n) -> rs.getString("schedule_key"), planId).stream().findFirst();
    }
}
