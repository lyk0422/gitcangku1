package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 禁运设置持久化：observation_embargo 每簇一行，写入后不可变。所有 SQL 使用参数化查询。
 */
@Repository
public class EmbargoRepository {

    private static final RowMapper<EmbargoRecord> EMBARGO_MAPPER = (rs, rowNum) -> new EmbargoRecord(
            rs.getString("observation_id"),
            rs.getTimestamp("embargo_until_utc").toInstant(),
            rs.getString("disclosure_scope"),
            rs.getString("operator"),
            rs.getInt("version_at_set"),
            rs.getString("request_id"),
            rs.getTimestamp("set_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EmbargoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入禁运设置；同簇重复设置时主键冲突抛出重复键异常。
     */
    public void insert(EmbargoRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_embargo (observation_id, embargo_until_utc, disclosure_scope, "
                        + "operator, version_at_set, request_id, set_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                Timestamp.from(record.embargoUntilUtc()),
                record.disclosureScope(),
                record.operator(),
                record.versionAtSet(),
                record.requestId(),
                Timestamp.from(record.setAtUtc()));
    }

    /**
     * 按簇查询禁运设置；未设置时返回空。
     */
    public Optional<EmbargoRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, embargo_until_utc, disclosure_scope, operator, version_at_set, "
                                + "request_id, set_at_utc FROM observation_embargo WHERE observation_id = ?",
                        EMBARGO_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 查询全部禁运设置，按设置时刻、簇标识稳定排序。
     */
    public List<EmbargoRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT observation_id, embargo_until_utc, disclosure_scope, operator, version_at_set, "
                        + "request_id, set_at_utc FROM observation_embargo "
                        + "ORDER BY set_at_utc ASC, observation_id ASC",
                EMBARGO_MAPPER);
    }
}