package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 禁运设置持久化：observation_embargo 每簇一行，设置后不可变（仅插入与查询，无更新/删除）。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class EmbargoRepository {

    private static final RowMapper<EmbargoRecord> MAPPER = (rs, rowNum) -> new EmbargoRecord(
            rs.getString("observation_id"),
            rs.getTimestamp("embargo_until_utc").toInstant(),
            rs.getString("disclosure_scope"),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getInt("version_at_set"),
            rs.getTimestamp("set_at_utc").toInstant());

    private static final String COLUMNS = "observation_id, embargo_until_utc, disclosure_scope, operator, "
            + "request_id, version_at_set, set_at_utc";

    private final JdbcTemplate jdbcTemplate;

    public EmbargoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条禁运设置；同簇重复设置时主键冲突，由上层转为 409。
     */
    public void insert(EmbargoRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_embargo (observation_id, embargo_until_utc, disclosure_scope, "
                        + "operator, request_id, version_at_set, set_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(), Timestamp.from(record.embargoUntilUtc()), record.disclosureScope(),
                record.operator(), record.requestId(), record.versionAtSet(),
                Timestamp.from(record.setAtUtc()));
    }

    /**
     * 按簇查询禁运设置；不存在时返回空。
     */
    public Optional<EmbargoRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM observation_embargo WHERE observation_id = ?",
                        MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 诊断查询：列出全部禁运设置，按 observationId 字典序稳定排序。
     */
    public List<EmbargoRecord> findAllOrdered() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_embargo ORDER BY observation_id ASC", MAPPER);
    }
}
