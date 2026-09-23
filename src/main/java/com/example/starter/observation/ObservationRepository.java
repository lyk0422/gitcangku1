package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测记录持久化：维护 observation_current 当前状态与 observation_version 完整历史快照。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ObservationRepository {

    private static final String CURRENT_COLUMNS =
            "observation_id, location, reading, note, version, deleted, "
                    + "site_key, observation_type, observed_at, device_id, record_status, origin, merged_into";

    private static final String VERSION_COLUMNS =
            "observation_id, version, location, reading, note, deleted, "
                    + "site_key, observation_type, observed_at, device_id, record_status, origin, merged_into";

    private static final RowMapper<ObservationSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> {
        Timestamp observedAt = rs.getTimestamp("observed_at");
        return new ObservationSnapshot(
                rs.getString("observation_id"),
                rs.getInt("version"),
                rs.getString("location"),
                rs.getString("reading"),
                rs.getString("note"),
                rs.getBoolean("deleted"),
                rs.getString("site_key"),
                rs.getString("observation_type"),
                observedAt == null ? null : observedAt.toInstant(),
                rs.getString("device_id"),
                RecordStatus.valueOf(rs.getString("record_status")),
                RecordOrigin.valueOf(rs.getString("origin")),
                rs.getString("merged_into"));
    };

    private final JdbcTemplate jdbcTemplate;

    public ObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<ObservationSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + CURRENT_COLUMNS + " FROM observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + CURRENT_COLUMNS + " FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 在写事务内对多条记录依次加行锁（按记录键排序加锁，避免死锁），返回全部存在的当前状态。
     * 调用方需校验返回数量与键集合一致。
     */
    public List<ObservationSnapshot> findCurrentForUpdate(List<String> observationIds) {
        List<String> ordered = observationIds.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        return jdbcTemplate.query(
                "SELECT " + CURRENT_COLUMNS + " FROM observation_current "
                        + "WHERE observation_id IN (" + placeholders + ") FOR UPDATE",
                SNAPSHOT_MAPPER, ordered.toArray());
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT " + VERSION_COLUMNS + " FROM observation_version "
                                + "WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, location, reading, note, version, deleted, "
                        + "site_key, observation_type, observed_at, device_id, record_status, origin, merged_into, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.siteKey(), snapshot.observationType(),
                Timestamp.from(snapshot.observedAt()), snapshot.deviceId(),
                snapshot.status().name(), snapshot.origin().name(), snapshot.mergedInto());
    }

    /**
     * 更新当前状态为新版本内容（保留站点/类型/观测时间/设备与归并状态列的最新值）。
     */
    public void updateCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, deleted = ?, "
                        + "site_key = ?, observation_type = ?, observed_at = ?, device_id = ?, "
                        + "record_status = ?, origin = ?, merged_into = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.siteKey(), snapshot.observationType(),
                Timestamp.from(snapshot.observedAt()), snapshot.deviceId(),
                snapshot.status().name(), snapshot.origin().name(), snapshot.mergedInto(),
                snapshot.observationId());
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
     * 将成员记录标记为已归并：记录 MERGED 状态与归并目标主记录键；版本（代次）不再前进，历史可查。
     */
    public void markMerged(String observationId, String canonicalRecordId) {
        jdbcTemplate.update(
                "UPDATE observation_current SET record_status = 'MERGED', merged_into = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                canonicalRecordId, observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, version, location, reading, note, deleted, "
                        + "site_key, observation_type, observed_at, device_id, record_status, origin, merged_into, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(), snapshot.deleted(),
                snapshot.siteKey(), snapshot.observationType(),
                snapshot.observedAt() == null ? null : Timestamp.from(snapshot.observedAt()),
                snapshot.deviceId(), snapshot.status().name(), snapshot.origin().name(),
                snapshot.mergedInto());
    }
}
