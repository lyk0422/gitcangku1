package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 观测记录持久化：维护 observation_current 当前状态与 observation_version 完整历史快照。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ObservationRepository {

    private static final RowMapper<ObservationSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new ObservationSnapshot(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"));

    private static final RowMapper<VersionRecord> VERSION_RECORD_MAPPER = (rs, rowNum) -> new VersionRecord(
            new ObservationSnapshot(
                    rs.getString("observation_id"),
                    rs.getInt("version"),
                    rs.getString("location"),
                    rs.getString("reading"),
                    rs.getString("note"),
                    rs.getBoolean("deleted")),
            rs.getLong("global_revision"),
            rs.getTimestamp("committed_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<ObservationSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted "
                                + "FROM observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted "
                                + "FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted "
                                + "FROM observation_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, location, reading, note, version, deleted, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容。
     */
    public void updateCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, deleted = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进，业务字段保留原值（查询时不返回）。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE observation_current SET version = ?, deleted = TRUE, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。全局版本号与提交时刻由写事务在持锁期间确定。
     */
    public void insertVersion(ObservationSnapshot snapshot, long globalRevision, Instant committedAtUtc) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, version, location, reading, note, deleted, "
                        + "global_revision, committed_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(), snapshot.deleted(),
                globalRevision, Timestamp.from(committedAtUtc));
    }

    /**
     * 判断记录是否在任何时刻存在过（历史表有任意版本）；快照创建要求集合内每个 ID 都存在过，墓碑也算存在。
     */
    public boolean existsEver(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?",
                Integer.class, observationId);
        return count != null && count > 0;
    }

    /**
     * 读取指定时刻之前（含该时刻）已提交的最后一个版本；该时刻前尚未创建返回空。
     * 按提交时刻与版本号排序，兼容同一事务内连续产生多个版本时的先后定位。
     */
    public Optional<VersionRecord> findVersionAsOf(String observationId, Instant asOfUtc) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted, "
                                + "global_revision, committed_at_utc "
                                + "FROM observation_version "
                                + "WHERE observation_id = ? AND committed_at_utc <= ? "
                                + "ORDER BY committed_at_utc DESC, version DESC "
                                + "LIMIT 1",
                        VERSION_RECORD_MAPPER, observationId, Timestamp.from(asOfUtc))
                .stream().findFirst();
    }
}
