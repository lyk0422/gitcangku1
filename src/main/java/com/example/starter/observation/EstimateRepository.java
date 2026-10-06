package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 密度估计成果持久化：density_estimate 主表与层、成员明细同事务原子写入，冻结后永不更新。
 * 所有 SQL 使用参数化查询；精确结果的分子/分母以十进制整数字符串存储，避免任何精度损失。
 */
@Repository
public class EstimateRepository {

    private final JdbcTemplate jdbcTemplate;

    public EstimateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 原子插入成果主表及全部层、成员明细；estimate_id 或 request_id 唯一键冲突时抛出重复键异常。
     */
    public void insert(EstimateRecord record) {
        jdbcTemplate.update(
                "INSERT INTO density_estimate (estimate_id, request_id, operator, layer_count, "
                        + "observation_count, result_numerator, result_denominator, result_display, "
                        + "created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.estimateId(), record.requestId(), record.operator(), record.layerCount(),
                record.observationCount(), record.resultNumerator(), record.resultDenominator(),
                record.resultDisplay(), Timestamp.from(record.createdAtUtc()));
        for (EstimateRecord.EstimateLayerRecord layer : record.layers()) {
            jdbcTemplate.update(
                    "INSERT INTO density_estimate_layer (estimate_id, layer_index, total_area, "
                            + "density_numerator, density_denominator) VALUES (?, ?, ?, ?, ?)",
                    record.estimateId(), layer.layerIndex(), layer.totalArea(),
                    layer.densityNumerator(), layer.densityDenominator());
            for (EstimateRecord.EstimateMemberRecord member : layer.members()) {
                jdbcTemplate.update(
                        "INSERT INTO density_estimate_member (estimate_id, layer_index, member_index, "
                                + "observation_id, observation_version, individual_count, sampled_area) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        record.estimateId(), layer.layerIndex(), member.memberIndex(),
                        member.observationId(), member.observationVersion(),
                        member.individualCount(), member.sampledArea());
            }
        }
    }

    /**
     * 按成果标识查询冻结的完整成果（主表 + 层 + 成员，按写入顺序）；不存在时返回空。
     */
    public Optional<EstimateRecord> findById(String estimateId) {
        List<EstimateRecord> heads = jdbcTemplate.query(
                "SELECT estimate_id, request_id, operator, layer_count, observation_count, "
                        + "result_numerator, result_denominator, result_display, created_at_utc "
                        + "FROM density_estimate WHERE estimate_id = ?",
                (rs, rowNum) -> new EstimateRecord(
                        rs.getString("estimate_id"),
                        rs.getString("request_id"),
                        rs.getString("operator"),
                        rs.getInt("layer_count"),
                        rs.getInt("observation_count"),
                        rs.getString("result_numerator"),
                        rs.getString("result_denominator"),
                        rs.getString("result_display"),
                        rs.getTimestamp("created_at_utc").toInstant(),
                        List.of()),
                estimateId);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        EstimateRecord head = heads.get(0);
        List<EstimateRecord.EstimateLayerRecord> layers = jdbcTemplate.query(
                "SELECT layer_index, total_area, density_numerator, density_denominator "
                        + "FROM density_estimate_layer WHERE estimate_id = ? ORDER BY layer_index ASC",
                (rs, rowNum) -> new EstimateRecord.EstimateLayerRecord(
                        rs.getInt("layer_index"),
                        rs.getInt("total_area"),
                        rs.getString("density_numerator"),
                        rs.getString("density_denominator"),
                        findMembers(estimateId, rs.getInt("layer_index"))),
                estimateId);
        return Optional.of(new EstimateRecord(head.estimateId(), head.requestId(), head.operator(),
                head.layerCount(), head.observationCount(), head.resultNumerator(),
                head.resultDenominator(), head.resultDisplay(), head.createdAtUtc(), layers));
    }

    private List<EstimateRecord.EstimateMemberRecord> findMembers(String estimateId, int layerIndex) {
        return jdbcTemplate.query(
                "SELECT member_index, observation_id, observation_version, individual_count, sampled_area "
                        + "FROM density_estimate_member WHERE estimate_id = ? AND layer_index = ? "
                        + "ORDER BY member_index ASC",
                (rs, rowNum) -> new EstimateRecord.EstimateMemberRecord(
                        rs.getInt("member_index"),
                        rs.getString("observation_id"),
                        rs.getInt("observation_version"),
                        rs.getInt("individual_count"),
                        rs.getInt("sampled_area")),
                estimateId, layerIndex);
    }
}
