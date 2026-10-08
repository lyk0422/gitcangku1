package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 断面成果持久化：section_result 记录创建时冻结的完整点列、H 与精确面积，不可变、永不更新。
 * 所有 SQL 使用参数化查询；点列以 JSON 原文存储。
 */
@Repository
public class SectionResultRepository {

    private static final TypeReference<List<FrozenSectionPoint>> POINT_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SectionResultRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条断面成果；resultId 主键冲突时抛出重复键异常。
     */
    public void insert(SectionResult result) {
        jdbcTemplate.update(
                "INSERT INTO section_result (result_id, request_id, section_no, water_level_mm, point_count, "
                        + "points_json, area_numerator, area_denominator, operator, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                result.resultId(),
                result.requestId(),
                result.sectionNo(),
                result.waterLevelMm(),
                result.points().size(),
                writePoints(result.points()),
                result.areaNumerator().toString(),
                result.areaDenominator().toString(),
                result.operator(),
                Timestamp.from(result.createdAtUtc()));
    }

    /**
     * 按全局唯一 resultId 查询成果；不存在时返回空。
     */
    public Optional<SectionResult> findByResultId(String resultId) {
        return jdbcTemplate.query(
                        "SELECT result_id, request_id, section_no, water_level_mm, points_json, "
                                + "area_numerator, area_denominator, operator, created_at_utc "
                                + "FROM section_result WHERE result_id = ?",
                        RESULT_MAPPER, resultId)
                .stream().findFirst();
    }

    private String writePoints(List<FrozenSectionPoint> points) {
        try {
            return objectMapper.writeValueAsString(points);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize frozen points", e);
        }
    }

    private List<FrozenSectionPoint> readPoints(String json) {
        try {
            return objectMapper.readValue(json, POINT_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize frozen points", e);
        }
    }

    private final RowMapper<SectionResult> RESULT_MAPPER = (rs, rowNum) -> new SectionResult(
            rs.getString("result_id"),
            rs.getString("request_id"),
            rs.getString("section_no"),
            rs.getLong("water_level_mm"),
            readPoints(rs.getString("points_json")),
            new BigInteger(rs.getString("area_numerator")),
            new BigInteger(rs.getString("area_denominator")),
            rs.getString("operator"),
            rs.getTimestamp("created_at_utc").toInstant());
}
