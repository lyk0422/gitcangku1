package com.example.starter.excursion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 越限区间分析持久化：excursion_analysis 只插不改，分析结果生成后冻结。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class AnalysisRepository {

    private static final RowMapper<AnalysisRecord> RECORD_MAPPER = (rs, rowNum) -> new AnalysisRecord(
            rs.getString("analysis_id"),
            rs.getString("request_id"),
            rs.getString("station_id"),
            rs.getString("threshold_h"),
            rs.getInt("consecutive_minutes"),
            rs.getInt("reference_count"),
            rs.getString("references_json"),
            rs.getString("intervals_json"),
            rs.getString("operator"),
            rs.getString("created_at_utc"));

    private final JdbcTemplate jdbcTemplate;

    public AnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按分析标识查询；不存在时返回空。
     */
    public Optional<AnalysisRecord> findByAnalysisId(String analysisId) {
        return jdbcTemplate.query(
                        "SELECT analysis_id, request_id, station_id, threshold_h, consecutive_minutes, "
                                + "reference_count, references_json, intervals_json, operator, created_at_utc "
                                + "FROM excursion_analysis WHERE analysis_id = ?",
                        RECORD_MAPPER, analysisId)
                .stream().findFirst();
    }

    /**
     * 插入冻结的分析结果；request_id 唯一约束冲突时抛出 DuplicateKeyException。
     */
    public void insert(AnalysisRecord record) {
        jdbcTemplate.update(
                "INSERT INTO excursion_analysis (analysis_id, request_id, station_id, threshold_h, "
                        + "consecutive_minutes, reference_count, references_json, intervals_json, operator, "
                        + "created_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.analysisId(), record.requestId(), record.stationId(), record.thresholdH(),
                record.consecutiveMinutes(), record.referenceCount(), record.referencesJson(),
                record.intervalsJson(), record.operator(), record.createdAtUtc());
    }
}
