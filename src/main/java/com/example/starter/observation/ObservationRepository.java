package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
            rs.getInt("confidence"),
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
                        "SELECT observation_id, version, location, reading, note, confidence, deleted "
                                + "FROM observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, confidence, deleted "
                                + "FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, confidence, deleted "
                                + "FROM observation_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1，confidence = 100）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, location, reading, note, version, confidence, deleted, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.confidence(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容（含置信度）。
     */
    public void updateCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, confidence = ?, deleted = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.confidence(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 仅更新当前版本置信度（CONFIRMED 复核生效时调用，不改变观测内容与版本，不改写历史快照）。
     */
    public void updateConfidence(String observationId, int confidence) {
        jdbcTemplate.update(
                "UPDATE observation_current SET confidence = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                confidence, observationId);
    }

    /**
     * 将已确定的置信度固化到当前版本对应的历史快照行；
     * 仅回写“当前版本”那一行，更早的历史快照永不被改写。
     */
    public void updateVersionConfidence(String observationId, int version, int confidence) {
        jdbcTemplate.update(
                "UPDATE observation_version SET confidence = ? "
                        + "WHERE observation_id = ? AND version = ?",
                confidence, observationId, version);
    }

    /**
     * 查询某观测全部版本快照（含墓碑），按版本号升序，用于置信度轨迹重建。
     */
    public java.util.List<ObservationSnapshot> findAllVersions(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, confidence, deleted "
                                + "FROM observation_version WHERE observation_id = ? ORDER BY version",
                        SNAPSHOT_MAPPER, observationId);
    }

    /**
     * 查询某观测全部版本号与生成时间，按版本号升序，用于置信度轨迹的版本事件点。
     */
    public java.util.List<VersionStamp> findVersionStamps(String observationId) {
        return jdbcTemplate.query(
                "SELECT version, created_at FROM observation_version "
                        + "WHERE observation_id = ? ORDER BY version",
                (rs, rowNum) -> new VersionStamp(rs.getInt("version"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                observationId);
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进、置信度随墓碑版本固化，业务字段保留原值（查询时不返回）。
     */
    public void markDeleted(String observationId, int newVersion, int confidence) {
        jdbcTemplate.update(
                "UPDATE observation_current SET version = ?, confidence = ?, deleted = TRUE, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                newVersion, confidence, observationId);
    }

    /**
     * 追加一条完整版本快照（含该版本固化的置信度），历史永不删除。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, version, location, reading, note, confidence, deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(), snapshot.confidence(), snapshot.deleted());
    }
}
