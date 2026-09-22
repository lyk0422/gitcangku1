package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 冲突解决记录持久化：observation_resolution 只追加不修改，与观测版本变更同事务原子提交。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ResolutionRepository {

    private static final RowMapper<ResolutionRecord> RECORD_MAPPER = (rs, rowNum) -> new ResolutionRecord(
            rs.getString("resolution_id"),
            rs.getString("observation_id"),
            rs.getInt("base_version"),
            rs.getInt("previous_version"),
            rs.getInt("result_version"),
            rs.getBoolean("version_created"),
            rs.getString("candidate_location"),
            rs.getString("candidate_reading"),
            rs.getString("candidate_note"),
            rs.getString("conflict_fields"),
            rs.getString("selections"),
            rs.getString("fingerprint"),
            rs.getString("operator"),
            rs.getString("resolved_at"));

    private static final String COLUMNS = "resolution_id, observation_id, base_version, previous_version, "
            + "result_version, version_created, candidate_location, candidate_reading, candidate_note, "
            + "conflict_fields, selections, fingerprint, operator, resolved_at";

    private final JdbcTemplate jdbcTemplate;

    public ResolutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按解决标识查询解决记录；不存在时返回空。
     */
    public Optional<ResolutionRecord> find(String resolutionId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM observation_resolution WHERE resolution_id = ?",
                        RECORD_MAPPER, resolutionId)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询解决历史，按解决时刻与标识升序。
     */
    public List<ResolutionRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_resolution WHERE observation_id = ? "
                        + "ORDER BY resolved_at, resolution_id",
                RECORD_MAPPER, observationId);
    }

    /**
     * 追加一条不可变解决记录；resolution_id 主键冲突时由数据库串行化并发同键提交。
     */
    public void insert(ResolutionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_resolution (resolution_id, observation_id, base_version, "
                        + "previous_version, result_version, version_created, candidate_location, "
                        + "candidate_reading, candidate_note, conflict_fields, selections, fingerprint, "
                        + "operator, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.resolutionId(), record.observationId(), record.baseVersion(),
                record.previousVersion(), record.resultVersion(), record.versionCreated(),
                record.candidateLocation(), record.candidateReading(), record.candidateNote(),
                record.conflictFields(), record.selectionsJson(), record.fingerprint(),
                record.operator(), record.resolvedAt());
    }
}
