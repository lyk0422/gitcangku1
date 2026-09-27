package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测记录持久化：维护 observation_current 当前状态与 observation_version 完整历史快照。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ObservationRepository {

    private static final String CURRENT_COLUMNS =
            "observation_id, version, location, reading, note, deleted, "
                    + "device_id, grid_x, grid_y, collected_at_utc, permit_id, permit_version, review_status";

    private static final String VERSION_COLUMNS =
            "observation_id, version, location, reading, note, deleted, "
                    + "device_id, grid_x, grid_y, collected_at_utc, permit_id, permit_version";

    private static final RowMapper<ObservationSnapshot> CURRENT_MAPPER = (rs, rowNum) -> new ObservationSnapshot(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"),
            rs.getString("device_id"),
            (Integer) rs.getObject("grid_x"),
            (Integer) rs.getObject("grid_y"),
            rs.getTimestamp("collected_at_utc") == null ? null : rs.getTimestamp("collected_at_utc").toInstant(),
            rs.getString("permit_id"),
            (Integer) rs.getObject("permit_version"),
            rs.getString("review_status"));

    private static final RowMapper<ObservationSnapshot> VERSION_MAPPER = (rs, rowNum) -> new ObservationSnapshot(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"),
            rs.getString("device_id"),
            (Integer) rs.getObject("grid_x"),
            (Integer) rs.getObject("grid_y"),
            rs.getTimestamp("collected_at_utc") == null ? null : rs.getTimestamp("collected_at_utc").toInstant(),
            rs.getString("permit_id"),
            (Integer) rs.getObject("permit_version"),
            null);

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
                        CURRENT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + CURRENT_COLUMNS + " FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        CURRENT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT " + VERSION_COLUMNS
                                + " FROM observation_version WHERE observation_id = ? AND version = ?",
                        VERSION_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, location, reading, note, "
                        + "device_id, grid_x, grid_y, collected_at_utc, permit_id, permit_version, review_status, "
                        + "version, deleted, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.deviceId(), snapshot.gridX(), snapshot.gridY(),
                Timestamp.from(snapshot.collectedAtUtc()),
                snapshot.permitId(), snapshot.permitVersion(), snapshot.reviewStatus(),
                snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容；设备、网格、采集时刻与许可信息在提交时绑定，不随合并变化。
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
     * 更新复核标记（许可撤销时将未合并观测标记为 PERMIT_REVIEW）。
     */
    public void updateReviewStatus(String observationId, String reviewStatus) {
        jdbcTemplate.update(
                "UPDATE observation_current SET review_status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                reviewStatus, observationId);
    }

    /**
     * 查询指定许可下、指定复核标记的当前观测记录（用于许可撤销时筛出待复核观测）。
     */
    public List<ObservationSnapshot> findByPermitAndReviewStatus(String permitId, String reviewStatus) {
        return jdbcTemplate.query(
                "SELECT " + CURRENT_COLUMNS
                        + " FROM observation_current WHERE permit_id = ? AND review_status = ? AND deleted = FALSE "
                        + "ORDER BY observation_id FOR UPDATE",
                CURRENT_MAPPER, permitId, reviewStatus);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, version, location, reading, note, "
                        + "device_id, grid_x, grid_y, collected_at_utc, permit_id, permit_version, "
                        + "deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(),
                snapshot.deviceId(), snapshot.gridX(), snapshot.gridY(),
                snapshot.collectedAtUtc() == null ? null : Timestamp.from(snapshot.collectedAtUtc()),
                snapshot.permitId(), snapshot.permitVersion(), snapshot.deleted());
    }
}
