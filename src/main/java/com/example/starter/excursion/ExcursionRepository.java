package com.example.starter.excursion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 越限观测持久化：维护 excursion_current 当前状态与 excursion_version 完整历史快照。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ExcursionRepository {

    private static final RowMapper<ExcursionSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new ExcursionSnapshot(
            rs.getString("observation_id"),
            rs.getInt("version"),
            rs.getString("station_id"),
            rs.getString("minute_utc"),
            rs.getString("reading"),
            rs.getBoolean("deleted"));

    private static final String COLUMNS = "observation_id, version, station_id, minute_utc, reading, deleted";

    private final JdbcTemplate jdbcTemplate;

    public ExcursionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<ExcursionSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM excursion_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ExcursionSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM excursion_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ExcursionSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM excursion_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1）；同站同分钟冲突由唯一键抛出 DuplicateKeyException。
     */
    public void insertCurrent(ExcursionSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO excursion_current (observation_id, station_id, minute_utc, reading, version, deleted, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.stationId(), snapshot.minuteUtc(), snapshot.reading(),
                snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容；同站同分钟冲突由唯一键抛出 DuplicateKeyException。
     */
    public void updateCurrent(ExcursionSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE excursion_current SET station_id = ?, minute_utc = ?, reading = ?, version = ?, "
                        + "deleted = ?, updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.stationId(), snapshot.minuteUtc(), snapshot.reading(),
                snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进，minute_utc 置 NULL 释放同站同分钟唯一约束。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE excursion_current SET version = ?, deleted = TRUE, minute_utc = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(ExcursionSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO excursion_version (observation_id, version, station_id, minute_utc, reading, deleted, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.version(), snapshot.stationId(),
                snapshot.minuteUtc(), snapshot.reading(), snapshot.deleted());
    }

    /**
     * 按 observationId 列表批量加行锁读取当前状态（按传入顺序逐行锁定，调用方保证顺序一致以避免死锁）。
     */
    public List<ExcursionSnapshot> findCurrentForUpdateOrdered(List<String> observationIds) {
        return observationIds.stream()
                .map(id -> findCurrentForUpdate(id).orElse(null))
                .toList();
    }
}
