package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 披露快照与更正附页持久化：快照每簇一行、不可变；附页按 (observation_id, seq) 追加。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class DisclosureRepository {

    private static final String SNAPSHOT_COLUMNS = "observation_id, version, location, reading, note, deleted, "
            + "resolution_id, conflict_fields, field_selections, quality_flags, operator, request_id, released_at_utc";

    private static final RowMapper<DisclosureSnapshotRecord> SNAPSHOT_MAPPER =
            (rs, rowNum) -> new DisclosureSnapshotRecord(
                    rs.getString("observation_id"),
                    rs.getInt("version"),
                    rs.getString("location"),
                    rs.getString("reading"),
                    rs.getString("note"),
                    rs.getBoolean("deleted"),
                    rs.getString("resolution_id"),
                    rs.getString("conflict_fields"),
                    rs.getString("field_selections"),
                    rs.getString("quality_flags"),
                    rs.getString("operator"),
                    rs.getString("request_id"),
                    rs.getTimestamp("released_at_utc").toInstant());

    private static final RowMapper<DisclosureAppendixRecord> APPENDIX_MAPPER =
            (rs, rowNum) -> new DisclosureAppendixRecord(
                    rs.getString("observation_id"),
                    rs.getInt("seq"),
                    rs.getString("location"),
                    rs.getString("reading"),
                    rs.getString("note"),
                    rs.getString("reason"),
                    rs.getString("operator"),
                    rs.getString("request_id"),
                    rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public DisclosureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询披露快照（不加锁）；不存在时返回空。
     */
    public Optional<DisclosureSnapshotRecord> findSnapshot(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + SNAPSHOT_COLUMNS + " FROM disclosure_snapshot WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 查询披露快照并加行锁，用于更正附页时串行化同一簇的并发追加。
     */
    public Optional<DisclosureSnapshotRecord> findSnapshotForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + SNAPSHOT_COLUMNS + " FROM disclosure_snapshot WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 插入首次发布快照；同簇并发发布由主键约束保证只生成一行（DuplicateKeyException）。
     */
    public void insertSnapshot(DisclosureSnapshotRecord record) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_snapshot (" + SNAPSHOT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.observationId(), record.version(), record.location(), record.reading(), record.note(),
                record.deleted(), record.resolutionId(), record.conflictFieldsJson(), record.fieldSelectionsJson(),
                record.qualityFlagsJson(), record.operator(), record.requestId(),
                Timestamp.from(record.releasedAtUtc()));
    }

    /**
     * 读取当前最大附页序号；须在快照行锁内调用以保证并发追加序号唯一。
     */
    public int maxAppendixSeq(String observationId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM disclosure_appendix WHERE observation_id = ?",
                Integer.class, observationId);
        return max == null ? 0 : max;
    }

    /**
     * 追加一条更正附页。
     */
    public void insertAppendix(DisclosureAppendixRecord record) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_appendix (observation_id, seq, location, reading, note, reason, "
                        + "operator, request_id, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.observationId(), record.seq(), record.location(), record.reading(), record.note(),
                record.reason(), record.operator(), record.requestId(),
                Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按 seq 升序查询全部附页，排序稳定。
     */
    public List<DisclosureAppendixRecord> findAppendices(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, seq, location, reading, note, reason, operator, request_id, created_at_utc "
                        + "FROM disclosure_appendix WHERE observation_id = ? ORDER BY seq ASC",
                APPENDIX_MAPPER, observationId);
    }

    /**
     * 统计附页总数。
     */
    public int countAppendices(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disclosure_appendix WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }
}