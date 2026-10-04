package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 禁运设置持久化：observation_embargo 每簇至多一条，设置后不可变。所有 SQL 使用参数化查询。
 */
@Repository
public class EmbargoRepository {

    private static final RowMapper<EmbargoRecord> EMBARGO_MAPPER = (rs, rowNum) -> new EmbargoRecord(
            rs.getString("observation_id"),
            rs.getTimestamp("embargo_until_utc").toInstant(),
            rs.getString("disclosure_scope"),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getTimestamp("set_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EmbargoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变禁运设置；同簇重复设置时主键冲突抛出重复键异常。
     */
    public void insert(EmbargoRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_embargo (observation_id, embargo_until_utc, disclosure_scope, "
                        + "operator, request_id, set_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                Timestamp.from(record.embargoUntilUtc()),
                record.disclosureScope(),
                record.operator(),
                record.requestId(),
                Timestamp.from(record.setAtUtc()));
    }

    /**
     * 按 observationId 查询禁运设置；未设置时返回空。
     */
    public Optional<EmbargoRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, embargo_until_utc, disclosure_scope, operator, request_id, "
                                + "set_at_utc FROM observation_embargo WHERE observation_id = ?",
                        EMBARGO_MAPPER, observationId)
                .stream().findFirst();
    }
}