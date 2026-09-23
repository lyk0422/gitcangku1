package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 观测记录持久化：维护 observation_current 当前状态与 observation_version 完整历史快照。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ObservationRepository {

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
                rs.getString("obs_type"),
                observedAt == null ? null : observedAt.toInstant(),
                rs.getString("device_id"),
                MergeStatus.fromValue(rs.getString("merge_status")));
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
                        "SELECT observation_id, version, location, reading, note, deleted, "
                                + "site_key, obs_type, observed_at, device_id, merge_status "
                                + "FROM observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted, "
                                + "site_key, obs_type, observed_at, device_id, merge_status "
                                + "FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 对给定记录键逐个加行锁（SELECT ... FOR UPDATE）并读取当前状态。
     * 调用方须传入已升序去重的键，以保证簇事务与单记录写事务之间加锁顺序一致、避免死锁。
     * 墓碑/已归并等状态不过滤，由业务层在锁内重读后判定；不存在的键不产生行。
     */
    public List<ObservationSnapshot> findCurrentForUpdate(List<String> orderedObservationIds) {
        List<ObservationSnapshot> result = new ArrayList<>();
        for (String observationId : orderedObservationIds) {
            findCurrentForUpdate(observationId).ifPresent(result::add);
        }
        return result;
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted, "
                                + "site_key, obs_type, observed_at, device_id, merge_status "
                                + "FROM observation_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 查找同一 siteKey + type 下、观测时刻落在 [from, to] 区间的活跃未归并未删除记录，
     * 用于重复簇候选预览。只读、不加锁，不代表任何后台自动聚类结果。
     */
    public List<ObservationSnapshot> findActiveCandidates(String siteKey, String obsType,
                                                          Instant from, Instant to) {
        return jdbcTemplate.query(
                "SELECT observation_id, version, location, reading, note, deleted, "
                        + "site_key, obs_type, observed_at, device_id, merge_status "
                        + "FROM observation_current "
                        + "WHERE site_key = ? AND obs_type = ? AND deleted = FALSE AND merge_status = 'ACTIVE' "
                        + "AND observed_at BETWEEN ? AND ? "
                        + "ORDER BY observed_at ASC, observation_id ASC",
                SNAPSHOT_MAPPER, siteKey, obsType,
                Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * 插入新记录的当前状态（version = 1）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, location, reading, note, version, deleted, "
                        + "site_key, obs_type, observed_at, device_id, merge_status, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.siteKey(), snapshot.obsType(),
                toTimestamp(snapshot.observedAt()), snapshot.deviceId(), snapshot.mergeStatus().name());
    }

    /**
     * 更新当前状态为新版本内容。
     */
    public void updateCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, deleted = ?, "
                        + "site_key = ?, obs_type = ?, observed_at = ?, device_id = ?, merge_status = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.siteKey(), snapshot.obsType(),
                toTimestamp(snapshot.observedAt()), snapshot.deviceId(),
                snapshot.mergeStatus().name(), snapshot.observationId());
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
     * 将成员记录置为 MERGED：仅前进归并状态，内容字段与 generation（version）冻结不变。
     */
    public void markMerged(String observationId) {
        jdbcTemplate.update(
                "UPDATE observation_current SET merge_status = 'MERGED', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, version, location, reading, note, deleted, "
                        + "site_key, obs_type, observed_at, device_id, merge_status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(), snapshot.deleted(),
                snapshot.siteKey(), snapshot.obsType(),
                toTimestamp(snapshot.observedAt()), snapshot.deviceId(),
                snapshot.mergeStatus().name());
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
