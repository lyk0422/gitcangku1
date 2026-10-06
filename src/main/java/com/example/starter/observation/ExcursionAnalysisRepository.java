package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 越限分析持久化：excursion_analysis 记录生成时冻结的完整上下文，不可变、永不更新。
 * 所有 SQL 使用参数化查询；引用集合与区间结果以 JSON 原文存储。
 */
@Repository
public class ExcursionAnalysisRepository {

    private static final RowMapper<ExcursionAnalysisRecord> RECORD_MAPPER = (rs, rowNum) ->
            new ExcursionAnalysisRecord(
                    rs.getString("analysis_id"),
                    rs.getString("request_id"),
                    rs.getString("operator"),
                    rs.getString("station_id"),
                    rs.getString("threshold"),
                    rs.getInt("consecutive_minutes"),
                    rs.getString("references_json"),
                    rs.getString("intervals_json"),
                    rs.getTimestamp("created_at_utc").toInstant());

    private static final String COLUMNS = "analysis_id, request_id, operator, station_id, threshold, "
            + "consecutive_minutes, references_json, intervals_json, created_at_utc";

    private final JdbcTemplate jdbcTemplate;

    public ExcursionAnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变分析记录；requestId 唯一键冲突时抛出重复键异常。
     */
    public void insert(ExcursionAnalysisRecord record) {
        jdbcTemplate.update(
                "INSERT INTO excursion_analysis (analysis_id, request_id, operator, station_id, threshold, "
                        + "consecutive_minutes, references_json, intervals_json, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.analysisId(), record.requestId(), record.operator(), record.stationId(),
                record.threshold(), record.consecutiveMinutes(), record.referencesJson(),
                record.intervalsJson(), Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按分析标识查询；不存在时返回空。
     */
    public Optional<ExcursionAnalysisRecord> findByAnalysisId(String analysisId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM excursion_analysis WHERE analysis_id = ?",
                        RECORD_MAPPER, analysisId)
                .stream().findFirst();
    }
}
