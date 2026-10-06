package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 闭合导线成果持久化：traverse_result 与 traverse_result_edge 记录生成时冻结的完整上下文，
 * 不可变、永不更新。所有 SQL 使用参数化查询。
 */
@Repository
public class TraverseRepository {

    private static final RowMapper<TraverseResult> RESULT_MAPPER = (rs, rowNum) -> new TraverseResult(
            rs.getString("result_id"),
            rs.getString("request_id"),
            rs.getString("operator"),
            rs.getInt("edge_count"),
            rs.getString("closure_dx"),
            rs.getString("closure_dy"),
            rs.getString("total_w"),
            rs.getTimestamp("generated_at_utc").toInstant());

    private static final RowMapper<TraverseEdge> EDGE_MAPPER = (rs, rowNum) -> new TraverseEdge(
            rs.getString("result_id"),
            rs.getInt("edge_index"),
            rs.getString("observation_id"),
            rs.getInt("observation_version"),
            rs.getString("dx"),
            rs.getString("dy"),
            rs.getString("w"),
            rs.getString("corrected_dx"),
            rs.getString("corrected_dy"));

    private final JdbcTemplate jdbcTemplate;

    public TraverseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变成果记录；resultId 主键冲突时抛出重复键异常。
     */
    public void insertResult(TraverseResult result) {
        jdbcTemplate.update(
                "INSERT INTO traverse_result (result_id, request_id, operator, edge_count, "
                        + "closure_dx, closure_dy, total_w, generated_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                result.resultId(), result.requestId(), result.operator(), result.edgeCount(),
                result.closureDx(), result.closureDy(), result.totalW(),
                Timestamp.from(result.generatedAtUtc()));
    }

    /**
     * 插入一条成果边记录。
     */
    public void insertEdge(TraverseEdge edge) {
        jdbcTemplate.update(
                "INSERT INTO traverse_result_edge (result_id, edge_index, observation_id, observation_version, "
                        + "dx, dy, w, corrected_dx, corrected_dy, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                edge.resultId(), edge.edgeIndex(), edge.observationId(), edge.observationVersion(),
                edge.dx(), edge.dy(), edge.w(), edge.correctedDx(), edge.correctedDy());
    }

    /**
     * 按成果标识查询成果记录；不存在时返回空。
     */
    public Optional<TraverseResult> findResult(String resultId) {
        return jdbcTemplate.query(
                        "SELECT result_id, request_id, operator, edge_count, closure_dx, closure_dy, total_w, "
                                + "generated_at_utc FROM traverse_result WHERE result_id = ?",
                        RESULT_MAPPER, resultId)
                .stream().findFirst();
    }

    /**
     * 按成果标识按边序号查询全部成果边。
     */
    public List<TraverseEdge> findEdges(String resultId) {
        return jdbcTemplate.query(
                "SELECT result_id, edge_index, observation_id, observation_version, dx, dy, w, "
                        + "corrected_dx, corrected_dy FROM traverse_result_edge "
                        + "WHERE result_id = ? ORDER BY edge_index ASC",
                EDGE_MAPPER, resultId);
    }
}
