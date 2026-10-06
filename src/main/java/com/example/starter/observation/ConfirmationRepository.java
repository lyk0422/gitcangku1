package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 一致性确认记录持久化：observation_confirmation 记录成功确认的完整冻结上下文，
 * 不可变、永不更新。所有 SQL 使用参数化查询。
 */
@Repository
public class ConfirmationRepository {

    private static final String COLUMNS =
            "confirmation_id, request_id, first_observation_id, first_version, "
                    + "second_observation_id, second_version, "
                    + "first_x, first_y, first_a, first_b, first_c, "
                    + "second_x, second_y, second_a, second_b, second_c, "
                    + "diff_u, diff_v, sum_a, sum_b, sum_c, lhs_value, rhs_value, "
                    + "operator, confirmed_at_utc";

    private static final RowMapper<ConfirmationRecord> RECORD_MAPPER = (rs, rowNum) -> new ConfirmationRecord(
            rs.getString("confirmation_id"),
            rs.getString("request_id"),
            rs.getString("first_observation_id"),
            rs.getInt("first_version"),
            rs.getString("second_observation_id"),
            rs.getInt("second_version"),
            new PositionedObservationSnapshot(
                    rs.getString("first_observation_id"), rs.getInt("first_version"),
                    rs.getBigDecimal("first_x"), rs.getBigDecimal("first_y"),
                    rs.getBigDecimal("first_a"), rs.getBigDecimal("first_b"), rs.getBigDecimal("first_c"),
                    false),
            new PositionedObservationSnapshot(
                    rs.getString("second_observation_id"), rs.getInt("second_version"),
                    rs.getBigDecimal("second_x"), rs.getBigDecimal("second_y"),
                    rs.getBigDecimal("second_a"), rs.getBigDecimal("second_b"), rs.getBigDecimal("second_c"),
                    false),
            rs.getBigDecimal("diff_u"),
            rs.getBigDecimal("diff_v"),
            rs.getBigDecimal("sum_a"),
            rs.getBigDecimal("sum_b"),
            rs.getBigDecimal("sum_c"),
            rs.getBigDecimal("lhs_value"),
            rs.getBigDecimal("rhs_value"),
            rs.getString("operator"),
            rs.getTimestamp("confirmed_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ConfirmationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变确认记录；与业务核对、幂等去重在同一事务内提交。
     */
    public void insert(ConfirmationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_confirmation (" + COLUMNS + ", created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "CURRENT_TIMESTAMP)",
                record.confirmationId(),
                record.requestId(),
                record.firstObservationId(),
                record.firstVersion(),
                record.secondObservationId(),
                record.secondVersion(),
                record.first().x(), record.first().y(),
                record.first().a(), record.first().b(), record.first().c(),
                record.second().x(), record.second().y(),
                record.second().a(), record.second().b(), record.second().c(),
                record.diffU().toPlainString(),
                record.diffV().toPlainString(),
                record.sumA().toPlainString(),
                record.sumB().toPlainString(),
                record.sumC().toPlainString(),
                record.lhsValue().toPlainString(),
                record.rhsValue().toPlainString(),
                record.operator(),
                Timestamp.from(record.confirmedAtUtc()));
    }

    /**
     * 按全局唯一 confirmationId 查询确认记录；不存在时返回空。
     */
    public Optional<ConfirmationRecord> findByConfirmationId(String confirmationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM observation_confirmation WHERE confirmation_id = ?",
                        RECORD_MAPPER, confirmationId)
                .stream().findFirst();
    }

    /**
     * 按观测点标识查询其参与的全部确认记录（无论作为第一条还是第二条），按确认时刻先后排序。
     */
    public List<ConfirmationRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_confirmation "
                        + "WHERE first_observation_id = ? OR second_observation_id = ? "
                        + "ORDER BY confirmed_at_utc ASC, confirmation_id ASC",
                RECORD_MAPPER, observationId, observationId);
    }
}
