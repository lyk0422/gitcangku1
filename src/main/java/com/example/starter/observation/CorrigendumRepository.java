package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测更正附录持久化：corrigendum_appendix 记录不可变附录链 V1..Vn。
 * 所有 SQL 使用参数化查询；附录永不更新或删除，原始观测快照亦不由此层修改。
 */
@Repository
public class CorrigendumRepository {

    private static final RowMapper<CorrigendumAppendix> APPENDIX_MAPPER = (rs, rowNum) -> new CorrigendumAppendix(
            rs.getString("appendix_id"),
            rs.getString("observation_id"),
            rs.getString("request_id"),
            rs.getInt("appendix_version"),
            (Integer) rs.getObject("previous_appendix_version"),
            rs.getString("resolution_id"),
            rs.getInt("snapshot_version"),
            rs.getString("field_name"),
            rs.getString("old_value"),
            rs.getString("new_value"),
            rs.getString("operator"),
            rs.getTimestamp("corrected_at_utc").toInstant());

    private static final String COLUMNS = "appendix_id, observation_id, request_id, appendix_version, "
            + "previous_appendix_version, resolution_id, snapshot_version, field_name, old_value, new_value, "
            + "operator, corrected_at_utc";

    private final JdbcTemplate jdbcTemplate;

    public CorrigendumRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变附录记录；(observationId, appendixVersion) 或 (observationId, requestId)
     * 唯一键冲突，或 appendixId 主键冲突时抛出重复键异常。
     */
    public void insert(CorrigendumAppendix appendix) {
        jdbcTemplate.update(
                "INSERT INTO corrigendum_appendix (appendix_id, observation_id, request_id, appendix_version, "
                        + "previous_appendix_version, resolution_id, snapshot_version, field_name, old_value, "
                        + "new_value, operator, corrected_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                appendix.appendixId(),
                appendix.observationId(),
                appendix.requestId(),
                appendix.appendixVersion(),
                appendix.previousAppendixVersion(),
                appendix.resolutionId(),
                appendix.snapshotVersion(),
                appendix.fieldName(),
                appendix.oldValue(),
                appendix.newValue(),
                appendix.operator(),
                Timestamp.from(appendix.correctedAtUtc()));
    }

    /**
     * 按全局唯一 appendixId 查询附录；不存在时返回空。
     */
    public Optional<CorrigendumAppendix> findByAppendixId(String appendixId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM corrigendum_appendix WHERE appendix_id = ?",
                        APPENDIX_MAPPER, appendixId)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询指定附录版本；不存在时返回空。
     */
    public Optional<CorrigendumAppendix> findByVersion(String observationId, int appendixVersion) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM corrigendum_appendix "
                                + "WHERE observation_id = ? AND appendix_version = ?",
                        APPENDIX_MAPPER, observationId, appendixVersion)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询全部附录，按附录版本先后（V1 起）排序。
     */
    public List<CorrigendumAppendix> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM corrigendum_appendix WHERE observation_id = ? "
                        + "ORDER BY appendix_version ASC",
                APPENDIX_MAPPER, observationId);
    }

    /**
     * 查询观测记录最新一条附录；无附录时返回空。
     */
    public Optional<CorrigendumAppendix> findLatest(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM corrigendum_appendix WHERE observation_id = ? "
                                + "ORDER BY appendix_version DESC LIMIT 1",
                        APPENDIX_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 统计观测记录的附录数量（即当前附录版本号；无附录为 0）。
     */
    public int countByObservationId(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum_appendix WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }
}
