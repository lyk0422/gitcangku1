package com.example.starter.plan.repo;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Platform;
import com.example.starter.plan.model.PlatformRisk;
import com.example.starter.plan.model.PlatformSpan;
import com.example.starter.plan.model.PlanStatus;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 站台主数据、计划停靠站台与站台超长风险快照的 JDBC 持久化。
 * 所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class PlatformRepository {

    private static final RowMapper<Platform> PLATFORM_MAPPER = (rs, n) -> new Platform(
            rs.getString("code"),
            rs.getInt("effective_length"));

    private static final RowMapper<PlatformRisk> RISK_MAPPER = (rs, n) -> new PlatformRisk(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("platform_code"),
            rs.getInt("previous_length"),
            rs.getInt("new_length"),
            rs.getInt("consist_length"),
            rs.getLong("marked_at"),
            rs.getObject("resolved_at", Long.class));

    private static final RowMapper<PlatformSpan> SPAN_MAPPER = (rs, n) -> new PlatformSpan(
            rs.getString("schedule_key"),
            rs.getString("platform_code"),
            rs.getObject("consist_length", Integer.class),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private static final RowMapper<DayPlan> PLAN_MAPPER = (rs, n) -> new DayPlan(
            rs.getLong("id"),
            rs.getString("schedule_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getInt("version"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getObject("consist_length", Integer.class));

    private final JdbcTemplate jdbc;

    public PlatformRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建站台；站台代码唯一约束冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public void insertPlatform(String code, int effectiveLength, long nowMillis) {
        jdbc.update("INSERT INTO rail_platform (code, effective_length, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?)",
                code, effectiveLength, nowMillis, nowMillis);
    }

    /**
     * 按代码查询站台（不加锁）。
     */
    public Optional<Platform> findByCode(String code) {
        return jdbc.query("SELECT code, effective_length FROM rail_platform WHERE code = ?",
                PLATFORM_MAPPER, code).stream().findFirst();
    }

    /**
     * 按代码查询站台并加行级写锁，须在事务内调用，用于串行化长度调整。
     */
    public Optional<Platform> findByCodeForUpdate(String code) {
        return jdbc.query("SELECT code, effective_length FROM rail_platform WHERE code = ?"
                        + " FOR UPDATE",
                PLATFORM_MAPPER, code).stream().findFirst();
    }

    /**
     * 更新站台有效长度。
     */
    public void updateLength(String code, int effectiveLength, long nowMillis) {
        jdbc.update("UPDATE rail_platform SET effective_length = ?, updated_at = ? WHERE code = ?",
                effectiveLength, nowMillis, code);
    }

    /**
     * 回查停靠指定站台、运营日不早于 today、编组长度超过 newLength 的已发布计划。
     */
    public List<DayPlan> findOverLengthPublishedPlans(String platformCode, LocalDate today,
                                                      int newLength) {
        return jdbc.query("SELECT p.id, p.schedule_key, p.op_date, p.version, p.status,"
                        + " p.consist_length FROM rail_day_plan p"
                        + " JOIN rail_plan_platform pp ON pp.plan_id = p.id"
                        + " WHERE pp.platform_code = ? AND p.status = 'PUBLISHED'"
                        + " AND p.op_date >= ? AND p.consist_length IS NOT NULL"
                        + " AND p.consist_length > ? ORDER BY p.id",
                PLAN_MAPPER, platformCode, Date.valueOf(today), newLength);
    }

    /**
     * 写入风险快照（固化下调前站台原长度与当前编组长度）。
     */
    public void insertRisk(long planId, String platformCode, int previousLength, int newLength,
                           int consistLength, long nowMillis) {
        jdbc.update("INSERT INTO rail_plan_platform_risk"
                        + " (plan_id, platform_code, previous_length, new_length, consist_length,"
                        + " marked_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, NULL)",
                planId, platformCode, previousLength, newLength, consistLength, nowMillis);
    }

    /**
     * 查询计划在某站台上未解除的风险快照。
     */
    public Optional<PlatformRisk> findOpenRisk(long planId, String platformCode) {
        return jdbc.query("SELECT id, plan_id, platform_code, previous_length, new_length,"
                        + " consist_length, marked_at, resolved_at FROM rail_plan_platform_risk"
                        + " WHERE plan_id = ? AND platform_code = ? AND resolved_at IS NULL",
                RISK_MAPPER, planId, platformCode).stream().findFirst();
    }

    /**
     * 再次下调时刷新未解除风险的下调后长度与标记时刻，原长度快照不改写。
     */
    public void refreshOpenRisk(long riskId, int newLength, long nowMillis) {
        jdbc.update("UPDATE rail_plan_platform_risk SET new_length = ?, marked_at = ?"
                        + " WHERE id = ?",
                newLength, nowMillis, riskId);
    }

    /**
     * 解除计划全部未解除风险（编组合规变更或取消时调用）。
     */
    public void resolveOpenRisks(long planId, long nowMillis) {
        jdbc.update("UPDATE rail_plan_platform_risk SET resolved_at = ?"
                        + " WHERE plan_id = ? AND resolved_at IS NULL",
                nowMillis, planId);
    }

    /**
     * 计划是否存在未解除的站台风险。
     */
    public boolean hasOpenRisk(long planId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rail_plan_platform_risk"
                        + " WHERE plan_id = ? AND resolved_at IS NULL",
                Integer.class, planId);
        return count != null && count > 0;
    }

    /**
     * 查询计划全部风险快照（含已解除），按标记顺序升序。
     */
    public List<PlatformRisk> findRisksByPlan(long planId) {
        return jdbc.query("SELECT id, plan_id, platform_code, previous_length, new_length,"
                        + " consist_length, marked_at, resolved_at FROM rail_plan_platform_risk"
                        + " WHERE plan_id = ? ORDER BY id",
                RISK_MAPPER, planId);
    }

    /**
     * 查询指定运营日停靠指定站台的已发布计划占用窗口（计划区段占用整体跨度）。
     */
    public List<PlatformSpan> findPlatformOccupancy(LocalDate opDate, String platformCode) {
        return jdbc.query("SELECT p.schedule_key, pp.platform_code, p.consist_length,"
                        + " MIN(o.start_utc) AS start_utc, MAX(o.end_utc) AS end_utc"
                        + " FROM rail_day_plan p"
                        + " JOIN rail_plan_platform pp ON pp.plan_id = p.id"
                        + " JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ? AND pp.platform_code = ?"
                        + " GROUP BY p.id, p.schedule_key, pp.platform_code, p.consist_length"
                        + " ORDER BY start_utc, p.schedule_key",
                SPAN_MAPPER, Date.valueOf(opDate), platformCode);
    }

    /**
     * 查询指定运营日、停靠指定站台集合的其他已发布计划占用窗口（排除给定计划集合），
     * 用于发布/改签时的同站台重叠裁决。
     */
    public List<PlatformSpan> findPublishedPlatformSpans(LocalDate opDate,
                                                         Collection<String> platformCodes,
                                                         Collection<Long> excludePlanIds) {
        if (platformCodes.isEmpty()) {
            return List.of();
        }
        StringJoiner platformPlaceholders = new StringJoiner(", ");
        platformCodes.forEach(c -> platformPlaceholders.add("?"));
        StringBuilder sql = new StringBuilder(
                "SELECT p.schedule_key, pp.platform_code, p.consist_length,"
                        + " MIN(o.start_utc) AS start_utc, MAX(o.end_utc) AS end_utc"
                        + " FROM rail_day_plan p"
                        + " JOIN rail_plan_platform pp ON pp.plan_id = p.id"
                        + " JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND p.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" AND pp.platform_code IN (").append(platformPlaceholders).append(')')
                .append(" GROUP BY p.id, p.schedule_key, pp.platform_code, p.consist_length")
                .append(" ORDER BY pp.platform_code, start_utc");
        args.addAll(platformCodes);
        return jdbc.query(sql.toString(), SPAN_MAPPER, args.toArray());
    }
}
