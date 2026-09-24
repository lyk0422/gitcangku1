package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 冻结快照持久化：observation_snapshot 头表与 observation_snapshot_item 逐条固化表。
 * 快照头与全部条目在同一事务内原子写入，之后只读、永不更新或删除。所有 SQL 使用参数化查询。
 */
@Repository
public class SnapshotRepository {

    private static final RowMapper<SnapshotHeader> HEADER_MAPPER = (rs, rowNum) -> new SnapshotHeader(
            rs.getString("snapshot_key"),
            rs.getTimestamp("target_time_utc").toInstant(),
            rs.getLong("global_latest_revision"),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final RowMapper<SnapshotItem> ITEM_MAPPER = (rs, rowNum) -> new SnapshotItem(
            rs.getString("snapshot_key"),
            rs.getInt("ordinal"),
            rs.getString("observation_id"),
            rs.getString("state"),
            (Integer) rs.getObject("version"),
            rs.getBoolean("deleted"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getString("last_resolution_id"));

    private final JdbcTemplate jdbcTemplate;

    public SnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入不可变快照头。
     */
    public void insertHeader(SnapshotHeader header) {
        jdbcTemplate.update(
                "INSERT INTO observation_snapshot (snapshot_key, target_time_utc, "
                        + "global_latest_revision, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                header.snapshotKey(), Timestamp.from(header.targetTimeUtc()),
                header.globalLatestVersion(), Timestamp.from(header.createdAtUtc()));
    }

    /**
     * 插入一条固化条目。
     */
    public void insertItem(SnapshotItem item) {
        jdbcTemplate.update(
                "INSERT INTO observation_snapshot_item (snapshot_key, ordinal, observation_id, state, "
                        + "version, deleted, location, reading, note, last_resolution_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                item.snapshotKey(), item.ordinal(), item.observationId(), item.state(),
                item.version(), item.deleted(), item.location(), item.reading(), item.note(),
                item.lastResolutionId());
    }

    /**
     * 按全局唯一 snapshotKey 查询快照头；不存在返回空。
     */
    public Optional<SnapshotHeader> findHeader(String snapshotKey) {
        return jdbcTemplate.query(
                        "SELECT snapshot_key, target_time_utc, global_latest_revision, created_at_utc "
                                + "FROM observation_snapshot WHERE snapshot_key = ?",
                        HEADER_MAPPER, snapshotKey)
                .stream().findFirst();
    }

    /**
     * 按 ordinal 升序查询快照全部固化条目（即 observationId 升序）。
     */
    public List<SnapshotItem> findItems(String snapshotKey) {
        return jdbcTemplate.query(
                "SELECT snapshot_key, ordinal, observation_id, state, version, deleted, "
                        + "location, reading, note, last_resolution_id "
                        + "FROM observation_snapshot_item WHERE snapshot_key = ? "
                        + "ORDER BY ordinal ASC",
                ITEM_MAPPER, snapshotKey);
    }
}
