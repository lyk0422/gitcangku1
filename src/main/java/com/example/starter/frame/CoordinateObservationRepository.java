package com.example.starter.frame;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 坐标观测结果持久化：coordinate_observation 记录接收时固化的原坐标、目标坐标、规则版本与复核状态。
 * 所有 SQL 使用参数化查询；记录写入后不再更新，规则调整不会改写历史结果。
 */
@Repository
public class CoordinateObservationRepository {

    private static final RowMapper<CoordinateObservation> OBSERVATION_MAPPER = (rs, rowNum) ->
            new CoordinateObservation(
                    rs.getString("coordinate_observation_id"),
                    rs.getString("source_datum"),
                    rs.getString("source_x"),
                    rs.getString("source_y"),
                    rs.getTimestamp("observed_at_utc").toInstant(),
                    rs.getString("target_datum"),
                    rs.getBigDecimal("target_x"),
                    rs.getBigDecimal("target_y"),
                    rs.getString("rule_id"),
                    rs.getInt("rule_version"),
                    ReviewStatus.valueOf(rs.getString("review_status")));

    private final JdbcTemplate jdbcTemplate;

    public CoordinateObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按业务标识查询坐标观测；不存在时返回空。
     */
    public Optional<CoordinateObservation> find(String coordinateObservationId) {
        return jdbcTemplate.query(
                        "SELECT coordinate_observation_id, source_datum, source_x, source_y, observed_at_utc, "
                                + "target_datum, target_x, target_y, rule_id, rule_version, review_status "
                                + "FROM coordinate_observation WHERE coordinate_observation_id = ?",
                        OBSERVATION_MAPPER, coordinateObservationId)
                .stream().findFirst();
    }

    /**
     * 插入一条坐标观测结果（含转换后坐标、规则版本与复核状态）。
     */
    public void insert(CoordinateObservation observation) {
        jdbcTemplate.update(
                "INSERT INTO coordinate_observation (coordinate_observation_id, source_datum, source_x, source_y, "
                        + "observed_at_utc, target_datum, target_x, target_y, rule_id, rule_version, "
                        + "review_status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                observation.coordinateObservationId(),
                observation.sourceDatum(),
                observation.sourceX(),
                observation.sourceY(),
                Timestamp.from(observation.observedAtUtc()),
                observation.targetDatum(),
                observation.targetX(),
                observation.targetY(),
                observation.ruleId(),
                observation.ruleVersion(),
                observation.reviewStatus().name());
    }
}
