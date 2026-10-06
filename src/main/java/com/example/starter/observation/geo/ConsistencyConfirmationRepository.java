package com.example.starter.observation.geo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 一致性确认记录持久化：consistency_confirmation 记录确认时刻两条观测的完整快照与比较值，
 * 不可变、永不更新。所有 SQL 使用参数化查询。
 */
@Repository
public class ConsistencyConfirmationRepository {

    private static final String COLUMNS = "confirmation_id, request_id, "
            + "observation_id_a, version_a, x_a, y_a, a_a, b_a, c_a, "
            + "observation_id_b, version_b, x_b, y_b, a_b, b_b, c_b, "
            + "u, v, sum_a, sum_b, sum_c, lhs, rhs, operator, confirmed_at_utc";

    private static final RowMapper<ConsistencyConfirmation> CONFIRMATION_MAPPER =
            (rs, rowNum) -> new ConsistencyConfirmation(
                    rs.getString("confirmation_id"),
                    rs.getString("request_id"),
                    rs.getString("observation_id_a"),
                    rs.getInt("version_a"),
                    rs.getString("x_a"),
                    rs.getString("y_a"),
                    rs.getString("a_a"),
                    rs.getString("b_a"),
                    rs.getString("c_a"),
                    rs.getString("observation_id_b"),
                    rs.getInt("version_b"),
                    rs.getString("x_b"),
                    rs.getString("y_b"),
                    rs.getString("a_b"),
                    rs.getString("b_b"),
                    rs.getString("c_b"),
                    rs.getString("u"),
                    rs.getString("v"),
                    rs.getString("sum_a"),
                    rs.getString("sum_b"),
                    rs.getString("sum_c"),
                    rs.getString("lhs"),
                    rs.getString("rhs"),
                    rs.getString("operator"),
                    rs.getTimestamp("confirmed_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ConsistencyConfirmationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变确认记录。
     */
    public void insert(ConsistencyConfirmation confirmation) {
        jdbcTemplate.update(
                "INSERT INTO consistency_confirmation (confirmation_id, request_id, "
                        + "observation_id_a, version_a, x_a, y_a, a_a, b_a, c_a, "
                        + "observation_id_b, version_b, x_b, y_b, a_b, b_b, c_b, "
                        + "u, v, sum_a, sum_b, sum_c, lhs, rhs, operator, confirmed_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "CURRENT_TIMESTAMP)",
                confirmation.confirmationId(),
                confirmation.requestId(),
                confirmation.observationIdA(),
                confirmation.versionA(),
                confirmation.xA(),
                confirmation.yA(),
                confirmation.aA(),
                confirmation.bA(),
                confirmation.cA(),
                confirmation.observationIdB(),
                confirmation.versionB(),
                confirmation.xB(),
                confirmation.yB(),
                confirmation.aB(),
                confirmation.bB(),
                confirmation.cB(),
                confirmation.u(),
                confirmation.v(),
                confirmation.sumA(),
                confirmation.sumB(),
                confirmation.sumC(),
                confirmation.lhs(),
                confirmation.rhs(),
                confirmation.operator(),
                Timestamp.from(confirmation.confirmedAtUtc()));
    }

    /**
     * 按全局唯一 confirmationId 查询确认记录；不存在时返回空。
     */
    public Optional<ConsistencyConfirmation> findByConfirmationId(String confirmationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM consistency_confirmation WHERE confirmation_id = ?",
                        CONFIRMATION_MAPPER, confirmationId)
                .stream().findFirst();
    }
}
