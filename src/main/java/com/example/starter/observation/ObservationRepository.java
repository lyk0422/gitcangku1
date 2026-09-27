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

    private static final String CURRENT_COLUMNS =
            "observation_id, version, location, reading, note, deleted, "
                    + "device_id, grid_id, observed_at_utc, permit_id, permit_version, "
                    + "observation_status, review_snapshot";

    private static final RowMapper<ObservationSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new ObservationSnapshot(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"));

    private static final RowMapper<ObservationSnapshot> FULL_SNAPSHOT_MAPPER = (rs, rowNum) -> {
        String statusValue = rs.getString("observation_status");
        Timestamp observedAt = rs.getTimestamp("observed_at_utc");
        return new ObservationSnapshot(
                rs.getString("observation_id"),
                rs.getInt("version"),
                rs.getString("location"),
                rs.getString("reading"),
                rs.getString("note"),
                rs.getBoolean("deleted"),
                rs.getString("device_id"),
                (Integer) rs.getObject("grid_id"),
                observedAt == null ? null : observedAt.toInstant(),
                rs.getString("permit_id"),
                rs.getString("permit_version"),
                statusValue == null ? null : ObservationStatus.valueOf(statusValue),
                rs.getString("review_snapshot"));
    };

    private final JdbcTemplate jdbcTemplate;

    public ObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁，不含许可采样列），用于旧查询接口。
     */
    public Optional<ObservationSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted "
                                + "FROM observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前完整状态（含许可采样列，不加锁），用于查询接口。
     */
    public Optional<ObservationSnapshot> findCurrentFull(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + CURRENT_COLUMNS + " FROM observation_current WHERE observation_id = ?",
                        FULL_SNAPSHOT_MAPPER, observationId)
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
     * 读取当前完整状态并加行锁（含许可采样列），用于采样提交、批量合并与裁决事务。
     */
    public Optional<ObservationSnapshot> findCurrentFullForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + CURRENT_COLUMNS + " FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        FULL_SNAPSHOT_MAPPER, observationId)
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
     * 插入新记录的当前状态（version = 1，旧的直接创建路径，许可采样列为 NULL）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, location, reading, note, version, deleted, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted());
    }

    /**
     * 插入许可采样接收的观测当前状态（version = 1，业务状态 RECEIVED，扣减已在许可行完成）。
     */
    public void insertSampledCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, location, reading, note, version, deleted, "
                        + "device_id, grid_id, observed_at_utc, permit_id, permit_version, "
                        + "observation_status, review_snapshot, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, FALSE, ?, ?, ?, ?, ?, '"
                        + ObservationStatus.RECEIVED.name() + "', NULL, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deviceId(), snapshot.gridId(),
                Timestamp.from(snapshot.observedAtUtc()), snapshot.permitId(), snapshot.permitVersion());
    }

    /**
     * 更新当前状态为新版本内容（旧路径；许可采样列保持不变，状态由 markMerged 单独推进）。
     */
    public void updateCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, deleted = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 许可采样观测合并后推进业务内容与版本（不动许可身份列）。
     */
    public void updateSampledCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET location = ?, reading = ?, note = ?, version = ?, "
                        + "observation_status = ?, updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.location(), snapshot.reading(), snapshot.note(), snapshot.version(),
                ObservationStatus.MERGED.name(), snapshot.observationId());
    }

    /**
     * 将许可采样观测的业务状态置为 MERGED（合并结果与当前一致、不新增版本时使用）。
     */
    public void markMerged(String observationId) {
        jdbcTemplate.update(
                "UPDATE observation_current SET observation_status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ? AND observation_status IS NOT NULL",
                ObservationStatus.MERGED.name(), observationId);
    }

    /**
     * 许可撤销：将该许可下尚未合并的观测（RECEIVED，含未删除行）置为 PERMIT_REVIEW 并写入复核快照。
     * 已 MERGED、已 PERMIT_REVIEW、旧路径（permit_id 为空）与墓碑行不受影响。
     *
     * @return 实际进入复核的观测数
     */
    public int markPermitReviewForReceived(String permitId, String reviewSnapshotJson) {
        return jdbcTemplate.update(
                "UPDATE observation_current SET observation_status = ?, review_snapshot = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE permit_id = ? AND observation_status = ? AND deleted = FALSE",
                ObservationStatus.PERMIT_REVIEW.name(), reviewSnapshotJson,
                permitId, ObservationStatus.RECEIVED.name());
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
     * 在锁内读取某许可下处于指定业务状态的全部未删除观测完整行（撤销时逐行写复核快照使用）。
     */
    public java.util.List<ObservationSnapshot> findFullByPermitAndStatusForUpdate(
            String permitId, ObservationStatus status) {
        return jdbcTemplate.query(
                "SELECT " + CURRENT_COLUMNS + " FROM observation_current "
                        + "WHERE permit_id = ? AND observation_status = ? AND deleted = FALSE FOR UPDATE",
                FULL_SNAPSHOT_MAPPER, permitId, status.name());
    }

    /**
     * 将指定观测置为 PERMIT_REVIEW 并写入该行各自的复核快照；仅对 RECEIVED 未删除行生效。
     *
     * @return 更新行数（1 表示该行进入复核）
     */
    public int markSinglePermitReview(String observationId, String reviewSnapshotJson) {
        return jdbcTemplate.update(
                "UPDATE observation_current SET observation_status = ?, review_snapshot = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ? AND observation_status = ? AND deleted = FALSE",
                ObservationStatus.PERMIT_REVIEW.name(), reviewSnapshotJson,
                observationId, ObservationStatus.RECEIVED.name());
    }

    /**
     * 读取某许可下处于指定业务状态的观测数（含未删除行），供撤销后核对。
     */
    public int countByPermitAndStatus(String permitId, ObservationStatus status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE permit_id = ? AND observation_status = ?",
                Integer.class, permitId, status.name());
        return count == null ? 0 : count;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
