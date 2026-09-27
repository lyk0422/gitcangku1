package com.example.starter.plan.repo;

import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.RestrictedSection;
import com.example.starter.plan.model.SeparationRule;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 危险品编组、互斥规则、限制区段配置与发布快照的 JDBC 持久化。
 * 所有时刻以 UTC 毫秒存储；快照追加后不可变。
 */
@Repository
public class HazardRepository {

    private static final RowMapper<HazardCar> CAR_MAPPER = (rs, n) -> new HazardCar(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getInt("car_position"),
            rs.getString("hazard_class"));

    private static final RowMapper<SeparationRule> RULE_MAPPER = (rs, n) -> new SeparationRule(
            rs.getString("class_a"),
            rs.getString("class_b"),
            rs.getInt("min_separation"));

    private static final RowMapper<PublishSnapshot> SNAPSHOT_MAPPER = (rs, n) -> new PublishSnapshot(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("schedule_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getString("operator"),
            rs.getString("request_key"),
            rs.getString("source"),
            rs.getInt("rule_version"),
            rs.getInt("section_version"),
            rs.getString("formation_json"),
            rs.getString("sections_json"),
            rs.getString("validation_result"),
            rs.getLong("created_at"));

    /**
     * 危险品配置全局版本。
     *
     * @param ruleVersion    互斥规则集版本
     * @param sectionVersion 限制区段配置版本
     */
    public record ConfigVersions(int ruleVersion, int sectionVersion) {
    }

    private final JdbcTemplate jdbc;

    public HazardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 编组 ----------

    /**
     * 整体替换计划的危险品编组：先删后插，保持提交顺序。
     */
    public void replaceHazardCars(long planId, List<HazardCar> cars) {
        jdbc.update("DELETE FROM rail_plan_hazard_car WHERE plan_id = ?", planId);
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_hazard_car (plan_id, seq, car_position, hazard_class)"
                        + " VALUES (?, ?, ?, ?)",
                cars, cars.size(),
                (ps, c) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, c.seq());
                    ps.setInt(3, c.carPosition());
                    ps.setString(4, c.hazardClass());
                });
    }

    /**
     * 查询计划编组，按车厢位置升序（稳定排序）。
     */
    public List<HazardCar> findHazardCars(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, car_position, hazard_class"
                        + " FROM rail_plan_hazard_car WHERE plan_id = ? ORDER BY car_position",
                CAR_MAPPER, planId);
    }

    // ---------- 互斥规则 ----------

    /**
     * 整体替换互斥规则集：先删全表再插入，规则集版本由调用方在配置锁内递增。
     */
    public void replaceSeparationRules(List<SeparationRule> rules, long nowMillis) {
        jdbc.update("DELETE FROM rail_hazard_separation_rule");
        jdbc.batchUpdate(
                "INSERT INTO rail_hazard_separation_rule (class_a, class_b, min_separation, updated_at)"
                        + " VALUES (?, ?, ?, ?)",
                rules, rules.size(),
                (ps, r) -> {
                    ps.setString(1, r.classA());
                    ps.setString(2, r.classB());
                    ps.setInt(3, r.minSeparation());
                    ps.setLong(4, nowMillis);
                });
    }

    /**
     * 查询全部互斥规则，按 (classA, classB) 字典序（稳定排序）。
     */
    public List<SeparationRule> findSeparationRules() {
        return jdbc.query("SELECT class_a, class_b, min_separation FROM rail_hazard_separation_rule"
                + " ORDER BY class_a, class_b", RULE_MAPPER);
    }

    // ---------- 限制区段 ----------

    /**
     * 覆盖式设置限制区段开放清单：主表 upsert 后整体替换类别清单。
     */
    public void upsertRestrictedSection(String sectionId, List<String> allowedClasses, long nowMillis) {
        jdbc.update("MERGE INTO rail_restricted_section KEY(section_id) VALUES (?, ?)",
                sectionId, nowMillis);
        jdbc.update("DELETE FROM rail_restricted_section_class WHERE section_id = ?", sectionId);
        jdbc.batchUpdate(
                "INSERT INTO rail_restricted_section_class (section_id, hazard_class) VALUES (?, ?)",
                allowedClasses, allowedClasses.size(),
                (ps, c) -> {
                    ps.setString(1, sectionId);
                    ps.setString(2, c);
                });
    }

    /**
     * 按区段 ID 查询限制配置（含开放清单，按字典序）。
     */
    public Optional<RestrictedSection> findRestrictedSection(String sectionId) {
        List<String> classes = jdbc.query(
                "SELECT hazard_class FROM rail_restricted_section_class"
                        + " WHERE section_id = ? ORDER BY hazard_class",
                (rs, n) -> rs.getString(1), sectionId);
        if (classes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RestrictedSection(sectionId, classes));
    }

    /**
     * 查询全部限制区段，按区段 ID 字典序（稳定排序）。
     */
    public List<RestrictedSection> findRestrictedSections() {
        List<String> sectionIds = jdbc.query(
                "SELECT section_id FROM rail_restricted_section ORDER BY section_id",
                (rs, n) -> rs.getString(1));
        return sectionIds.stream()
                .map(id -> findRestrictedSection(id)
                        .orElseThrow(() -> new IllegalStateException("限制区段配置缺失: " + id)))
                .toList();
    }

    // ---------- 配置版本与锁 ----------

    /**
     * 查询当前危险品配置版本（不加锁，用于只读展示）。
     */
    public ConfigVersions findConfigVersions() {
        return jdbc.queryForObject(
                "SELECT rule_version, section_version FROM rail_hazard_config_meta WHERE id = 1",
                (rs, n) -> new ConfigVersions(rs.getInt(1), rs.getInt(2)));
    }

    /**
     * 获取危险品配置全局互斥锁（单行 FOR UPDATE）并返回当前版本，
     * 串行化规则与区段配置变更；须在事务内调用。
     */
    public ConfigVersions lockConfigVersions() {
        return jdbc.queryForObject(
                "SELECT rule_version, section_version FROM rail_hazard_config_meta WHERE id = 1 FOR UPDATE",
                (rs, n) -> new ConfigVersions(rs.getInt(1), rs.getInt(2)));
    }

    /**
     * 递增互斥规则集版本。
     */
    public void bumpRuleVersion(long nowMillis) {
        jdbc.update("UPDATE rail_hazard_config_meta SET rule_version = rule_version + 1,"
                + " updated_at = ? WHERE id = 1", nowMillis);
    }

    /**
     * 递增限制区段配置版本。
     */
    public void bumpSectionVersion(long nowMillis) {
        jdbc.update("UPDATE rail_hazard_config_meta SET section_version = section_version + 1,"
                + " updated_at = ? WHERE id = 1", nowMillis);
    }

    // ---------- 发布快照 ----------

    /**
     * 追加发布快照（不可变）。
     */
    public void insertSnapshot(PublishSnapshot snapshot) {
        jdbc.update("INSERT INTO rail_plan_publish_snapshot"
                        + " (plan_id, schedule_key, op_date, operator, request_key, source,"
                        + " rule_version, section_version, formation_json, sections_json,"
                        + " validation_result, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.planId(), snapshot.scheduleKey(), Date.valueOf(snapshot.opDate()),
                snapshot.operator(), snapshot.requestKey(), snapshot.source(),
                snapshot.ruleVersion(), snapshot.sectionVersion(),
                snapshot.formationJson(), snapshot.sectionsJson(),
                snapshot.validationResult(), snapshot.createdAt());
    }

    /**
     * 查询指定计划的发布快照历史，按快照 id 升序（即追加顺序，稳定排序）。
     */
    public List<PublishSnapshot> findSnapshotsByPlanId(long planId) {
        return jdbc.query("SELECT id, plan_id, schedule_key, op_date, operator, request_key, source,"
                        + " rule_version, section_version, formation_json, sections_json,"
                        + " validation_result, created_at"
                        + " FROM rail_plan_publish_snapshot WHERE plan_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, planId);
    }
}
