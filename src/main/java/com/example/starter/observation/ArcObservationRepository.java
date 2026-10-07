package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 圆弧观测持久化：维护 arc_observation_current 当前状态、arc_observation_version 版本头
 * 与 arc_observation_arc 圆弧分量（完整历史，永不删除）。所有 SQL 使用参数化查询。
 */
@Repository
public class ArcObservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ArcObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<ArcObservationSnapshot> findCurrent(String observationId) {
        return findCurrentRow(observationId, false);
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ArcObservationSnapshot> findCurrentForUpdate(String observationId) {
        return findCurrentRow(observationId, true);
    }

    private Optional<ArcObservationSnapshot> findCurrentRow(String observationId, boolean forUpdate) {
        String sql = "SELECT observation_id, version, deleted, conflict_pending "
                + "FROM arc_observation_current WHERE observation_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, (rs, rowNum) -> toSnapshot(
                        rs.getString("observation_id"), rs.getInt("version"),
                        rs.getBoolean("deleted"), rs.getBoolean("conflict_pending")),
                observationId).stream().findFirst();
    }

    /**
     * 读取指定历史版本（版本头 + 圆弧分量）；版本不存在时返回空。
     */
    public Optional<ArcObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, deleted FROM arc_observation_version "
                                + "WHERE observation_id = ? AND version = ?",
                        (rs, rowNum) -> toSnapshot(rs.getString("observation_id"), rs.getInt("version"),
                                rs.getBoolean("deleted"), false),
                        observationId, version)
                .stream().findFirst();
    }

    private ArcObservationSnapshot toSnapshot(String observationId, int version,
                                              boolean deleted, boolean conflictPending) {
        List<ArcComponent> arcs = deleted ? List.of() : findArcs(observationId, version);
        return new ArcObservationSnapshot(observationId, version, deleted, conflictPending, arcs);
    }

    /**
     * 读取某版本的全部圆弧分量，按起始角升序。
     */
    public List<ArcComponent> findArcs(String observationId, int version) {
        return jdbcTemplate.query(
                "SELECT start_angle, arc_length FROM arc_observation_arc "
                        + "WHERE observation_id = ? AND version = ? ORDER BY arc_index ASC",
                (rs, rowNum) -> new ArcComponent(rs.getInt("start_angle"), rs.getInt("arc_length")),
                observationId, version);
    }

    /**
     * 插入新记录的当前状态（version = 1）。
     */
    public void insertCurrent(ArcObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO arc_observation_current (observation_id, version, deleted, conflict_pending, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.deleted(), snapshot.conflictPending());
    }

    /**
     * 更新当前状态为新版本（版本号、删除标记与冲突标记整体推进）。
     */
    public void updateCurrent(ArcObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE arc_observation_current SET version = ?, deleted = ?, conflict_pending = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.version(), snapshot.deleted(), snapshot.conflictPending(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进，同时清除未解决冲突标记。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE arc_observation_current SET version = ?, deleted = TRUE, conflict_pending = FALSE, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 仅更新未解决冲突标记（合并冲突置位、人工裁决清除），不改变版本号。
     */
    public void setConflictPending(String observationId, boolean conflictPending) {
        jdbcTemplate.update(
                "UPDATE arc_observation_current SET conflict_pending = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                conflictPending, observationId);
    }

    /**
     * 追加一条版本头，历史永不删除。
     */
    public void insertVersionHeader(String observationId, int version, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO arc_observation_version (observation_id, version, deleted, created_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                observationId, version, deleted);
    }

    /**
     * 追加某版本的全部圆弧分量，arc_index 按列表顺序（已按起始角升序）从 0 编号。
     */
    public void insertArcs(String observationId, int version, List<ArcComponent> arcs) {
        for (int i = 0; i < arcs.size(); i++) {
            ArcComponent arc = arcs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO arc_observation_arc (observation_id, version, arc_index, "
                            + "start_angle, arc_length, created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    observationId, version, i, arc.startAngle(), arc.arcLength());
        }
    }
}
