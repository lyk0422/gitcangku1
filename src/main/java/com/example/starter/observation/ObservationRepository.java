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

    static final RowMapper<ObservationSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> new ObservationSnapshot(
            rs.getString("observation_id"),
            rs.getString("survey_id"),
            rs.getInt("version"),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"),
            rs.getBoolean("deleted"));

    private static final String SNAPSHOT_COLUMNS =
            "observation_id, survey_id, version, location, reading, note, deleted";

    private final JdbcTemplate jdbcTemplate;

    public ObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取当前状态（不加锁），用于查询接口。
     */
    public Optional<ObservationSnapshot> findCurrent(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + SNAPSHOT_COLUMNS + " FROM observation_current WHERE observation_id = ?",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取当前状态并加行锁（SELECT ... FOR UPDATE），用于写事务内串行化同一记录的并发变更。
     */
    public Optional<ObservationSnapshot> findCurrentForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + SNAPSHOT_COLUMNS
                                + " FROM observation_current WHERE observation_id = ? FOR UPDATE",
                        SNAPSHOT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 读取指定历史版本快照；版本不存在时返回空。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT " + SNAPSHOT_COLUMNS
                                + " FROM observation_version WHERE observation_id = ? AND version = ?",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 读取严格小于指定版本的最后一个存活（非墓碑）历史版本；墓碑恢复取 BASE 来源时使用，不存在时返回空。
     */
    public Optional<ObservationSnapshot> findLastLiveVersionBefore(String observationId, int version) {
        return jdbcTemplate.query(
                        "SELECT " + SNAPSHOT_COLUMNS
                                + " FROM observation_version WHERE observation_id = ? AND version < ? "
                                + "AND deleted = FALSE ORDER BY version DESC LIMIT 1",
                        SNAPSHOT_MAPPER, observationId, version)
                .stream().findFirst();
    }

    /**
     * 插入新记录的当前状态（version = 1）。
     */
    public void insertCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_current (observation_id, survey_id, location, reading, note, version, "
                        + "deleted, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.surveyId(), snapshot.location(), snapshot.reading(),
                snapshot.note(), snapshot.version(), snapshot.deleted());
    }

    /**
     * 更新当前状态为新版本内容（surveyId 保持快照中的归属）。
     */
    public void updateCurrent(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "UPDATE observation_current SET survey_id = ?, location = ?, reading = ?, note = ?, version = ?, "
                        + "deleted = ?, updated_at = CURRENT_TIMESTAMP WHERE observation_id = ?",
                snapshot.surveyId(), snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.version(), snapshot.deleted(), snapshot.observationId());
    }

    /**
     * 将当前状态标记为删除墓碑：版本号前进，surveyId 与业务字段保留原值（查询时业务字段不返回）。
     */
    public void markDeleted(String observationId, int newVersion) {
        jdbcTemplate.update(
                "UPDATE observation_current SET version = ?, deleted = TRUE, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                newVersion, observationId);
    }

    /**
     * 为观测记录设置所属调查问卷标识（建簇时调用）。
     */
    public void assignSurvey(String observationId, String surveyId) {
        jdbcTemplate.update(
                "UPDATE observation_current SET survey_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE observation_id = ?",
                surveyId, observationId);
    }

    /**
     * 追加一条完整版本快照，历史永不删除。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, survey_id, version, location, reading, note, "
                        + "deleted, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                snapshot.observationId(), snapshot.surveyId(), snapshot.version(), snapshot.location(),
                snapshot.reading(), snapshot.note(), snapshot.deleted());
    }
}
