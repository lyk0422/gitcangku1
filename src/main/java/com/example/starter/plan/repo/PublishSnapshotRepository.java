package com.example.starter.plan.repo;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 发布快照持久化。快照仅在发布/改签事务提交前追加一次，之后只读；
 * 唯一约束 uk_rail_publish_snapshot_plan 保证同计划并发发布至多一条快照。
 */
@Repository
public class PublishSnapshotRepository {

    private final JdbcTemplate jdbc;

    public PublishSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加发布快照。
     */
    public void insert(long planId, String scheduleKey, int planVersion, String consistJson,
                       int ruleVersion, String rulesHash, String pathSectionsJson,
                       String sectionRestrictionsJson, String result, String operator,
                       long nowMillis) {
        jdbc.update("INSERT INTO rail_publish_snapshot"
                        + " (plan_id, schedule_key, plan_version, consist_json, rule_version, rules_hash,"
                        + " path_sections_json, section_restrictions_json, result, operator, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                planId, scheduleKey, planVersion, consistJson, ruleVersion, rulesHash,
                pathSectionsJson, sectionRestrictionsJson, result, operator, nowMillis);
    }

    /**
     * 按计划 id 查询快照原始行；无快照（草稿/从未发布）返回空。
     */
    public Optional<SnapshotRow> findByPlanId(long planId) {
        return jdbc.query("SELECT plan_id, schedule_key, plan_version, consist_json, rule_version,"
                        + " rules_hash, path_sections_json, section_restrictions_json, result,"
                        + " operator, created_at FROM rail_publish_snapshot WHERE plan_id = ?",
                (rs, n) -> new SnapshotRow(rs.getLong("plan_id"), rs.getString("schedule_key"),
                        rs.getInt("plan_version"), rs.getString("consist_json"),
                        rs.getInt("rule_version"), rs.getString("rules_hash"),
                        rs.getString("path_sections_json"),
                        rs.getString("section_restrictions_json"), rs.getString("result"),
                        rs.getString("operator"), rs.getLong("created_at")),
                planId).stream().findFirst();
    }

    /**
     * 快照原始行，编组/区段等 JSON 由业务层反序列化。
     */
    public record SnapshotRow(long planId, String scheduleKey, int planVersion, String consistJson,
                              int ruleVersion, String rulesHash, String pathSectionsJson,
                              String sectionRestrictionsJson, String result, String operator,
                              long createdAt) {
    }
}
