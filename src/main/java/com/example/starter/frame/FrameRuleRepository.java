package com.example.starter.frame;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 坐标基准转换规则持久化：维护规则版本链与版本生效区间（左闭右开）。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class FrameRuleRepository {

    private static final RowMapper<FrameRule> RULE_MAPPER = (rs, rowNum) -> {
        Timestamp to = rs.getTimestamp("effective_to_utc");
        return new FrameRule(
                rs.getString("rule_id"),
                rs.getInt("version"),
                rs.getString("target_datum"),
                rs.getBigDecimal("offset_x"),
                rs.getBigDecimal("offset_y"),
                rs.getTimestamp("effective_from_utc").toInstant(),
                to == null ? null : to.toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public FrameRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取规则链上的最新版本（version 最大）；规则链不存在时返回空。
     */
    public Optional<FrameRule> findLatest(String ruleId) {
        return jdbcTemplate.query(
                        "SELECT rule_id, version, target_datum, offset_x, offset_y, "
                                + "effective_from_utc, effective_to_utc "
                                + "FROM coordinate_frame_rule WHERE rule_id = ? ORDER BY version DESC LIMIT 1",
                        RULE_MAPPER, ruleId)
                .stream().findFirst();
    }

    /**
     * 读取规则链的全部版本，按版本号升序。
     */
    public List<FrameRule> findByRuleId(String ruleId) {
        return jdbcTemplate.query(
                "SELECT rule_id, version, target_datum, offset_x, offset_y, "
                        + "effective_from_utc, effective_to_utc "
                        + "FROM coordinate_frame_rule WHERE rule_id = ? ORDER BY version ASC",
                RULE_MAPPER, ruleId);
    }

    /**
     * 查询某 UTC 时刻处于生效区间 [from, to) 内的全部规则版本（跨所有规则链）。
     * 唯一规则契约要求正常情况下至多命中一条；命中 0 条或多条由业务层裁决为 422。
     */
    public List<FrameRule> findEffectiveAt(Instant instant) {
        Timestamp point = Timestamp.from(instant);
        return jdbcTemplate.query(
                "SELECT rule_id, version, target_datum, offset_x, offset_y, "
                        + "effective_from_utc, effective_to_utc "
                        + "FROM coordinate_frame_rule "
                        + "WHERE effective_from_utc <= ? "
                        + "AND (effective_to_utc IS NULL OR ? < effective_to_utc) "
                        + "ORDER BY rule_id ASC, version ASC",
                RULE_MAPPER, point, point);
    }

    /**
     * 闭合规则链当前开放版本：将 effective_to_utc 置为新版本生效起始时刻（左闭右开，不重叠）。
     */
    public void closeOpenVersion(String ruleId, Instant effectiveToUtc) {
        jdbcTemplate.update(
                "UPDATE coordinate_frame_rule SET effective_to_utc = ? "
                        + "WHERE rule_id = ? AND effective_to_utc IS NULL",
                Timestamp.from(effectiveToUtc), ruleId);
    }

    /**
     * 插入一条新版本规则。
     */
    public void insert(FrameRule rule) {
        jdbcTemplate.update(
                "INSERT INTO coordinate_frame_rule (rule_id, version, target_datum, offset_x, offset_y, "
                        + "effective_from_utc, effective_to_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                rule.ruleId(), rule.version(), rule.targetDatum(), rule.offsetX(), rule.offsetY(),
                Timestamp.from(rule.effectiveFromUtc()),
                rule.effectiveToUtc() == null ? null : Timestamp.from(rule.effectiveToUtc()));
    }
}
