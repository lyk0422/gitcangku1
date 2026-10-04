package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 披露快照与附页持久化：disclosure_snapshot 每簇至多一份且不可变，
 * disclosure_appendix 只追加不更新。所有 SQL 使用参数化查询。
 */
@Repository
public class DisclosureRepository {

    private static final RowMapper<DisclosureSnapshotRecord> SNAPSHOT_MAPPER = (rs, rowNum) ->
            new DisclosureSnapshotRecord(
                    rs.getString("observation_id"),
                    rs.getInt("winning_version"),
                    rs.getString("location"),
                    rs.getString("reading"),
                    rs.getString("note"),
                    rs.getBoolean("deleted"),
                    rs.getString("resolutions"),
                    rs.getString("quality_flags"),
                    rs.getString("release_request_id"),
                    rs.getString("operator"),
                    rs.getTimestamp("released_at_utc").toInstant());

    private static final RowMapper<AppendixRecord> APPENDIX_MAPPER = (rs, rowNum) -> new AppendixRecord(
            rs.getString("observation_id"),
            rs.getInt("seq"),
            rs.getInt("version"),
            rs.getString("operation"),
            rs.getString("request_id"),
            rs.getTimestamp("recorded_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public DisclosureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入首次披露快照；同簇并发发布时主键冲突抛出重复键异常，由调用方重读已提交快照。
     */
    public void insertSnapshot(DisclosureSnapshotRecord record) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_snapshot (observation_id, winning_version, location, reading, note, "
                        + "deleted, resolutions, quality_flags, release_request_id, operator, released_at_utc, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                record.winningVersion(),
                record.location(),
                record.reading(),
                record.note(),
                record.deleted(),
                record.resolutionsJson(),
                record.qualityFlagsJson(),
                record.releaseRequestId(),
                record.operator(),
                Timestamp.from(record.releasedAtUtc()));
    }

    /**
     * 按 observationId 查询首次披露快照；未发布时返回空。
     */
    public Optional<DisclosureSnapshotRecord> findSnapshot(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, winning_version, location, reading, note, deleted, resolutions, "
                                + "quality_flags, release_request_id, operator, released_at_utc "
                                + "FROM disclosure_snapshot WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 追加一条附页；seq 由 nextSeq 在同事务内分配。
     */
    public void insertAppendix(AppendixRecord record) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_appendix (observation_id, seq, version, operation, request_id, "
                        + "recorded_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                record.seq(),
                record.version(),
                record.operation(),
                record.requestId(),
                Timestamp.from(record.recordedAtUtc()));
    }

    /**
     * 分配下一个附页序号；调用方需持有观测记录行锁以串行化并发更正。
     */
    public int nextAppendixSeq(String observationId) {
        Integer seq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM disclosure_appendix WHERE observation_id = ?",
                Integer.class, observationId);
        return seq == null ? 1 : seq;
    }

    /**
     * 按 seq 升序查询指定观测记录的全部附页，排序稳定。
     */
    public List<AppendixRecord> findAppendices(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, seq, version, operation, request_id, recorded_at_utc "
                        + "FROM disclosure_appendix WHERE observation_id = ? ORDER BY seq ASC",
                APPENDIX_MAPPER, observationId);
    }
}