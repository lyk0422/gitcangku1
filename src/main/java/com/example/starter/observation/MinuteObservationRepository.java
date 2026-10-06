package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 分钟观测持久化：维护 minute_observation_current 当前状态与 minute_observation_version 完整历史快照。
 * 所有 SQL 使用参数化查询；时刻列存 UTC 整分钟。
 */
@Repository
public class MinuteObservationRepository {

    private static final RowMapper<MinuteObservationSnapshot> SNAPSHOT_MAPPER =
            (rs, rowNum) -> new MinuteObservationSnapshot(
                    rs.getString("observation_id"),
                    rs.getInt("version"),
                    rs.getString("station_id"),
                    toInstant(rs.getTimestamp("minute_utc")),
                    rs.getString("reading"),
                    rs.getBoolean("deleted"));

    private final JdbcTemplate jdbcTemplate;

    public MinuteObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<MinuteObservationSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, station_id, minute_utc, reading, deleted "
                                + "FROM minute_observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<MinuteObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, station_id, minute_utc, reading, deleted "
                                + "FROM minute_observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<MinuteObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, station_id, minute_utc, reading, deleted "
                                + "FROM minute_observation_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1）；同站同分钟冲突时抛出重复键异常。
     */
    public void insertCurrent(MinuteObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO minute_observation_current (observation_id, station_id, minute_utc, reading, "
                        + "version, deleted, updated_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.stationId(),
                snapshot.minuteUtc() == null ? null : Timestamp.from(snapshot.minuteUtc()),
                snapshot.reading(), snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容；同站同分钟冲突时抛出重复键异常。
     */
    public void updateCurrent(MinuteObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE minute_observation_current SET station_id = ?, minute_utc = ?, reading = ?, "
                        + "version = ?, deleted = ?, updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.stationId(),
                snapshot.minuteUtc() == null ? null : Timestamp.from(snapshot.minuteUtc()),
                snapshot.reading(), snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进，业务字段置 NULL 以释放同站同分钟唯一槽位。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE minute_observation_current SET station_id = NULL, minute_utc = NULL, reading = NULL, "
                        + "version = ?, deleted = TRUE, updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(MinuteObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO minute_observation_version (observation_id, version, station_id, minute_utc, "
                        + "reading, deleted, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.stationId(),
                snapshot.minuteUtc() == null ? null : Timestamp.from(snapshot.minuteUtc()),
                snapshot.reading(), snapshot.deleted());
    }
}
