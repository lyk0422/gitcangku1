package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 闭合导线成果持久化：traverse_result 主表与 traverse_leg 边表，生成后不可变、永不更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class TraverseRepository {

    private static final RowMapper<TraverseRecord.Leg> LEG_MAPPER = (rs, rowNum) -> new TraverseRecord.Leg(
            rs.getInt("leg_index"),
            rs.getString("observation_id"),
            rs.getInt("observation_version"),
            rs.getString("dx"),
            rs.getString("dy"),
            rs.getString("w"),
            rs.getString("adjusted_dx"),
            rs.getString("adjusted_dy"));

    private final JdbcTemplate jdbcTemplate;

    public TraverseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入成果主表记录；request_id 唯一键冲突时抛出重复键异常。
     */
    public void insertResult(TraverseRecord record) {
        jdbcTemplate.update(
                "INSERT INTO traverse_result (traverse_id, request_id, operator, leg_count, "
                        + "closure_dx, closure_dy, total_weight, generated_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.traverseId(),
                record.requestId(),
                record.operator(),
                record.legCount(),
                record.closureDx(),
                record.closureDy(),
                record.totalWeight(),
                Timestamp.from(record.generatedAtUtc()));
    }

    /**
     * 插入一条成果边记录。
     */
    public void insertLeg(String traverseId, TraverseRecord.Leg leg) {
        jdbcTemplate.update(
                "INSERT INTO traverse_leg (traverse_id, leg_index, observation_id, observation_version, "
                        + "dx, dy, w, adjusted_dx, adjusted_dy) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                traverseId,
                leg.legIndex(),
                leg.observationId(),
                leg.observationVersion(),
                leg.dx(),
                leg.dy(),
                leg.w(),
                leg.adjustedDx(),
                leg.adjustedDy());
    }

    /**
     * 按成果标识查询完整成果（主表 + 按边序排列的边表）；不存在时返回空。
     */
    public Optional<TraverseRecord> findById(String traverseId) {
        List<TraverseRecord> records = jdbcTemplate.query(
                "SELECT traverse_id, request_id, operator, leg_count, closure_dx, closure_dy, "
                        + "total_weight, generated_at_utc FROM traverse_result WHERE traverse_id = ?",
                (rs, rowNum) -> new TraverseRecord(
                        rs.getString("traverse_id"),
                        rs.getString("request_id"),
                        rs.getString("operator"),
                        rs.getInt("leg_count"),
                        rs.getString("closure_dx"),
                        rs.getString("closure_dy"),
                        rs.getString("total_weight"),
                        rs.getTimestamp("generated_at_utc").toInstant(),
                        findLegs(rs.getString("traverse_id"))),
                traverseId);
        return records.stream().findFirst();
    }

    private List<TraverseRecord.Leg> findLegs(String traverseId) {
        return jdbcTemplate.query(
                "SELECT leg_index, observation_id, observation_version, dx, dy, w, adjusted_dx, adjusted_dy "
                        + "FROM traverse_leg WHERE traverse_id = ? ORDER BY leg_index ASC",
                LEG_MAPPER, traverseId);
    }
}
