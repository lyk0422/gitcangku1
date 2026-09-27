package com.example.starter.plan.repo;

import com.example.starter.plan.model.HazardRuleSet;
import com.example.starter.plan.model.SectionRestriction;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 危险品互斥规则集版本与限制区段开放清单版本的持久化。
 * 配置只追加新版本行，历史版本不删除、不回写；规范化 JSON 与哈希由业务层计算后传入。
 * 配置写入与发布/改签共用全局 publish_lock 串行化，因此版本号读取无需单独行锁。
 */
@Repository
public class HazardConfigRepository {

    private final JdbcTemplate jdbc;

    public HazardConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加规则集新版本。
     */
    public void insertRuleVersion(int version, String rulesJson, String rulesHash, String operator,
                                  long nowMillis) {
        jdbc.update("INSERT INTO rail_hazard_rule_version"
                        + " (version, rules_json, rules_hash, operator, created_at) VALUES (?, ?, ?, ?, ?)",
                version, rulesJson, rulesHash, operator, nowMillis);
    }

    /**
     * 查询当前规则集最大版本号；无配置返回 0。
     */
    public int currentRuleVersion() {
        Integer v = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM rail_hazard_rule_version",
                Integer.class);
        return v == null ? 0 : v;
    }

    /**
     * 读取指定版本规则集原始 JSON；不存在返回空。
     */
    public Optional<RuleVersionRow> findRuleVersion(int version) {
        return jdbc.query("SELECT version, rules_json, rules_hash, operator, created_at"
                        + " FROM rail_hazard_rule_version WHERE version = ?",
                (rs, n) -> new RuleVersionRow(rs.getInt("version"), rs.getString("rules_json"),
                        rs.getString("rules_hash"), rs.getString("operator"), rs.getLong("created_at")),
                version).stream().findFirst();
    }

    /**
     * 规则集版本原始行。
     */
    public record RuleVersionRow(int version, String rulesJson, String rulesHash, String operator,
                                 long createdAt) {

        /**
         * 转换为模型对象（规则列表由业务层反序列化以避免仓库依赖 Jackson）。
         */
        public HazardRuleSet toModel(List<com.example.starter.plan.model.HazardRule> rules) {
            return new HazardRuleSet(version, rules, rulesHash, operator, createdAt);
        }
    }

    /**
     * 追加限制区段开放清单新版本。
     */
    public void insertSectionVersion(String sectionId, int version, String allowedClassesJson,
                                     String operator, long nowMillis) {
        jdbc.update("INSERT INTO rail_section_restriction"
                        + " (section_id, version, allowed_classes_json, operator, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                sectionId, version, allowedClassesJson, operator, nowMillis);
    }

    /**
     * 查询区段当前配置版本号；未配置返回 0。
     */
    public int currentSectionVersion(String sectionId) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM rail_section_restriction WHERE section_id = ?",
                Integer.class, sectionId);
        return v == null ? 0 : v;
    }

    /**
     * 读取指定区段的指定版本开放清单原始行；不存在返回空。
     */
    public Optional<SectionRow> findSectionVersion(String sectionId, int version) {
        return jdbc.query("SELECT section_id, version, allowed_classes_json, operator, created_at"
                        + " FROM rail_section_restriction WHERE section_id = ? AND version = ?",
                (rs, n) -> new SectionRow(rs.getString("section_id"), rs.getInt("version"),
                        rs.getString("allowed_classes_json"), rs.getString("operator"),
                        rs.getLong("created_at")),
                sectionId, version).stream().findFirst();
    }

    /**
     * 区段版本原始行。
     */
    public record SectionRow(String sectionId, int version, String allowedClassesJson,
                             String operator, long createdAt) {

        /**
         * 转换为模型对象（类别列表由业务层反序列化）。
         */
        public SectionRestriction toModel(List<String> allowedClasses) {
            return new SectionRestriction(sectionId, version, allowedClasses, operator, createdAt);
        }
    }
}
