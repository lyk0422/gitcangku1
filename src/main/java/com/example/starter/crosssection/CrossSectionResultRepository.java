package com.example.starter.crosssection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 断面成果持久化：cross_section_result 成果头与 cross_section_result_point 冻结点列同事务写入，不可变。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class CrossSectionResultRepository {

    private static final RowMapper<CrossSectionResultRecord> RESULT_MAPPER = (rs, rowNum) ->
            new CrossSectionResultRecord(
                    rs.getString("result_id"),
                    rs.getString("request_id"),
                    rs.getString("fingerprint"),
                    rs.getString("section_no"),
                    rs.getInt("water_level_mm"),
                    rs.getString("area_num"),
                    rs.getString("area_den"),
                    rs.getString("area_text"),
                    rs.getInt("point_count"),
                    rs.getString("operator"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<CrossSectionResultPointRecord> POINT_MAPPER = (rs, rowNum) ->
            new CrossSectionResultPointRecord(
                    rs.getInt("ordinal"),
                    rs.getString("source_observation_id"),
                    rs.getInt("source_version"),
                    rs.getInt("x_mm"),
                    rs.getInt("z_mm"));

    private final JdbcTemplate jdbcTemplate;

    public CrossSectionResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入成果头（冻结 H 与精确面积）；resultId 主键或 requestId 唯一键冲突时抛出重复键异常。
     */
    public void insertResult(CrossSectionResultRecord record) {
        jdbcTemplate.update(
                "INSERT INTO cross_section_result (result_id, request_id, fingerprint, section_no, "
                        + "water_level_mm, area_num, area_den, area_text, point_count, operator, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.resultId(), record.requestId(), record.fingerprint(), record.sectionNo(),
                record.waterLevelMm(), record.areaNumerator(), record.areaDenominator(), record.areaText(),
                record.pointCount(), record.operator(), Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 追加一个冻结点，必须与成果头在同一事务内提交。
     */
    public void insertPoint(String resultId, CrossSectionResultPointRecord point) {
        jdbcTemplate.update(
                "INSERT INTO cross_section_result_point (result_id, ordinal, source_observation_id, "
                        + "source_version, x_mm, z_mm) VALUES (?, ?, ?, ?, ?, ?)",
                resultId, point.ordinal(), point.sourceObservationId(),
                point.sourceVersion(), point.xMm(), point.zMm());
    }

    /**
     * 按 resultId 查询成果头；不存在返回空。
     */
    public Optional<CrossSectionResultRecord> findResult(String resultId) {
        return jdbcTemplate.query(
                        "SELECT result_id, request_id, fingerprint, section_no, water_level_mm, area_num, "
                                + "area_den, area_text, point_count, operator, created_at "
                                + "FROM cross_section_result WHERE result_id = ?",
                        RESULT_MAPPER, resultId)
                .stream().findFirst();
    }

    /**
     * 按提交顺序读取成果冻结点列。
     */
    public List<CrossSectionResultPointRecord> findPoints(String resultId) {
        return jdbcTemplate.query(
                "SELECT ordinal, source_observation_id, source_version, x_mm, z_mm "
                        + "FROM cross_section_result_point WHERE result_id = ? ORDER BY ordinal ASC",
                POINT_MAPPER, resultId);
    }
}
