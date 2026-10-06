package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 密度估计成果持久化：density_estimate 主表与层、成员冻结明细，成功生成后不可变。
 * 所有 SQL 使用参数化查询；精确有理数以十进制整数字符串保存分子/分母。
 */
@Repository
public class EstimateRepository {

    /**
     * 成果主表行。
     */
    public record EstimateRow(String estimateId, String requestId, String fingerprint, String operator,
                              int layerCount, String totalNumerator, String totalDenominator,
                              String totalDisplay, Instant createdAtUtc) {
    }

    /**
     * 成果层表行。
     */
    public record LayerRow(int layerIndex, long totalArea, long countSum, long sampleAreaSum,
                           String densityNumerator, String densityDenominator, String densityDisplay) {
    }

    /**
     * 成果成员表行。
     */
    public record MemberRow(int layerIndex, String observationId, int obsVersion,
                            int individualCount, int sampleArea) {
    }

    private static final RowMapper<EstimateRow> ESTIMATE_MAPPER = (rs, rowNum) -> new EstimateRow(
            rs.getString("estimate_id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("operator"),
            rs.getInt("layer_count"),
            rs.getString("total_numerator"),
            rs.getString("total_denominator"),
            rs.getString("total_display"),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final RowMapper<LayerRow> LAYER_MAPPER = (rs, rowNum) -> new LayerRow(
            rs.getInt("layer_index"),
            rs.getLong("total_area"),
            rs.getLong("count_sum"),
            rs.getLong("sample_area_sum"),
            rs.getString("density_numerator"),
            rs.getString("density_denominator"),
            rs.getString("density_display"));

    private static final RowMapper<MemberRow> MEMBER_MAPPER = (rs, rowNum) -> new MemberRow(
            rs.getInt("layer_index"),
            rs.getString("observation_id"),
            rs.getInt("obs_version"),
            rs.getInt("individual_count"),
            rs.getInt("sample_area"));

    private final JdbcTemplate jdbcTemplate;

    public EstimateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入成果主表行；estimateId 主键冲突时抛出重复键异常。
     */
    public void insertEstimate(EstimateRow row) {
        jdbcTemplate.update(
                "INSERT INTO density_estimate (estimate_id, request_id, fingerprint, operator, layer_count, "
                        + "total_numerator, total_denominator, total_display, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                row.estimateId(), row.requestId(), row.fingerprint(), row.operator(), row.layerCount(),
                row.totalNumerator(), row.totalDenominator(), row.totalDisplay(),
                Timestamp.from(row.createdAtUtc()));
    }

    /**
     * 插入一条层冻结明细。
     */
    public void insertLayer(String estimateId, LayerRow layer) {
        jdbcTemplate.update(
                "INSERT INTO density_estimate_layer (estimate_id, layer_index, total_area, count_sum, "
                        + "sample_area_sum, density_numerator, density_denominator, density_display) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                estimateId, layer.layerIndex(), layer.totalArea(), layer.countSum(), layer.sampleAreaSum(),
                layer.densityNumerator(), layer.densityDenominator(), layer.densityDisplay());
    }

    /**
     * 插入一条成员冻结明细。
     */
    public void insertMember(String estimateId, MemberRow member) {
        jdbcTemplate.update(
                "INSERT INTO density_estimate_member (estimate_id, layer_index, observation_id, obs_version, "
                        + "individual_count, sample_area) VALUES (?, ?, ?, ?, ?, ?)",
                estimateId, member.layerIndex(), member.observationId(), member.obsVersion(),
                member.individualCount(), member.sampleArea());
    }

    /**
     * 按 estimateId 查询成果主表行；不存在时返回空。
     */
    public Optional<EstimateRow> findEstimate(String estimateId) {
        return jdbcTemplate.query(
                        "SELECT estimate_id, request_id, fingerprint, operator, layer_count, total_numerator, "
                                + "total_denominator, total_display, created_at_utc "
                                + "FROM density_estimate WHERE estimate_id = ?",
                        ESTIMATE_MAPPER, estimateId)
                .stream().findFirst();
    }

    /**
     * 按 estimateId 查询全部层冻结明细，按层序号升序。
     */
    public List<LayerRow> findLayers(String estimateId) {
        return jdbcTemplate.query(
                "SELECT layer_index, total_area, count_sum, sample_area_sum, density_numerator, "
                        + "density_denominator, density_display "
                        + "FROM density_estimate_layer WHERE estimate_id = ? ORDER BY layer_index ASC",
                LAYER_MAPPER, estimateId);
    }

    /**
     * 按 estimateId 查询全部成员冻结明细，按层序号、观测标识升序。
     */
    public List<MemberRow> findMembers(String estimateId) {
        return jdbcTemplate.query(
                "SELECT layer_index, observation_id, obs_version, individual_count, sample_area "
                        + "FROM density_estimate_member WHERE estimate_id = ? "
                        + "ORDER BY layer_index ASC, observation_id ASC",
                MEMBER_MAPPER, estimateId);
    }
}
