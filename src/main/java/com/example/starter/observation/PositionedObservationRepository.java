package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 观测点（新类型观测）持久化：维护 positioned 当前状态与完整历史快照。
 * 坐标与协方差矩阵作为一个完整字段组整体读写，所有 SQL 使用参数化查询。
 */
@Repository
public class PositionedObservationRepository {

    private static final RowMapper<PositionedObservationSnapshot> SNAPSHOT_MAPPER =
            (rs, rowNum) -> new PositionedObservationSnapshot(
                    rs.getString("observation_id"),
                    rs.getInt("version"),
                    rs.getBigDecimal("x"),
                    rs.getBigDecimal("y"),
                    rs.getBigDecimal("a"),
                    rs.getBigDecimal("b"),
                    rs.getBigDecimal("c"),
                    rs.getBoolean("deleted"));

    private final JdbcTemplate jdbcTemplate;

    public PositionedObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<PositionedObservationSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, x, y, a, b, c, deleted "
                                + "FROM positioned_observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一观测点的并发变更。
     */
    public Optional<PositionedObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, x, y, a, b, c, deleted "
                                + "FROM positioned_observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<PositionedObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, x, y, a, b, c, deleted "
                                + "FROM positioned_observation_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新观测点的当前状态（version = 1）。
     */
    public void insertCurrent(PositionedObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO positioned_observation_current (observation_id, x, y, a, b, c, version, deleted, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.x(), snapshot.y(), snapshot.a(), snapshot.b(), snapshot.c(),
                snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容。
     */
    public void updateCurrent(PositionedObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE positioned_observation_current SET x = ?, y = ?, a = ?, b = ?, c = ?, version = ?, "
                        + "deleted = ?, updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.x(), snapshot.y(), snapshot.a(), snapshot.b(), snapshot.c(),
                snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进，业务字段保留原值（查询时不返回）。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE positioned_observation_current SET version = ?, deleted = TRUE, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(PositionedObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO positioned_observation_version (observation_id, version, x, y, a, b, c, deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.x(), snapshot.y(),
                snapshot.a(), snapshot.b(), snapshot.c(), snapshot.deleted());
    }
}
