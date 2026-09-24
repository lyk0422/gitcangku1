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
     * 锁定全局版本提交互斥行（SELECT ... FOR UPDATE）。
     * 所有产生观测版本的写事务与冻结快照事务都先取此锁：持锁期间其他一方无法提交，
     * 使快照的一致切刻严格按事务提交顺序全含或全不含。
     */
    public void lockGlobalWriteMutex() {
        jdbcTemplate.queryForObject(
                "SELECT lock_slot FROM observation_write_mutex WHERE lock_slot = 0 FOR UPDATE",
                Integer.class);
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
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, version, location, reading, note, deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(), snapshot.deleted());
    }

    /**
     * 在版本写事务内（提交前）登记该版本事务提交时刻（UTC）。
     * 该时刻是按时刻查询与冻结快照时间过滤的唯一时间权威，与版本行同事务原子可见。
     */
    public void insertVersionCommit(String observationId, int version, Instant committedAtUtc) {
        jdbcTemplate.update(
                "INSERT INTO observation_version_commit (observation_id, version, committed_at_utc) "
                        + "VALUES (?, ?, ?)",
                observationId, version, Timestamp.from(committedAtUtc));
    }

    /**
     * 查询某条观测记录在目标 UTC 时刻（含）之前最后一个已提交版本；该时刻尚未创建时返回空。
     * 墓碑版本同样命中，由快照的 deleted 标记区分；删除后恢复的新版本因其提交时刻更晚不会被取到。
     */
    public Optional<ObservationSnapshot> findVersionAsOf(String observationId, Instant asOfUtc) {
        return jdbcTemplate.query(
                        "SELECT v.observation_id, v.version, v.location, v.reading, v.note, v.deleted "
                                + "FROM observation_version v "
                                + "JOIN observation_version_commit c "
                                + "ON c.observation_id = v.observation_id AND c.version = v.version "
                                + "WHERE v.observation_id = ? AND c.committed_at_utc <= ? "
                                + "ORDER BY v.version DESC LIMIT 1",
                        SNAPSHOT_MAPPER, observationId, Timestamp.from(asOfUtc))
                .stream().findFirst();
    }

    /**
     * 统计目标 UTC 时刻（含）之前全局已提交的观测版本总数（跨所有记录，含墓碑）。
     * 用于冻结快照固化“读取时的全局最新版本”，并参与快照事务的全含/全不含裁决。
     */
    public long countCommittedVersions(Instant asOfUtc) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version_commit WHERE committed_at_utc <= ?",
                Long.class, Timestamp.from(asOfUtc));
        return count == null ? 0L : count;
    }
}
