package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 到期发布快照与更正附页持久化：embargo_release 每簇至多一行、不可变；
 * embargo_release_addendum 只追加。所有 SQL 使用参数化查询。
 */
@Repository
public class ReleaseRepository {

    private static final String RELEASE_COLUMNS = "release_id, observation_id, embargo_until_utc, "
            + "disclosure_scope, released_version, location, reading, note, deleted, "
            + "conflict_resolutions, quality_flags, released_at_utc";

    private static final RowMapper<ReleaseSnapshotRecord> RELEASE_MAPPER = (rs, rowNum) -> new ReleaseSnapshotRecord(
            rs.getString("release_id"),
            rs.getString("observation_id"),
            rs.getTimestamp("embargo_until_utc").toInstant(),
            rs.getString("disclosure_scope"),
            rs.getInt("released_version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"),
            rs.getString("conflict_resolutions"),
            rs.getString("quality_flags"),
            rs.getTimestamp("released_at_utc").toInstant());

    private static final String ADDENDUM_COLUMNS = "addendum_id, observation_id, release_id, version, "
            + "location, reading, note, deleted, operation, request_id, created_at_utc";

    private static final RowMapper<ReleaseAddendumRecord> ADDENDUM_MAPPER = (rs, rowNum) -> new ReleaseAddendumRecord(
            rs.getString("addendum_id"),
            rs.getString("observation_id"),
            rs.getString("release_id"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"),
            rs.getString("operation"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ReleaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入首次发布快照；同簇并发发布由 UNIQUE(observation_id) 串行化，后到者抛重复键异常。
     */
    public void insertRelease(ReleaseSnapshotRecord record) {
        jdbcTemplate.update(
                "INSERT INTO embargo_release (release_id, observation_id, embargo_until_utc, disclosure_scope, "
                        + "released_version, location, reading, note, deleted, conflict_resolutions, "
                        + "quality_flags, released_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.releaseId(), record.observationId(), Timestamp.from(record.embargoUntilUtc()),
                record.disclosureScope(), record.releasedVersion(), record.location(), record.reading(),
                record.note(), record.deleted(), record.conflictResolutions(), record.qualityFlags(),
                Timestamp.from(record.releasedAtUtc()));
    }

    /**
     * 按簇查询发布快照；不存在时返回空。
     */
    public Optional<ReleaseSnapshotRecord> findRelease(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + RELEASE_COLUMNS + " FROM embargo_release WHERE observation_id = ?",
                        RELEASE_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 追加一条更正附页；(observation_id, version) 唯一，重复插入抛重复键异常。
     */
    public void insertAddendum(ReleaseAddendumRecord record) {
        jdbcTemplate.update(
                "INSERT INTO embargo_release_addendum (addendum_id, observation_id, release_id, version, "
                        + "location, reading, note, deleted, operation, request_id, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.addendumId(), record.observationId(), record.releaseId(), record.version(),
                record.location(), record.reading(), record.note(), record.deleted(), record.operation(),
                record.requestId(), Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按簇查询全部更正附页，按版本号升序稳定排序。
     */
    public List<ReleaseAddendumRecord> findAddenda(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + ADDENDUM_COLUMNS + " FROM embargo_release_addendum WHERE observation_id = ? "
                        + "ORDER BY version ASC, addendum_id ASC",
                ADDENDUM_MAPPER, observationId);
    }
}
