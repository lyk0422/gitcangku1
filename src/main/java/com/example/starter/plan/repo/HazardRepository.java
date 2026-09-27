package com.example.starter.plan.repo;

import com.example.starter.plan.model.PlanCar;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.SectionRestriction;
import com.example.starter.plan.model.SeparationRule;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
 * 危险品编组、隔离规则、限制区段配置与发布快照的 JDBC 持久化。
 * 所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class HazardRepository {

    private static final RowMapper<PlanCar> CAR_MAPPER = (rs, n) -> new PlanCar(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("car_position"),
            rs.getString("category"));

    private static final RowMapper<SeparationRule> RULE_MAPPER = (rs, n) -> new SeparationRule(
            rs.getLong("id"),
            rs.getString("category_a"),
            rs.getString("category_b"),
            rs.getInt("min_separation"));

    private static final RowMapper<SectionRestriction> RESTRICTION_MAPPER = (rs, n) -> new SectionRestriction(
            rs.getString("section_id"),
            rs.getBoolean("restricted"),
            rs.getInt("version"));

    private static final RowMapper<PublishSnapshot> SNAPSHOT_MAPPER = (rs, n) -> new PublishSnapshot(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getString("op_type"),
            rs.getString("conclusion"),
            rs.getString("operator"),
            rs.getString("snapshot_json"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public HazardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 编组 ----------

    /**
     * 整体替换计划的危险品编组：先删后插，cars 按车厢位置升序持久化。
     */
    public void replaceCars(long planId, List<PlanCar> cars) {
        jdbc.update("DELETE FROM rail_plan_car WHERE plan_id = ?", planId);
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_car (plan_id, car_position, category) VALUES (?, ?, ?)",
                cars, cars.size(),
                (ps, c) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, c.carPosition());
                    ps.setString(3, c.category());
                });
    }

    /**
     * 查询计划编组，按车厢位置升序。
     */
    public List<PlanCar> findCars(long planId) {
        return jdbc.query("SELECT id, plan_id, car_position, category FROM rail_plan_car"
                        + " WHERE plan_id = ? ORDER BY car_position",
                CAR_MAPPER, planId);
    }

    // ---------- 隔离规则 ----------

    /**
     * 查询全部隔离规则，按 (category_a, category_b) 字典序稳定排序。
     */
    public List<SeparationRule> findAllRules() {
        return jdbc.query("SELECT id, category_a, category_b, min_separation"
                        + " FROM rail_hazard_separation_rule ORDER BY category_a, category_b",
                RULE_MAPPER);
    }

    /**
     * 整体替换隔离规则集：先清空再插入（类别对须已按字典序规范化）。
     */
    public void replaceRules(List<SeparationRule> rules, long nowMillis) {
        jdbc.update("DELETE FROM rail_hazard_separation_rule");
        jdbc.batchUpdate(
                "INSERT INTO rail_hazard_separation_rule (category_a, category_b, min_separation, updated_at)"
                        + " VALUES (?, ?, ?, ?)",
                rules, rules.size(),
                (ps, r) -> {
                    ps.setString(1, r.categoryA());
                    ps.setString(2, r.categoryB());
                    ps.setInt(3, r.minSeparation());
                    ps.setLong(4, nowMillis);
                });
    }

    // ---------- 限制区段配置 ----------

    /**
     * 查询全部限制区段配置，按 section_id 字典序稳定排序。
     */
    public List<SectionRestriction> findAllRestrictions() {
        return jdbc.query("SELECT section_id, restricted, version FROM rail_section_restriction"
                        + " ORDER BY section_id",
                RESTRICTION_MAPPER);
    }

    /**
     * 按区段集合查询限制配置（不加锁），按 section_id 字典序。
     */
    public List<SectionRestriction> findRestrictions(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT section_id, restricted, version FROM rail_section_restriction"
                        + " WHERE section_id IN (" + placeholders + ") ORDER BY section_id",
                RESTRICTION_MAPPER, sectionIds.toArray());
    }

    /**
     * 查询指定区段的开放清单，按类别字典序。
     */
    public List<String> findAllowedCategories(String sectionId) {
        return jdbc.query("SELECT category FROM rail_section_allowed_category"
                        + " WHERE section_id = ? ORDER BY category",
                (rs, n) -> rs.getString("category"), sectionId);
    }

    /**
     * 整体替换限制区段配置：清空后按给定版本重建；版本由上层基于旧配置计算。
     */
    public void replaceRestrictions(List<SectionRestriction> restrictions,
                                    List<List<String>> allowedCategories, long nowMillis) {
        jdbc.update("DELETE FROM rail_section_allowed_category");
        jdbc.update("DELETE FROM rail_section_restriction");
        for (int i = 0; i < restrictions.size(); i++) {
            SectionRestriction r = restrictions.get(i);
            jdbc.update("INSERT INTO rail_section_restriction (section_id, restricted, version, updated_at)"
                            + " VALUES (?, ?, ?, ?)",
                    r.sectionId(), r.restricted(), r.version(), nowMillis);
            for (String category : allowedCategories.get(i)) {
                jdbc.update("INSERT INTO rail_section_allowed_category (section_id, category)"
                        + " VALUES (?, ?)", r.sectionId(), category);
            }
        }
    }

    // ---------- 发布快照 ----------

    /**
     * 写入发布快照（不可变）；同计划重复写入抛出 DuplicateKeyException 由上层裁决。
     */
    public long insertSnapshot(long planId, String scheduleKey, String opType, String conclusion,
                               String operator, String snapshotJson, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_publish_snapshot"
                            + " (plan_id, schedule_key, op_type, conclusion, operator, snapshot_json, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, planId);
            ps.setString(2, scheduleKey);
            ps.setString(3, opType);
            ps.setString(4, conclusion);
            ps.setString(5, operator);
            ps.setString(6, snapshotJson);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按计划 id 查询发布快照。
     */
    public Optional<PublishSnapshot> findSnapshotByPlanId(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, op_type, conclusion, operator,"
                        + " snapshot_json, created_at FROM rail_publish_snapshot WHERE plan_id = ?",
                SNAPSHOT_MAPPER, planId).stream().findFirst();
    }

    /**
     * 查询指定运营日的全部发布快照，按 (created_at, id) 稳定排序。
     */
    public List<PublishSnapshot> findSnapshotsByOpDate(java.time.LocalDate opDate) {
        return jdbc.query("SELECT s.id, s.plan_id, s.schedule_key, s.op_type, s.conclusion,"
                        + " s.operator, s.snapshot_json, s.created_at"
                        + " FROM rail_publish_snapshot s JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.op_date = ? ORDER BY s.created_at, s.id",
                SNAPSHOT_MAPPER, java.sql.Date.valueOf(opDate));
    }
}
