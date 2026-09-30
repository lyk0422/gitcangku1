package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 坐标观测持久化：转换结果与命中规则版本冻结在一行中；request_id 唯一键支撑同参重放与改参 409。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class CoordinateObservationRepository {

    private static final RowMapper<CoordinateObservation> OBSERVATION_MAPPER = (rs, rowNum) ->
            new CoordinateObservation(
                    rs.getString("observation_id"),
                    rs.getString("request_id"),
                    rs.getString("source_frame"),
                    rs.getBigDecimal("original_x"),
                    rs.getBigDecimal("original_y"),
                    rs.getTimestamp("observed_at_utc").toInstant(),
                    rs.getString("target_frame"),
                    rs.getBigDecimal("target_x"),
                    rs.getBigDecimal("target_y"),
                    rs.getInt("rule_version"),
                    ReviewStatus.valueOf(rs.getString("review_status")),
                    rs.getBoolean("auto_merged"));

    private final JdbcTemplate jdbcTemplate;

    public CoordinateObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁定固定守卫行：规则发布与观测写入事务均先取得此锁，从而以提交时有效版本串行裁决。
     */
    public void lockGuard() {
        jdbcTemplate.queryForObject("SELECT id FROM coordinate_rule_guard WHERE id = 1 FOR UPDATE", Integer.class);
    }

    /**
     * 插入一条坐标观测（转换结果与规则版本已冻结）。
     * observationId 主键或 requestId 唯一键冲突时由数据库抛出重复键异常。
     */
    public void insert(CoordinateObservation observation) {
        jdbcTemplate.update(
                "INSERT INTO coordinate_observation (observation_id, request_id, source_frame, "
                        + "original_x, original_y, observed_at_utc, target_frame, target_x, target_y, "
                        + "rule_version, review_status, auto_merged, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                observation.observationId(),
                observation.requestId(),
                observation.sourceFrame(),
                observation.originalX(),
                observation.originalY(),
                Timestamp.from(observation.observedAtUtc()),
                observation.targetFrame(),
                observation.targetX(),
                observation.targetY(),
                observation.ruleVersion(),
                observation.reviewStatus().name(),
                observation.autoMerged());
    }

    /**
     * 按业务标识查询坐标观测；不存在时返回空。
     */
    public Optional<CoordinateObservation> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, request_id, source_frame, original_x, original_y, observed_at_utc, "
                                + "target_frame, target_x, target_y, rule_version, review_status, auto_merged "
                                + "FROM coordinate_observation WHERE observation_id = ?",
                        OBSERVATION_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 按请求标识查询坐标观测（幂等重放路径）；不存在时返回空。
     */
    public Optional<CoordinateObservation> findByRequestId(String requestId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, request_id, source_frame, original_x, original_y, observed_at_utc, "
                                + "target_frame, target_x, target_y, rule_version, review_status, auto_merged "
                                + "FROM coordinate_observation WHERE request_id = ?",
                        OBSERVATION_MAPPER, requestId)
                .stream().findFirst();
    }
}
