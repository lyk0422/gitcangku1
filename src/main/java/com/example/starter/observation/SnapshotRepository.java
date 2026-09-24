package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 冻结快照持久化：observation_snapshot 头信息与 observation_snapshot_item 逐条内容。
 * 快照在单事务内写入，写入后永不更新或删除；所有 SQL 使用参数化查询。
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public SnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 快照头信息（不含逐条内容），用于幂等判定与按 requestId 重放。
     *
     * @param snapshotKey         快照标识
     * @param requestId           生成请求标识
     * @param targetTimeUtc       目标 UTC 时刻
     * @param globalLatestVersion 切刻处全局最新版本序号
     * @param idFingerprint       归一化 observationId 集合指纹
     */
    public record SnapshotHeader(
            String snapshotKey,
            String requestId,
            Instant targetTimeUtc,
            long globalLatestVersion,
            String idFingerprint) {
    }

    private static final RowMapper<SnapshotHeader> HEADER_MAPPER = (rs, rowNum) -> new SnapshotHeader(
            rs.getString("snapshot_key"),
            rs.getString("request_id"),
            rs.getTimestamp("target_time_utc").toInstant(),
            rs.getLong("global_latest_version"),
            rs.getString("id_fingerprint"));

    private static final RowMapper<SnapshotItemRecord> ITEM_MAPPER = (rs, rowNum) -> new SnapshotItemRecord(
            rs.getString("snapshot_key"),
            rs.getInt("ordinal"),
            rs.getString("observation_id"),
            ObservationState.valueOf(rs.getString("state")),
            (Integer) rs.getObject("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getString("last_resolution_id"));

    /**
     * 在快照事务内写入头信息与全部逐条内容；snapshotKey 或 requestId 唯一键冲突由调用方按 DuplicateKeyException 处理。
     */
    public void insert(SnapshotRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_snapshot (snapshot_key, request_id, target_time_utc, "
                        + "global_latest_version, id_count, id_fingerprint, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.snapshotKey(), record.requestId(), Timestamp.from(record.targetTimeUtc()),
                record.globalLatestVersion(), record.idCount(), record.idFingerprint());
        for (SnapshotItemRecord item : record.items()) {
            jdbcTemplate.update(
                    "INSERT INTO observation_snapshot_item (snapshot_key, ordinal, observation_id, version, "
                            + "state, location, reading, note, last_resolution_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    item.snapshotKey(), item.ordinal(), item.observationId(), item.version(),
                    item.state().name(), item.location(), item.reading(), item.note(), item.lastResolutionId());
        }
    }

    /**
     * 按 snapshotKey 查询头信息；不存在返回空。
     */
    public Optional<SnapshotHeader> findHeaderByKey(String snapshotKey) {
        return jdbcTemplate.query(
                        "SELECT snapshot_key, request_id, target_time_utc, global_latest_version, id_fingerprint "
                                + "FROM observation_snapshot WHERE snapshot_key = ?",
                        HEADER_MAPPER, snapshotKey)
                .stream().findFirst();
    }

    /**
     * 按 requestId 查询头信息（支撑同键同参重放）；不存在返回空。
     */
    public Optional<SnapshotHeader> findHeaderByRequestId(String requestId) {
        return jdbcTemplate.query(
                        "SELECT snapshot_key, request_id, target_time_utc, global_latest_version, id_fingerprint "
                                + "FROM observation_snapshot WHERE request_id = ?",
                        HEADER_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按 snapshotKey 加载完整不可变快照（头信息 + 按 observationId 升序的逐条内容）；不存在返回空。
     */
    public Optional<SnapshotRecord> findByKey(String snapshotKey) {
        Optional<SnapshotHeader> header = findHeaderByKey(snapshotKey);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<SnapshotItemRecord> items = jdbcTemplate.query(
                "SELECT snapshot_key, ordinal, observation_id, version, state, location, reading, note, "
                        + "last_resolution_id FROM observation_snapshot_item "
                        + "WHERE snapshot_key = ? ORDER BY ordinal ASC",
                ITEM_MAPPER, snapshotKey);
        SnapshotHeader h = header.get();
        return Optional.of(new SnapshotRecord(h.snapshotKey(), h.requestId(), h.targetTimeUtc(),
                h.globalLatestVersion(), h.idFingerprint(), List.copyOf(items)));
    }
}
