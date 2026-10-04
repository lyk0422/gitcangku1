package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 披露快照与附页持久化：disclosure_snapshot 每簇一份、不可变；disclosure_appendix 只追加。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class DisclosureRepository {

    private static final RowMapper<DisclosureSnapshotRecord> SNAPSHOT_MAPPER = (rs, rowNum) ->
            new DisclosureSnapshotRecord(
                    rs.getString("observation_id"),
                    rs.getInt("version"),
                    rs.getString("location"),
                    rs.getString("reading"),
                    rs.getString("note"),
                    rs.getBoolean("deleted"),
                    rs.getString("resolutions"),
                    rs.getString("quality_flags"),
                    rs.getString("operator"),
                    rs.getString("request_id"),
                    rs.getTimestamp("released_at_utc").toInstant());

    private static final RowMapper<AppendixRecord> APPENDIX_MAPPER = (rs, rowNum) -> new AppendixRecord(
            rs.getString("observation_id"),
            rs.getInt("seq"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"),
            rs.getString("source_request_id"),
            rs.getTimestamp("recorded_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public DisclosureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入首次发布快照；同簇并发发布时主键冲突抛出重复键异常，保证只生成一个快照。
     */
    public void insertSnapshot(DisclosureSnapshotRecord record) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_snapshot (observation_id, version, location, reading, note, deleted, "
                        + "resolutions, quality_flags, operator, request_id, released_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(), record.version(), record.location(), record.reading(), record.note(),
                record.deleted(), record.resolutions(), record.qualityFlags(), record.operator(),
                record.requestId(), Timestamp.from(record.releasedAtUtc()));
    }

    /**
     * 按簇查询披露快照；未发布时返回空。
     */
    public Optional<DisclosureSnapshotRecord> findSnapshot(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted, resolutions, "
                                + "quality_flags, operator, request_id, released_at_utc "
                                + "FROM disclosure_snapshot WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 查询全部披露快照，按发布时刻、簇标识稳定排序。
     */
    public List<DisclosureSnapshotRecord> findAllSnapshots() {
        return jdbcTemplate.query(
                "SELECT observation_id, version, location, reading, note, deleted, resolutions, "
                        + "quality_flags, operator, request_id, released_at_utc "
                        + "FROM disclosure_snapshot ORDER BY released_at_utc ASC, observation_id ASC",
                SNAPSHOT_MAPPER);
    }

    /**
     * 追加一页披露附页；序号由 nextSeq 在持有观测行锁的事务内计算，保证单调递增。
     */
    public void insertAppendix(AppendixRecord record) {
        jdbcTemplate.update(
                "INSERT INTO disclosure_appendix (observation_id, seq, version, location, reading, note, "
                        + "deleted, source_request_id, recorded_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(), record.seq(), record.version(), record.location(), record.reading(),
                record.note(), record.deleted(), record.sourceRequestId(),
                Timestamp.from(record.recordedAtUtc()));
    }

    /**
     * 读取簇的下一个附页序号（调用方须持有该簇观测行锁以串行化并发更正）。
     */
    public int nextAppendixSeq(String observationId) {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM disclosure_appendix WHERE observation_id = ?",
                Integer.class, observationId);
        return next == null ? 1 : next;
    }

    /**
     * 按簇查询附页历史，按序号升序稳定排序。
     */
    public List<AppendixRecord> findAppendices(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, seq, version, location, reading, note, deleted, source_request_id, "
                        + "recorded_at_utc FROM disclosure_appendix WHERE observation_id = ? ORDER BY seq ASC",
                APPENDIX_MAPPER, observationId);
    }

    /**
     * 统计簇的附页数量实际值。
     */
    public int countAppendices(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disclosure_appendix WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }
}